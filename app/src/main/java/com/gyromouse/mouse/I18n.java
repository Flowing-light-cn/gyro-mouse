package com.gyromouse.mouse;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 应用内语言设置：中文 / English / 跟随系统。
 *
 * <p>选择结果保存在 SharedPreferences；Activity 与 Service 在 attachBaseContext 里用
 * {@link #wrap(Context)} 换一个带目标语言的 Context，因此 {@code getString()} 以及所有
 * 由代码创建的文字（界面、弹窗、Toast、常驻通知）都会跟随所选语言。
 * 语言在系统里不落盘、只影响本应用，切换时由界面重建生效。</p>
 */
public final class I18n {

    /** 跟随系统语言。 */
    public static final String MODE_SYSTEM = "system";
    /** 简体中文。 */
    public static final String MODE_ZH = "zh";
    /** 英文。 */
    public static final String MODE_EN = "en";

    private static final String PREFS_NAME = "settings";
    private static final String KEY_LANGUAGE = "language";

    private I18n() {
    }

    /** 当前设定的语言模式（system / zh / en）。 */
    public static String getMode(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalize(p.getString(KEY_LANGUAGE, MODE_SYSTEM));
    }

    /** 保存语言模式；界面重建后生效。 */
    public static void setMode(Context context, String mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, normalize(mode))
                .apply();
    }

    /** 只接受已知模式，其它值（含旧版本残留）一律按「跟随系统」处理。 */
    public static String normalize(String mode) {
        if (MODE_ZH.equals(mode) || MODE_EN.equals(mode)) {
            return mode;
        }
        return MODE_SYSTEM;
    }

    /**
     * 返回使用当前所选语言的 Context；「跟随系统」时原样返回 base。
     * 传入 Activity / Service 的 base context，用于覆盖配置中的语言。
     */
    public static Context wrap(Context base) {
        String mode = getMode(base);
        if (MODE_SYSTEM.equals(mode)) {
            return base;
        }
        Locale locale = MODE_ZH.equals(mode) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        LocaleList locales = new LocaleList(locale);
        cfg.setLocales(locales);
        cfg.setLocale(locale);
        cfg.setLayoutDirection(locale);
        return base.createConfigurationContext(cfg);
    }
}
