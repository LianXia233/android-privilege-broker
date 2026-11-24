package io.github.lianxia233.privbroker;

import android.content.Context;

/**
 * 界面偏好的本地存储（与 Rust 核心的业务配置分开）。
 *
 * <p>主题模式、页面动画、导航栏形态、上次停留的标签页都属于纯展示偏好，
 * 不需要进 Rust 配置模型；存在这里可以让设置页与壳层共享同一份值。</p>
 */
final class UiPrefs {
    private static final String PREFS = "privilege_broker_ui";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_TAB_ANIM = "tab_animation";
    private static final String KEY_DOCK = "dock_mode";
    private static final String KEY_LAST_TAB = "last_tab";

    private UiPrefs() {
    }

    static String themeMode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, "system");
    }

    static void setThemeMode(Context c, String mode) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME, mode).apply();
    }

    static int tabAnimation(Context c) {
        String v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAB_ANIM, "slide");
        return "fade".equals(v) ? PageSwitcher.FADE : PageSwitcher.SLIDE;
    }

    static void setTabAnimation(Context c, int mode) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TAB_ANIM, mode == PageSwitcher.FADE ? "fade" : "slide")
                .apply();
    }

    static int dockMode(Context c) {
        String v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DOCK, "floating");
        return "docked".equals(v) ? NavigationDock.DOCKED : NavigationDock.FLOATING;
    }

    static void setDockMode(Context c, int mode) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DOCK, mode == NavigationDock.DOCKED ? "docked" : "floating")
                .apply();
    }

    static int lastTab(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST_TAB, 0);
    }

    static void setLastTab(Context c, int index) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LAST_TAB, index).apply();
    }
}
