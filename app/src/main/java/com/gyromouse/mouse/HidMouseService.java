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
import android.content.Context;
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

    /**
     * 状态与提示都以字符串资源 id 传递，界面按当前语言渲染，
     * 因此切换语言后重新显示的文字不会残留旧语言。
     */
    public interface Listener {
        void onStatusChanged(int statusRes, String arg, boolean connected);

        void onToast(int messageRes);
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
    private int lastStatusRes = R.string.status_starting;
    private String lastStatusArg = null;
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

    /** 语言：让服务自己拼出的文字（通知等）使用应用内所选语言。 */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(I18n.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForegroundCompat();
        initBluetooth();
    }

    /** 语言切换后刷新常驻通知与通知渠道文字（不重启 HID 连接）。 */
    public static void refreshNotification() {
        HidMouseService s = instance;
        if (s == null) return;
        s.createNotificationChannel();
        s.updateNotification();
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
            status(R.string.st_no_bluetooth, false);
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                status(R.string.st_bluetooth_off, false);
                return;
            }
        } catch (SecurityException e) {
            status(R.string.st_permission_missing, false);
            return;
        }

        status(R.string.st_initializing, false);
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
                    status(R.string.st_service_disconnected, false);
                }
            }, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) {
            status(R.string.st_permission_missing, false);
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
            status(requested ? R.string.st_registering : R.string.st_register_request_failed, false);
        } catch (SecurityException e) {
            status(R.string.st_permission_missing, false);
        } catch (Exception e) {
            Log.e(TAG, "registerApp failed", e);
            status(R.string.st_register_failed, String.valueOf(e.getMessage()), false);
        }
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean reg) {
            registered = reg;
            if (reg) {
                status(R.string.st_registered, false);
                toast(R.string.toast_hid_registered);
            } else {
                status(R.string.st_registration_cancelled, false);
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
                status(R.string.st_connected, nameOf(device), true);
                toast(R.string.toast_connected);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                host = null;
                stopTilt();
                status(registered ? R.string.st_disconnected_waiting : R.string.st_not_connected, false);
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
            status(R.string.st_host_disconnected, false);
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
            status(R.string.st_no_sensor, connected);
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
            return getString(R.string.host_unknown);
        }
    }

    private void status(int res, boolean conn) {
        status(res, null, conn);
    }

    private void status(int res, String arg, boolean conn) {
        lastStatusRes = res;
        lastStatusArg = arg;
        Listener l = listener;
        if (l != null) {
            l.onStatusChanged(res, arg, conn);
        }
    }

    private void notifyStatus() {
        Listener l = listener;
        if (l != null) {
            l.onStatusChanged(lastStatusRes, lastStatusArg, connected);
        }
    }

    private void toast(int messageRes) {
        Listener l = listener;
        if (l != null) {
            l.onToast(messageRes);
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Context c = I18n.wrap(this);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.notif_channel_description));
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Context c = I18n.wrap(this);
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CHANNEL_ID)
                .setContentTitle(c.getString(R.string.app_name))
                .setContentText(c.getString(connected ? R.string.notif_connected : R.string.notif_waiting))
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
