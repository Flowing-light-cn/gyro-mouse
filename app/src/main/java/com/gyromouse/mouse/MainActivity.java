package com.gyromouse.mouse;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
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
    private static final int COLOR_ACCENT = 0xFF4FC3F7;
    private static final int COLOR_BTN_SELECTED = 0xFF24313B;

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

    /** 应用内语言：让本 Activity 的所有 Context 资源使用所选语言。 */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(I18n.wrap(newBase));
    }

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

        Button btnSettings = styledButton(getString(R.string.action_settings));
        topRow.addView(btnSettings, new LinearLayout.LayoutParams(dp(72), dp(40)));

        tvStatus = new TextView(this);
        tvStatus.setTextSize(17);
        tvStatus.setTextColor(COLOR_TEXT);
        tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvStatus.setSingleLine(true);
        tvStatus.setEllipsize(TextUtils.TruncateAt.END);
        tvStatus.setPadding(dp(12), 0, 0, 0);
        tvStatus.setText(R.string.status_starting);
        topRow.addView(tvStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvHint = new TextView(this);
        tvHint.setTextSize(12);
        tvHint.setTextColor(COLOR_MUTED);
        tvHint.setPadding(0, dp(6), 0, dp(10));
        tvHint.setText(R.string.hint_grant_bluetooth);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnDiscover = styledButton(getString(R.string.action_discoverable));
        btnCal = styledButton(getString(R.string.action_calibrate));
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
                toast(getString(R.string.toast_calibrated));
            }
        });
    }

    private void enterEditMode() {
        editing = true;
        panel.setEditMode(true);
        tvHint.setText(R.string.hint_edit_layout);
        btnDiscover.setText(R.string.action_reset_layout);
        btnCal.setText(R.string.action_finish_layout);
    }

    private void exitEditMode() {
        editing = false;
        panel.setEditMode(false);
        tvHint.setText(R.string.hint_ready);
        btnDiscover.setText(R.string.action_discoverable);
        btnCal.setText(R.string.action_calibrate);
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
        toast(getString(R.string.toast_layout_saved));
    }

    private void resetCustomLayout() {
        panel.resetLayout();
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < ControlPanelView.ITEM_COUNT; i++) {
            e.remove("layout_" + i);
        }
        e.putBoolean("has_custom_layout", false);
        e.apply();
        toast(getString(R.string.toast_layout_reset));
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
        title.setText(R.string.dialog_settings_title);
        title.setTextSize(18);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        addLanguageSelector(content, dialog);

        addSlider(content, getString(R.string.label_speed), 100, prefs.getInt("speed", 40), p -> {
            prefs.edit().putInt("speed", p).apply();
            HidMouseService.setSpeedLevel(p);
        });
        addSlider(content, getString(R.string.label_wheel_speed), 100, prefs.getInt("wheel_level", 75), p -> {
            prefs.edit().putInt("wheel_level", p).apply();
            panel.setWheelStepDp(12f + (100 - p) * 0.88f);
        });

        CheckBox cbInvert = new CheckBox(this);
        cbInvert.setText(R.string.label_invert);
        cbInvert.setTextSize(14);
        cbInvert.setTextColor(COLOR_TEXT);
        cbInvert.setPadding(0, dp(10), 0, 0);
        cbInvert.setChecked(prefs.getBoolean("inverted", false));
        content.addView(cbInvert);

        Button editBtn = styledButton(getString(R.string.action_edit_layout));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        elp.topMargin = dp(14);
        content.addView(editBtn, elp);

        Button done = styledButton(getString(R.string.action_done));
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

    /** 语言选择：跟随系统 / 中文 / English，点选后立即重建界面生效。 */
    private void addLanguageSelector(LinearLayout parent, Dialog dialog) {
        TextView tv = new TextView(this);
        tv.setText(R.string.label_language);
        tv.setTextSize(14);
        tv.setTextColor(COLOR_TEXT);
        tv.setPadding(0, dp(14), 0, 0);
        parent.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        rlp.topMargin = dp(8);
        parent.addView(row, rlp);

        String current = I18n.getMode(this);
        String[] modes = {I18n.MODE_SYSTEM, I18n.MODE_ZH, I18n.MODE_EN};
        int[] labels = {R.string.language_system, R.string.language_zh, R.string.language_en};
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            Button b = styledButton(getString(labels[i]));
            b.setTextSize(13);
            boolean selected = mode.equals(current);
            styleLanguageButton(b, selected);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) {
                lp.leftMargin = dp(8);
            }
            row.addView(b, lp);
            b.setOnClickListener(v -> switchLanguage(mode, dialog));
        }
    }

    private void styleLanguageButton(Button b, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? COLOR_BTN_SELECTED : COLOR_BTN);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(selected ? 1.5f : 1f), selected ? COLOR_ACCENT : COLOR_BORDER);
        b.setBackground(bg);
        b.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT);
    }

    /** 切换语言：保存设置 → 刷新常驻通知 → 重建界面（当前界面回到主界面）。 */
    private void switchLanguage(String mode, Dialog dialog) {
        if (mode.equals(I18n.getMode(this))) {
            return;
        }
        dialog.dismiss();
        I18n.setMode(this, mode);
        HidMouseService.refreshNotification();
        recreate();
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
            tvStatus.setText(R.string.status_missing_bluetooth_permission);
            tvStatus.setTextColor(COLOR_RED);
            tvHint.setText(R.string.hint_missing_bluetooth_permission);
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
            toast(getString(R.string.toast_start_service_failed, String.valueOf(e.getMessage())));
        }
    }

    private void requestDiscoverable() {
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(i);
        } catch (Exception e) {
            toast(getString(R.string.toast_discover_failed, String.valueOf(e.getMessage())));
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
    public void onStatusChanged(int statusRes, String arg, boolean connected) {
        runOnUiThread(() -> {
            tvStatus.setText(arg == null ? getString(statusRes) : getString(statusRes, arg));
            tvStatus.setTextColor(connected ? COLOR_GREEN : COLOR_TEXT);
            if (editing) return;
            if (connected) {
                tvHint.setText(R.string.hint_ready);
            } else if (statusRes == R.string.st_registered) {
                tvHint.setText(R.string.hint_pairing);
            }
        });
    }

    @Override
    public void onToast(int messageRes) {
        runOnUiThread(() -> Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show());
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
