package io.github.lianxia233.privbroker;

import android.os.Build;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * ROM 差异判定。
 *
 * 目前只用在一处：MIUI / HyperOS 的「通知样式」不是 Android 原生样式时，
 * 通知上的内联回复（RemoteInput）不会渲染，用户看不到输入框，也就无法从通知栏
 * 输入无线调试配对码。这不是权限问题，改开关也修不好，只能在 UI 上说明清楚，
 * 并保证应用内输码这条路径始终可用。
 */
final class RomCompat {

    private RomCompat() {
    }

    /** MIUI 或 HyperOS（两者通知样式机制一致）。 */
    static boolean isMiuiLike() {
        String brand = lower(Build.MANUFACTURER);
        boolean xiaomiBrand = brand.contains("xiaomi")
                || brand.contains("redmi")
                || brand.contains("poco");
        if (!xiaomiBrand) {
            return false;
        }
        String prop = lower(prop("ro.miui.ui.version.name"));
        if (!prop.isEmpty()) {
            return true;
        }
        prop = lower(prop("ro.mi.os.version.name"));
        if (!prop.isEmpty()) {
            return true;
        }
        String fingerprint = lower(Build.DISPLAY + " " + Build.VERSION.INCREMENTAL
                + " " + Build.PRODUCT);
        return fingerprint.contains("miui") || fingerprint.contains("hyperos");
    }

    /** 通知样式是否为 MIUI 自绘样式（此时通知内联回复不可用）。 */
    static boolean miuiNotificationStyleLikely() {
        return isMiuiLike();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String prop(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v instanceof String ? (String) v : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
