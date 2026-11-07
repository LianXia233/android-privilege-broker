package io.github.lianxia233.privbroker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 已安装应用列表数据源：设置页「允许静默安装的应用」与「卸载应用」选择器共用。
 *
 * 列表由 Rust 核心经平台桥接读取（QUERY_ALL_PACKAGES），Java 侧只做解析，
 * 是否属于系统应用由 {@code ApplicationInfo.FLAG_SYSTEM} 判定并原样带出。
 * 必须在后台线程调用：内部走 JNI + PackageManager 枚举。
 */
final class AppList {
    /** 列表筛选维度：全部 / 用户应用 / 系统应用。 */
    enum Kind {
        ALL, USER, SYSTEM
    }

    static final class Row {
        final String pkg;
        final String label;
        final boolean system;
        /** 仅设置页使用：该应用是否已被允许发起静默安装。 */
        boolean allowed;

        Row(String pkg, String label, boolean system) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
        }
    }

    private AppList() {
    }

    static List<Row> load() {
        List<Row> rows = new ArrayList<>();
        try {
            JSONObject r = new JSONObject(RustCore.dispatch("installedApps", "{}"));
            JSONObject d = r.optJSONObject("data");
            JSONArray arr = d == null ? null : d.optJSONArray("apps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String pkg = o.optString("package", "");
                    if (pkg.isEmpty()) {
                        continue;
                    }
                    String name = o.optString("label", "");
                    rows.add(new Row(pkg, name.isEmpty() ? pkg : name,
                            o.optBoolean("system", false)));
                }
            }
        } catch (Throwable ignored) {
            // 桥接异常时返回空列表，UI 显示「正在读取/无匹配」，不崩溃
        }
        return rows;
    }

    static boolean matches(Row r, Kind kind) {
        switch (kind) {
            case USER:
                return !r.system;
            case SYSTEM:
                return r.system;
            default:
                return true;
        }
    }
}
