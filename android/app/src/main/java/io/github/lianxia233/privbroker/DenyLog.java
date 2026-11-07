package io.github.lianxia233.privbroker;

import android.content.Context;

import org.json.JSONObject;

/** 授权被拒绝时的统一记录 + 通知，入口与授权页共用。 */
final class DenyLog {
    private DenyLog() {
    }

    static void record(Context app, boolean uninstall, String label,
                       String caller, String callerLabel) {
        record(app, uninstall, label, caller, callerLabel, "denied");
    }

    /**
     * @param kind {@code "denied"} 用户显式拒绝；{@code "ask"} 未授权调用被挡在门外，
     *             已在通知栏给出「待授权」入口，这里不再重复发拒绝通知。
     */
    static void record(Context app, boolean uninstall, String label,
                       String caller, String callerLabel, String kind) {
        final boolean ask = "ask".equals(kind);
        App.executor().execute(() -> {
            try {
                JSONObject e = new JSONObject()
                        .put("op", uninstall ? "uninstall" : "install")
                        .put("label", label)
                        .put("appName", label)
                        .put("source", "silent")
                        .put("caller", caller)
                        .put("ok", false)
                        .put("code", ask ? "CALLER_PENDING" : "CALLER_DENIED")
                        .put("detail", ask
                                ? "调用方未获静默安装授权，已转为通知栏待授权"
                                : "调用方未获静默安装授权");
                RustCore.dispatch("logRecord", e.toString());
            } catch (Throwable ignored) {
            }
        });
        if (!ask) {
            Notifier.denied(app, label, callerLabel);
        }
    }
}
