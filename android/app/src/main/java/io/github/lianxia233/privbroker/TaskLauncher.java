package io.github.lianxia233.privbroker;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一次静默安装/卸载交给进程级后台线程。
 *
 * 指示器只使用系统悬浮窗（TYPE_APPLICATION_OVERLAY），不启动任何 Activity；
 * 没有权限时由 FloatingIndicator 经自主通道自动授权，失败则静默放弃，
 * 因此不存在「后台启动 Activity 被限制」或「把本应用切到前台」的问题。
 *
 * 入参用 {@link Context} 而不是 Activity：授权决定可能来自系统悬浮窗
 * （{@link AuthPrompt}），那条路径上没有、也不该有 Activity。
 */
final class TaskLauncher {
    private TaskLauncher() {
    }

    static void launch(Context c, boolean uninstall, List<String> uris,
                       String uninstallPkg, String caller, String label) {
        launch(c, uninstall, uris, null, uninstallPkg, caller, label);
    }

    /**
     * @param sources 原始（调用方给的）URI，用于「安装成功后删除安装包」；
     *                为空时只清理本应用缓存里的私有副本。
     */
    static void launch(Context c, boolean uninstall, List<String> uris, List<String> sources,
                       String uninstallPkg, String caller, String label) {
        Context app = c.getApplicationContext();
        // 指示器不在这里显示：本方法由入口 Activity 的主线程调用，读配置要向
        // 特权代理发起 TCP 连接，在 targetSdk 26+ 上会被判为
        // NetworkOnMainThreadException 而恒定失败并回落成「开启」，用户关掉
        // 「后台指示」也会照样显示。改由 SilentTask.run() 在工作线程里按真实
        // 配置显示（show 幂等）。
        List<String> snapshot = new ArrayList<>();
        if (uris != null) {
            snapshot.addAll(uris);
        }
        List<String> originals = new ArrayList<>();
        if (sources != null) {
            originals.addAll(sources);
        }
        App.executor().execute(uninstall
                ? SilentTask.uninstall(app, uninstallPkg, caller)
                : SilentTask.install(app, snapshot, originals, caller, label));
    }
}
