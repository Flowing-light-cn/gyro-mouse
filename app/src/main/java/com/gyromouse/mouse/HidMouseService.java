package com.gyromouse.mouse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台服务：把手机注册成标准蓝牙 HID 鼠标，并把传感器/触摸事件转换成鼠标报告。
 */
public class HidMouseService extends Service implements TiltController.Sink {

    private static final String TAG = "HidMouseService";
    private static final String CHANNEL_ID = "hid_mouse";
    private static final int NOTIF_ID = 1;

    public static final int BTN_LEFT = 1;
    public static final int BTN_RIGHT = 2;
    public static final int BTN_MIDDLE = 4;
    public static final int BTN_BACK = 8;
    public static final int BTN_FORWARD = 16;

    public interface Listener {
        void onStatusChanged(String status, boolean connected);

        void onToast(String message);
    }

    private static volatile HidMouseService instance;
    private static volatile Listener listener;
    private static volatile int pendingSpeedLevel = 40;
    private static volatile boolean pendingInverted = false;

    /** 标准 5 键鼠标 + 滚轮的 HID 报告描述符。 */
    private static final byte[] REPORT_DESCRIPTOR = new byte[]{
            (byte) 0x05, (byte) 0x01,        // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x02,        // Usage (Mouse)
            (byte) 0xA1, (byte) 0x01,        // Collection (Application)
            (byte) 0x09, (byte) 0x01,        //   Usage (Pointer)
            (byte) 0xA1, (byte) 0x00,        //   Collection (Physical)
            (byte) 0x05, (byte) 0x09,        //     Usage Page (Button)
            (byte) 0x19, (byte) 0x01,        //     Usage Minimum (1)
            (byte) 0x29, (byte) 0x05,        //     Usage Maximum (5)
            (byte) 0x15, (byte) 0x00,        //     Logical Minimum (0)
            (byte) 0x25, (byte) 0x01,        //     Logical Maximum (1)
            (byte) 0x95, (byte) 0x05,        //     Report Count (5)
            (byte) 0x75, (byte) 0x01,        //     Report Size (1)
            (byte) 0x81, (byte) 0x02,        //     Input (Data,Var,Abs)
            (byte) 0x95, (byte) 0x01,        //     Report Count (1)
            (byte) 0x75, (byte) 0x03,        //     Report Size (3)
            (byte) 0x81, (byte) 0x01,        //     Input (Const,Array,Abs)
            (byte) 0x05, (byte) 0x01,        //     Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x30,        //     Usage (X)
            (byte) 0x09, (byte) 0x31,        //     Usage (Y)
            (byte) 0x09, (byte) 0x38,        //     Usage (Wheel)
            (byte) 0x15, (byte) 0x81,        //     Logical Minimum (-127)
            (byte) 0x25, (byte) 0x7F,        //     Logical Maximum (127)
            (byte) 0x75, (byte) 0x08,        //     Report Size (8)
            (byte) 0x95, (byte) 0x03,        //     Report Count (3)
            (byte) 0x81, (byte) 0x06,        //     Input (Data,Var,Rel)
            (byte) 0xC0,                     //   End Collection
            (byte) 0xC0                      // End Collection
    };

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private boolean registered = false;
    private volatile boolean connected = false;
    private int buttons = 0;

    private TiltController tilt;
    private String lastStatus = "正在启动…";
    private boolean destroyed = false;

    public static HidMouseService get() {
        return instance;
    }

    public static void setListener(Listener l) {
        listener = l;
        HidMouseService s = instance;
        if (l != null && s != null) {
            s.notifyStatus();
        }
    }

    public static void calibrateNow() {
        HidMouseService s = instance;
        if (s != null && s.tilt != null) {
            s.tilt.calibrate();
        }
    }

    public static void setSpeedLevel(int level) {
        pendingSpeedLevel = level;
        HidMouseService s = instance;
        if (s != null && s.tilt != null) {
            s.tilt.setSpeedLevel(level);
        }
    }

    public static void setInverted(boolean inverted) {
        pendingInverted = inverted;
        HidMouseService s = instance;
        if (s != null && s.tilt != null) {
            s.tilt.setInverted(inverted);
        }
    }

    public static float[] getTiltState() {
        HidMouseService s = instance;
        if (s != null && s.tilt != null) {
            return s.tilt.getState();
        }
        return new float[]{0f, 0f, 0f};
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForegroundCompat();
        initBluetooth();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        stopTilt();
        try {
            if (hid != null && registered) {
                hid.unregisterApp();
            }
        } catch (Exception e) {
            Log.w(TAG, "unregisterApp failed", e);
        }
        try {
            if (adapter != null && hid != null) {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            }
        } catch (Exception ignored) {
        }
        instance = null;
        super.onDestroy();
    }

