package io.github.lianxia233.privbroker;

import android.content.Context;
import android.content.pm.PackageInfo;

/** 版本信息读取（关于页展示用）。 */
final class BuildInfo {
    private BuildInfo() {
    }

    static String versionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            String name = pi.versionName == null ? "?" : pi.versionName;
            long code = pi.getLongVersionCode();
            return name + " (" + code + ")";
        } catch (Throwable t) {
            return "?";
        }
    }
}
