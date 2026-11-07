package io.github.lianxia233.privbroker;

import android.content.Context;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 应用内卸载的统一执行点：卸载选择器与「手动输入包名」入口共用。
 *
 * 与静默入口的区别：这里来源记作 {@code ui}，不经过调用方授权策略；
 * 结果仍由 Rust 核心写入安装日志，并由 {@link Notifier} 更新通知。
 */
final class Uninstaller {
    private Uninstaller() {
    }

    static void run(Context c, String pkg, boolean keepData) {
        final Context app = c.getApplicationContext();
        String resolved = AppNames.labelForPackage(app, pkg);
        final String label = resolved == null || resolved.trim().isEmpty() ? pkg : resolved.trim();
        try {
            Toast.makeText(app, app.getString(R.string.picker_submitted, label),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
        App.executor().execute(() -> {
            boolean ok = false;
            String raw = "";
            try {
                JSONObject a = new JSONObject()
                        .put("package", pkg)
                        .put("options", new JSONObject().put("keepData", keepData))
                        .put("context", new JSONObject()
                                .put("label", label)
                                .put("appName", label)
                                .put("source", "ui"));
                JSONObject r = new JSONObject(RustCore.dispatch("uninstall", a.toString()));
                if (r.optBoolean("ok")) {
                    JSONObject d = r.optJSONObject("data");
                    JSONObject result = d == null ? null : d.optJSONObject("result");
                    JSONObject outcome = result == null ? null : result.optJSONObject("outcome");
                    ok = outcome != null && outcome.optBoolean("ok");
                    raw = outcome == null ? "" : outcome.optString("raw", "").trim();
                } else {
                    JSONObject e = r.optJSONObject("error");
                    raw = e == null ? app.getString(R.string.error_unknown)
                            : e.optString("message", app.getString(R.string.error_unknown));
                }
            } catch (Throwable t) {
                raw = String.valueOf(t);
            }
            Notifier.finish(app, label, true, ok, raw, "");
        });
    }
}
