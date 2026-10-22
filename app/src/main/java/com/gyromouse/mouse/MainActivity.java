package com.gyromouse.mouse;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public class MainActivity extends Activity implements HidMouseService.Listener, ControlPanelView.Listener {

    private static final int REQ_PERMS = 100;

    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_TEXT = 0xFFE3EAF2;
    private static final int COLOR_MUTED = 0xFF8794A3;
    private static final int COLOR_GREEN = 0xFF66BB6A;
    private static final int COLOR_RED = 0xFFEF5350;
    private static final int COLOR_BTN = 0xFF1B232B;
    private static final int COLOR_BORDER = 0xFF2A343D;

    private TextView tvStatus;
    private TextView tvHint;
    private ControlPanelView panel;
    private SharedPreferences prefs;
    private Button btnDiscover;
    private Button btnCal;
    private boolean editing = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tiltPoller = new Runnable() {
        @Override
        public void run() {
            if (panel != null) {
                float[] s = HidMouseService.getTiltState();
                panel.setTilt(s[0], s[1], s[2]);
            }
            handler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        buildUi();
        requestPermissionsIfNeeded();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(pad, dp(10), pad, 0);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnSettings = styledButton("设置");
        topRow.addView(btnSettings, new LinearLayout.LayoutParams(dp(72), dp(40)));

        tvStatus = new TextView(this);
        tvStatus.setTextSize(17);
        tvStatus.setTextColor(COLOR_TEXT);
        tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvStatus.setSingleLine(true);
        tvStatus.setEllipsize(TextUtils.TruncateAt.END);
        tvStatus.setPadding(dp(12), 0, 0, 0);
        tvStatus.setText("正在启动…");
        topRow.addView(tvStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvHint = new TextView(this);
        tvHint.setTextSize(12);
        tvHint.setTextColor(COLOR_MUTED);
        tvHint.setPadding(0, dp(6), 0, dp(10));
        tvHint.setText("请授予蓝牙权限");

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnDiscover = styledButton("电脑发现本机");
        btnCal = styledButton("校准中位");
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp1.rightMargin = dp(8);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        btnRow.addView(btnDiscover, lp1);
        btnRow.addView(btnCal, lp2);

        panel = new ControlPanelView(this);
        panel.setListener(this);

        root.addView(topRow);
        root.addView(tvHint);
        root.addView(btnRow);
        root.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        applySettings();

        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnDiscover.setOnClickListener(v -> {
            if (editing) {
                resetCustomLayout();
            } else {
                requestDiscoverable();
            }
        });
        btnCal.setOnClickListener(v -> {
            if (editing) {
                saveLayoutAndExit();
            } else {
                HidMouseService.calibrateNow();
                toast("已把当前姿态校准为零点");
            }
        });
    }

    private void enterEditMode() {
        editing = true;
        panel.setEditMode(true);
        tvHint.setText("拖动按钮调整位置，拖右下角圆点缩放；完成后点“完成布局”");
        btnDiscover.setText("重置默认");
        btnCal.setText("完成布局");
    }

    private void exitEditMode() {
        editing = false;
        panel.setEditMode(false);
        tvHint.setText("倾斜手机移动指针；按钮控制左/右/中键、滚轮、侧键");
        btnDiscover.setText("电脑发现本机");
        btnCal.setText("校准中位");
    }

    private void saveLayoutAndExit() {
        String[] layout = panel.exportLayout();
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < layout.length; i++) {
            e.putString("layout_" + i, layout[i]);
        }
        e.putBoolean("has_custom_layout", true);
        e.apply();
        exitEditMode();
        toast("布局已保存");
    }

    private void resetCustomLayout() {
        panel.resetLayout();
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < ControlPanelView.ITEM_COUNT; i++) {
            e.remove("layout_" + i);
        }
        e.putBoolean("has_custom_layout", false);
        e.apply();
        toast("已恢复默认布局");
    }

    private void applySettings() {
        boolean invert = prefs.getBoolean("inverted", false);
        int speed = prefs.getInt("speed", 40);
        int wheelLevel = prefs.getInt("wheel_level", 75);

        panel.setWheelStepDp(12f + (100 - wheelLevel) * 0.88f);
        HidMouseService.setInverted(invert);
        HidMouseService.setSpeedLevel(speed);

        if (prefs.getBoolean("has_custom_layout", false)) {
            String[] layout = new String[ControlPanelView.ITEM_COUNT];
            for (int i = 0; i < ControlPanelView.ITEM_COUNT; i++) {
                layout[i] = prefs.getString("layout_" + i, null);
            }
            panel.importLayout(layout);
        }
    }

    private void showSettingsDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF161D24);
        bg.setCornerRadius(dp(18));
        content.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(18);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        addSlider(content, "鼠标灵敏度", 100, prefs.getInt("speed", 40), p -> {
            prefs.edit().putInt("speed", p).apply();
            HidMouseService.setSpeedLevel(p);
        });
        addSlider(content, "滚轮灵敏度", 100, prefs.getInt("wheel_level", 75), p -> {
            prefs.edit().putInt("wheel_level", p).apply();
            panel.setWheelStepDp(12f + (100 - p) * 0.88f);
        });

        CheckBox cbInvert = new CheckBox(this);
        cbInvert.setText("陀螺仪反向（倒置手机）");
        cbInvert.setTextSize(14);
        cbInvert.setTextColor(COLOR_TEXT);
        cbInvert.setPadding(0, dp(10), 0, 0);
        cbInvert.setChecked(prefs.getBoolean("inverted", false));
        content.addView(cbInvert);

        Button editBtn = styledButton("编辑按钮布局");
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        elp.topMargin = dp(14);
        content.addView(editBtn, elp);

        Button done = styledButton("完成");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        dlp.topMargin = dp(16);
        content.addView(done, dlp);

        scroll.addView(content);
        dialog.setContentView(scroll);

        cbInvert.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("inverted", checked).apply();
            HidMouseService.setInverted(checked);
        });
        editBtn.setOnClickListener(v -> {
            dialog.dismiss();
            enterEditMode();
        });
        done.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0x00000000));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addSlider(LinearLayout parent, String label, int max, int value, IntConsumer onChange) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(COLOR_TEXT);
        tv.setPadding(0, dp(14), 0, 0);
        parent.addView(tv);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(value);
        parent.addView(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                onChange.accept(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void requestPermissionsIfNeeded() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (perms.isEmpty()) {
            startHidService();
        } else {
            requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setText("缺少蓝牙权限");
            tvStatus.setTextColor(COLOR_RED);
            tvHint.setText("请在系统设置中允许本应用使用“附近的设备/蓝牙”，然后重新打开应用");
            return;
        }
        startHidService();
    }

    private void startHidService() {
        try {
            Intent i = new Intent(this, HidMouseService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("启动服务失败：" + e.getMessage());
        }
    }

    private void requestDiscoverable() {
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(i);
        } catch (Exception e) {
            toast("无法开启可发现模式：" + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HidMouseService.setListener(this);
        handler.post(tiltPoller);
    }

    @Override
    protected void onPause() {
        HidMouseService.setListener(null);
        handler.removeCallbacks(tiltPoller);
        super.onPause();
    }

    @Override
    public void onStatusChanged(String status, boolean connected) {
        runOnUiThread(() -> {
            tvStatus.setText(status);
            tvStatus.setTextColor(connected ? COLOR_GREEN : COLOR_TEXT);
            if (editing) return;
            if (connected) {
                tvHint.setText("倾斜手机移动指针；按钮控制左/右/中键、滚轮、侧键");
            } else if (status.contains("等待电脑连接")) {
                tvHint.setText("电脑操作：设置 → 蓝牙和其他设备 → 添加设备 → 蓝牙 → 选择本机\n"
                        + "若之前配对过，请先在电脑上删除旧配对再重新配对");
            }
        });
    }

    @Override
    public void onToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onButtonDown(int mask) {
        HidMouseService s = HidMouseService.get();
        if (s != null) s.pressButtons(mask);
    }

    @Override
    public void onButtonUp(int mask) {
        HidMouseService s = HidMouseService.get();
        if (s != null) s.releaseButtons(mask);
    }

    @Override
    public void onWheel(int delta) {
        HidMouseService s = HidMouseService.get();
        if (s != null) s.wheel(delta);
    }

    private Button styledButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(COLOR_TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BTN);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COLOR_BORDER);
        b.setBackground(bg);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
