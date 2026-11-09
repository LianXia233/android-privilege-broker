package io.github.lianxia233.privbroker;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 进程级静默安装 / 卸载任务。
 *
 * 刻意不持有 Activity 引用：入口 Activity 在授权结束后立刻 finish，
 * 屏幕上只剩通知与左上角 40dp 小指示器，不存在全屏透明遮罩。
 * 任务串行执行，避免多个安装互相覆盖进度、通知与悬浮窗。
 */
final class SilentTask implements Runnable {
    private static final Object SERIAL = new Object();

    private final Context app;
    private final boolean uninstall;
    private final List<String> uris;
    /** 调用方给的原始 URI：仅用于「安装成功后删除安装包」，不参与写入。 */
    private final List<String> sources;
    private final String targetPackage;
    private final String caller;
    private volatile String label;
    private boolean floating = true;
    private boolean fallback = true;
    /** 默认不自动弹系统安装器界面，避免遮挡前台应用（见 model.rs 注释）。 */
    private boolean autoOpen = false;
    private boolean deleteAfter = false;
    private boolean allowDowngrade = false;

    private SilentTask(Context app, boolean uninstall, List<String> uris, List<String> sources,
                       String targetPackage, String caller, String label) {
        this.app = app;
        this.uninstall = uninstall;
        this.uris = uris;
        this.sources = sources;
        this.targetPackage = targetPackage;
        this.caller = caller;
        this.label = label == null || label.trim().isEmpty() ? "安装包" : label.trim();
    }

    static SilentTask install(Context c, List<String> uris, List<String> sources,
                              String caller, String label) {
        return new SilentTask(c.getApplicationContext(), false, uris, sources, null, caller, label);
    }

    static SilentTask uninstall(Context c, String pkg, String caller) {
        return new SilentTask(c.getApplicationContext(), true, new java.util.ArrayList<>(),
                new java.util.ArrayList<>(), pkg, caller, pkg);
    }

    @Override
    public void run() {
        synchronized (SERIAL) {
            readConfig();
            // 幂等兜底：TaskLauncher 已调用过 show；这里是后台任务的再次确认。
            // 无论有无悬浮窗权限都不会启动 Activity，屏幕始终只有系统悬浮小窗。
            FloatingIndicator.show(app, floating);
            snapshotFocusWindow();
            try {
                if (uninstall) {
                    runUninstall();
                } else {
                    runInstall();
                }
            } finally {
                BrokerBridge.setProgressListener(null);
                FloatingIndicator.dismiss();
            }
        }
    }

    private void readConfig() {
        try {
            JSONObject r = call("status", "{}");
            JSONObject d = r.optJSONObject("data");
            JSONObject cfg = d == null ? null : d.optJSONObject("config");
            if (cfg != null) {
                floating = cfg.optBoolean("floatingIndicator", true);
                fallback = cfg.optBoolean("fallbackToSystemInstaller", true);
                autoOpen = cfg.optBoolean("autoOpenSystemInstaller", false);
                deleteAfter = cfg.optBoolean("deleteApkAfterInstall", false);
                allowDowngrade = cfg.optBoolean("allowDowngrade", false);
            }
        } catch (Throwable ignored) {
            // 配置读取失败时保持默认：显示指示器、只发降级通知，绝不自动弹界面
        }
    }

    /**
     * 把安装开始时的焦点窗口写进 logcat（tag {@code SilentEntry}）。
     *
     * 纯诊断用途：若实机仍出现「遮罩 / 屏幕点不动」，`adb logcat -s SilentEntry`
     * 会直接给出当时 mCurrentFocus / mFocusedApp 的窗口属主，无需再靠猜。
     * 不写安装日志、不发通知，对用户完全无感。
     */
    private void snapshotFocusWindow() {
        App.executor().execute(() -> {
            try {
                JSONArray arr = new JSONArray().put("sh").put("-c").put(
                        "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -4");
                String resp = BrokerBridge.call("exec",
                        new JSONObject().put("argv", arr).toString());
                if (resp == null) {
                    return;
                }
                JSONObject o = new JSONObject(resp);
                String out = o.optString("stdout", "").trim();
                if (!out.isEmpty()) {
                    Log.i("SilentEntry", "focus@install: " + out.replace('\n', ' '));
                }
            } catch (Throwable ignored) {
                // 诊断失败不影响安装
            }
        });
    }