    private void initBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            status("此设备不支持蓝牙", false);
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                status("请先打开手机蓝牙", false);
                return;
            }
        } catch (SecurityException e) {
            status("缺少蓝牙权限，请重新打开应用授权", false);
            return;
        }

        status("正在初始化蓝牙…", false);
        try {
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    hid = (BluetoothHidDevice) proxy;
                    registerHid();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    hid = null;
                    registered = false;
                    connected = false;
                    stopTilt();
                    status("蓝牙服务断开", false);
                }
            }, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) {
            status("缺少蓝牙权限，请重新打开应用授权", false);
        }
    }

    private void registerHid() {
        if (hid == null || destroyed) return;
        try {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "Gyro Mouse",
                    "Tilt controlled Bluetooth mouse",
                    "GyroMouse",
                    BluetoothHidDevice.SUBCLASS1_MOUSE,
                    REPORT_DESCRIPTOR);
            boolean requested = hid.registerApp(sdp, null, null, getMainExecutor(), callback);
            status(requested ? "正在注册 HID 设备…" : "HID 注册请求失败", false);
        } catch (SecurityException e) {
            status("缺少蓝牙权限，请重新打开应用授权", false);
        } catch (Exception e) {
            Log.e(TAG, "registerApp failed", e);
            status("HID 注册失败：" + e.getMessage(), false);
        }
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean reg) {
            registered = reg;
            if (reg) {
                status("HID 已注册，等待电脑连接", false);
                toast("HID 注册成功，现在可以在电脑上配对");
            } else {
                status("HID 注册被取消", false);
                stopTilt();
            }
            updateNotification();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                connected = true;
                startTilt();
                status("已连接：" + nameOf(device), true);
                toast("已连接电脑，倾斜手机试试");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                host = null;
                stopTilt();
                status(registered ? "已断开，等待电脑重新连接" : "未连接", false);
            }
            updateNotification();
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int len) {
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
        }

        @Override
        public void onSetProtocol(BluetoothDevice device, byte protocol) {
        }

        @Override
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            connected = false;
            host = null;
            stopTilt();
            status("电脑已断开连接", false);
            updateNotification();
        }
    };

    public void pressButtons(int mask) {
        buttons |= mask;
        sendReport(0, 0, 0);
    }

    public void releaseButtons(int mask) {
        buttons &= ~mask;
        sendReport(0, 0, 0);
    }

    public void wheel(int delta) {
        sendReport(0, 0, delta);
    }

    @Override
    public void onMove(int dx, int dy) {
        sendReport(dx, dy, 0);
    }

    private synchronized void sendReport(int dx, int dy, int wheel) {
        if (!connected || hid == null || host == null) return;
        byte[] data = new byte[]{
                (byte) (buttons & 0xFF),
                (byte) clamp(dx),
                (byte) clamp(dy),
                (byte) clamp(wheel)
        };
        try {
            hid.sendReport(host, 0, data);
        } catch (Exception e) {
            Log.w(TAG, "sendReport failed", e);
        }
    }

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    private void startTilt() {
        if (tilt == null) {
            tilt = new TiltController(this, this);
            tilt.setSpeedLevel(pendingSpeedLevel);
            tilt.setInverted(pendingInverted);
        }
        if (!tilt.isAvailable()) {
            status("未检测到重力传感器，无法倾斜控制", connected);
            return;
        }
        tilt.start();
    }

    private void stopTilt() {
        if (tilt != null) {
            tilt.stop();
        }
    }

    private String nameOf(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (Exception e) {
            return "电脑";
        }
    }

    private void status(String s, boolean conn) {
        lastStatus = s;
        Listener l = listener;
        if (l != null) {
            l.onStatusChanged(s, conn);
        }
    }

    private void notifyStatus() {
        Listener l = listener;
        if (l != null) {
            l.onStatusChanged(lastStatus, connected);
        }
    }

    private void toast(String s) {
        Listener l = listener;
        if (l != null) {
            l.onToast(s);
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "蓝牙鼠标",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持蓝牙 HID 连接");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("陀螺仪鼠标")
                .setContentText(connected ? "已连接电脑" : "等待电脑连接")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                return;
            } catch (Exception e) {
                Log.w(TAG, "typed startForeground failed", e);
            }
        }
        try {
            startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            stopSelf();
        }
    }

    private void updateNotification() {
        if (destroyed) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification());
            }
        } catch (Exception ignored) {
        }
    }
}