    // ---------------------------------------------------------------- 安装

    private void runInstall() {
        if (uris == null || uris.isEmpty()) {
            failPrepare("未收到安装包");
            return;
        }
        Notifier.start(app, label, false);
        attachProgress();
        try {
            JSONObject opts = defaultOptions();
            JSONObject prepare = new JSONObject()
                    .put("uris", new JSONArray(uris))
                    .put("options", opts);
            JSONObject r = call("prepareInstall", prepare.toString());
            if (!r.optBoolean("ok") && isBrokerLinkFailure(r) && reviveBroker()) {
                // 特权代理被系统回收（HyperOS 清理/幻影进程等）：自动重拉后重试，
                // 用户无需感知，更不用重新输码。
                r = call("prepareInstall", prepare.toString());
            }
            if (!r.optBoolean("ok")) {
                failPrepare(errorText(r));
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject plan = d == null ? null : d.optJSONObject("plan");
            JSONObject summary = d == null ? null : d.optJSONObject("summary");
            if (plan == null) {
                failPrepare("未能生成安装计划");
                return;
            }

            // 解析真实应用名（中文优先）：已安装应用名 > AXML label > 清洗后的文件名
            String resolved = AppNames.resolveInstallLabel(app, uris.get(0), summary);
            if (resolved != null && !resolved.isEmpty()) {
                label = resolved;
            }
            Notifier.start(app, label, false);

            String pkg = summary == null ? "" : summary.optString("package", "");
            JSONObject commit = new JSONObject()
                    .put("plan", plan)
                    .put("options", opts)
                    .put("context", context());
            JSONObject cr = call("commitInstall", commit.toString());
            if (!cr.optBoolean("ok") && isBrokerLinkFailure(cr) && reviveBroker()) {
                cr = call("commitInstall", commit.toString());
            }
            if (!cr.optBoolean("ok")) {
                finishFailure(AppNames.errorText(app, errorCodeOf(cr), errorText(cr)));
                return;
            }
            JSONObject outcome = outcomeOf(cr);
            boolean ok = outcome != null && outcome.optBoolean("ok");
            String raw = outcome == null ? "" : outcome.optString("raw", "");
            String code = outcome == null ? "" : outcome.optString("code", "");
            if (ok) {
                afterSuccess();
                Notifier.finish(app, label, false, true, "", safePkg(pkg));
            } else {
                finishFailure(AppNames.errorText(app, code, raw));
            }
        } catch (Throwable t) {
            failPrepare(String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- 卸载

    private void runUninstall() {
        String pkg = targetPackage == null ? "" : targetPackage.trim();
        if (pkg.isEmpty()) {
            failPrepare("未指定包名");
            return;
        }
        String installed = AppNames.labelForPackage(app, pkg);
        label = installed == null ? pkg : installed;
        // 卸载不可重放：先记下发起前的状态，失败后用它区分「命令没执行」与
        // 「命令执行了但结果没回来」。
        boolean wasInstalled = AppNames.isInstalled(app, pkg);
        Notifier.start(app, label, true);
        attachProgress();
        try {
            JSONObject a = new JSONObject()
                    .put("package", pkg)
                    .put("options", new JSONObject().put("keepData", false))
                    .put("context", context());
            JSONObject r = call("uninstall", a.toString());
            if (!r.optBoolean("ok") && isBrokerLinkFailure(r)) {
                // 链路类失败有两种可能：命令还没执行，或命令执行完但结果没回来。
                // 后者若照样重试，第二次会对已卸载的包报「未安装」，把成功掩盖成失败。
                if (wasInstalled && !AppNames.isInstalled(app, pkg)) {
                    record("UNINSTALL_CONFIRMED", "代理未回传结果，但目标包已不在安装状态，按已完成处理");
                    Notifier.finish(app, label, true, true, "", "");
                    return;
                }
                if (reviveBroker()) {
                    r = call("uninstall", a.toString());
                }
            }
            if (!r.optBoolean("ok")) {
                finishFailure(AppNames.errorText(app, errorCodeOf(r), errorText(r)));
                return;
            }
            JSONObject outcome = outcomeOf(r);
            boolean ok = outcome != null && outcome.optBoolean("ok");
            String raw = outcome == null ? "" : outcome.optString("raw", "");
            String code = outcome == null ? "" : outcome.optString("code", "");
            if (ok) {
                Notifier.finish(app, label, true, true, "", "");
            } else {
                finishFailure(AppNames.errorText(app, code, raw));
            }
        } catch (Throwable t) {
            failPrepare(String.valueOf(t));
        }
    }

    // ---------------------------------------------------------------- 收尾

    private void attachProgress() {
        BrokerBridge.setProgressListener((stage, index, total) -> {
            Notifier.progress(app, label, uninstall, index, total, stage);
            FloatingIndicator.progress(total > 0 ? Math.min(100, index * 100 / total) : -1);
        });
    }

    private void failPrepare(String msg) {
        record("PREPARE_FAILED", msg);
        finishFailure(AppNames.errorText(app, "PREPARE_FAILED", msg));
    }

    private void finishFailure(String zh) {
        Notifier.finish(app, label, uninstall, false, zh, "");
        if (uninstall || !fallback || uris == null || uris.isEmpty()) {
            return;
        }
        Intent i = buildSystemInstallIntent(uris.get(0));
        if (i == null) {
            // 拿不到可以交给系统安装器的 content://：安装包根本没读进来
            // （例如调用方给的是别的应用目录下的 file://），使命中安装器也
            // 无济于事。仍然发一条失败通知，只是不带「点击改用系统安装器」
            // 这个做不到的入口，改成告诉用户怎么重来。
            Notifier.fallback(app, label, zh
                    + "\n未能读取到安装包内容，无法改用系统安装器；"
                    + "请回应用首页点「选择安装包并安装」重新选择该文件。", null);
            recordFallback(false, false);
            return;
        }
        // 默认只发一条可点击的通知：用户点通知才进入系统安装器。
        // 过去这里无条件 app.startActivity(i)，会弹出一个可聚焦窗口，
        // 染黑状态栏/导航栏、吞掉触摸且不按返回不消失 —— 这正是
        // 实机反馈「屏幕卡住、需要按返回」的持续窗口源。
        // 交互语义保持不变（不主动拉界面），只把后续动作写清楚：
        // 为什么降级、点通知做什么、代理异常时回哪里修。
        String guide = autoOpen
                ? "\n已按设置自动打开系统安装器，可直接继续安装；若窗口未出现，"
                        + "请回应用首页点「查看启用步骤」检查特权代理状态。"
                : "\n点击此通知可改用系统安装器完成本次安装；若提示特权代理仍未就绪，"
                        + "请回应用首页点「查看启用步骤」重新激活后重试。";
        Notifier.fallback(app, label, zh + guide, i);
        recordFallback(autoOpen, true);
        if (autoOpen) {
            try {
                app.startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把降级方式写入日志，便于实机定位「到底是哪个窗口弹出来了」。
     *
     * @param opened       是否已自动拉起系统安装器（设置中开启）
     * @param canHandOver  通知上是否真的挂了「改用系统安装器」的点击入口
     */
    private void recordFallback(boolean opened, boolean canHandOver) {
        try {
            JSONObject e = new JSONObject()
                    .put("op", "install")
                    .put("label", label)
                    .put("appName", label)
                    .put("source", "silent")
                    .put("ok", false)
                    .put("code", "FALLBACK")
                    .put("detail", !canHandOver
                            ? "仅通知，未自动拉起任何界面；安装包未能读取，无法改用系统安装器，需重新选择文件"
                            : opened
                                    ? "已自动打开系统安装器（设置中开启）；窗口未出现时回应用点「查看启用步骤」检查代理"
                                    : "仅通知，未自动拉起任何界面；点击通知可改用系统安装器，"
                                            + "代理异常时回应用点「查看启用步骤」重新激活后重试");
            if (caller != null && !caller.isEmpty()) {
                e.put("caller", caller);
            }
            call("logRecord", e.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 降级：显式指定系统安装器组件，避免再次匹配到本应用的 INSTALL_PACKAGE 过滤器。
     * uriString 是自家 StagedProvider 的 content:// URI，通过临时 grant 安全交给
     * 系统安装器，既不依赖已失效的调用方授权，也不会暴露 file:// 触发异常。
     */
    private Intent buildSystemInstallIntent(String uriString) {
        try {
            Uri u = Uri.parse(uriString);
            // 只有自家 StagedProvider 的 content:// 能连同读授权一起交给别的应用。
            // file://（调用方直接给了路径、且暂存复制失败时 StagedFiles 会把原始
            // URI 原样带回）一旦进入交给外部的 Intent，framework 会抛
            // FileUriExposedException —— 通知的 PendingIntent 由系统代发，这个异常
            // 在 Notifier 里才抛出，直接把进程崩掉，失败降级反而变成崩溃。
            if (!"content".equals(u.getScheme())) {
                return null;
            }
            PackageManager pm = app.getPackageManager();
            String self = app.getPackageName();
            Intent base = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            base.setDataAndType(u, "application/vnd.android.package-archive");
            base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ComponentName target = resolveExternal(pm, base, self);
            if (target == null) {
                base.setAction(Intent.ACTION_VIEW);
                target = resolveExternal(pm, base, self);
            }
            Intent out;
            if (target != null) {
                out = new Intent(base);
                out.setComponent(target);
            } else {
                out = Intent.createChooser(base, null);
            }
            out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // 通知的 PendingIntent 由系统代发，部分 Android 版本只按 ClipData
            // 传递 URI 临时授权；同时设置 data 与 ClipData 才能确保系统安装器
            // 能读到自家 StagedProvider 的 content://。
            out.setClipData(ClipData.newUri(app.getContentResolver(), "安装包", u));
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ComponentName resolveExternal(PackageManager pm, Intent i, String self) {
        try {
            List<ResolveInfo> rs = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (rs == null) {
                return null;
            }
            for (ResolveInfo r : rs) {
                if (r.activityInfo == null || self.equals(r.activityInfo.packageName)) {
                    continue;
                }
                return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 准备阶段 / 异常路径补记日志，保证后台链路任何结果都留痕。 */
    private void record(String code, String detail) {
        try {
            JSONObject e = new JSONObject()
                    .put("op", uninstall ? "uninstall" : "install")
                    .put("label", label)
                    .put("appName", label)
                    .put("source", "silent")
                    .put("ok", false)
                    .put("code", code)
                    .put("detail", detail == null ? "" : detail);
            if (caller != null && !caller.isEmpty()) {
                e.put("caller", caller);
            }
            call("logRecord", e.toString());
        } catch (Throwable ignored) {
        }
    }

    private JSONObject context() throws Exception {
        JSONObject c = new JSONObject()
                .put("label", label)
                .put("appName", label)
                .put("source", "silent");
        if (caller != null && !caller.isEmpty()) {
            c.put("caller", caller);
        }
        return c;
    }

    private JSONObject defaultOptions() throws Exception {
        return new JSONObject()
                .put("replace", true)
                .put("grantRuntimePermissions", true)
                .put("allowDowngrade", allowDowngrade)
                .put("deleteSourceAfter", false)
                .put("skipConfirm", true);
    }

    /**
     * 安装成功后的收尾。
     *
     * 1. 永远清理本应用缓存里的私有副本（staged），装完就没用了；
     * 2. 「成功后删除安装包」开启时，再删调用方给的原始文件。
     *
     * 原始 URI 通常只有读权限，ContentResolver.delete 会被拒；因此
     * {@link BrokerBridge} 的 deleteSource 在失败后会解析真实路径并用
     * shell 身份 rm —— 这才是用户开关真正生效的关键。
     */
    private void afterSuccess() {
        try {
            BrokerBridge.call("cleanupStaged", "{}");
        } catch (Throwable ignored) {
        }
        if (!deleteAfter) {
            return;
        }
        List<String> targets = new java.util.ArrayList<>();
        if (sources != null) {
            targets.addAll(sources);
        }
        if (targets.isEmpty() && uris != null) {
            targets.addAll(uris);
        }
        int deleted = 0;
        int failed = 0;
        for (String u : targets) {
            boolean ok = false;
            try {
                String resp = BrokerBridge.call("deleteSource",
                        new JSONObject().put("uri", u).toString());
                JSONObject o = resp == null ? null : new JSONObject(resp);
                ok = o != null && o.optBoolean("deleted", false);
            } catch (Throwable ignored) {
            }
            if (ok) {
                deleted++;
            } else {
                failed++;
            }
        }
        recordDelete(deleted, failed);
    }

    /** 无论删除成败都写一条日志：这是用户可验证的唯一凭据。 */
    private void recordDelete(int deleted, int failed) {
        try {
            JSONObject e = new JSONObject()
                    .put("op", "install")
                    .put("label", label)
                    .put("appName", label)
                    .put("source", "silent")
                    .put("ok", failed == 0)
                    .put("code", failed == 0 ? "DELETE_OK" : "DELETE_PARTIAL")
                    .put("detail", failed == 0
                            ? "安装成功，已删除安装包 " + deleted + " 个"
                            : "安装成功，已删除 " + deleted + " 个，另有 " + failed
                                    + " 个无法删除（只读目录或权限受限）");
            if (caller != null && !caller.isEmpty()) {
                e.put("caller", caller);
            }
            call("logRecord", e.toString());
        } catch (Throwable ignored) {
        }
    }

    private JSONObject outcomeOf(JSONObject r) {
        JSONObject d = r.optJSONObject("data");
        JSONObject result = d == null ? null : d.optJSONObject("result");
        return result == null ? null : result.optJSONObject("outcome");
    }

    private JSONObject call(String method, String args) {
        try {
            return new JSONObject(RustCore.dispatch(method, args));
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false)
                        .put("error", new JSONObject().put("message", String.valueOf(e)));
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private String errorText(JSONObject r) {
        JSONObject e = r.optJSONObject("error");
        return e == null ? "" : e.optString("message", "");
    }

    private String errorCodeOf(JSONObject r) {
        JSONObject e = r.optJSONObject("error");
        if (e == null) {
            return "";
        }
        String k = e.optString("kind", "");
        return k.isEmpty() ? e.optString("message", "") : k;
    }

    /** 是否为「特权代理不在运行」类失败：只有这一类失败值得自动重拉重试。 */
    private boolean isBrokerNotReady(JSONObject r) {
        return "BROKER_NOT_READY".equals(errorCodeOf(r));
    }

    /**
     * 是否为可自愈的代理链路失败。
     *
     * <p>除了「代理确实没跑」，还包括端点可连但握手/会话被拒的情况：
     * 令牌不一致、挑战失效、连接在写包途中被断开。这些失败同样只需要
     * 重新拉起代理（或换一条新连接）就能恢复，不应直接降级。</p>
     */
    private boolean isBrokerLinkFailure(JSONObject r) {
        if (isBrokerNotReady(r)) {
            return true;
        }
        String kind = errorCodeOf(r);
        if (!"BRIDGE".equals(kind) && !"WRITE".equals(kind) && !"SESSION".equals(kind)) {
            return false;
        }
        String msg = errorText(r);
        return msg.contains("无法连接特权代理") || msg.contains("握手失败")
                || msg.contains("连接已关闭") || msg.contains("Broken pipe")
                || msg.contains("UNAUTHENTICATED") || msg.contains("SESSION_EXPIRED")
                || msg.contains("REPLAY_DETECTED");
    }

    /**
     * 自动重拉特权代理并写日志。阻塞（最长约 20 秒），仅限 SilentTask
     * 的后台线程调用；恢复期间通知保持「进行中」，失败才发失败通知。
     */
    private boolean reviveBroker() {
        record("REVIVE", "特权代理链路不可用（未运行或握手被拒），正在自动重新拉起（无需重新配对）");
        String err = BrokerReviver.revive(app);
        if (err == null) {
            record("REVIVED", "自动恢复成功，重试本次操作");
            return true;
        }
        record("REVIVE_FAILED", err);
        return false;
    }

    private String safePkg(String p) {
        return p == null || "null".equals(p) ? "" : p;
    }
}
