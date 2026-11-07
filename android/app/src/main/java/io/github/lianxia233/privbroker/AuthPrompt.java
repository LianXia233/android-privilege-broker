package io.github.lianxia233.privbroker;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨应用授权卡片（系统悬浮窗）。
 *
 * 目标：外部应用第一次发起静默安装时，用户**不必切回本应用**就能决定允许还是拒绝。
 * 卡片浮在当前前台应用之上，点「允许」后直接续装本次请求。
 *
 * v1.0.4：卡片改由 {@link AuthForegroundService} 短时前台服务承载。入口
 * {@code SilentInstallActivity} 对普通调用会立即 finish，进程一旦降为 cached，
 * 异步申请完 SYSTEM_ALERT_WINDOW 再 addView 往往已经来不及（国产 ROM 上表现为
 * 「弹窗没弹出来」）。前台服务把进程钉在前台优先级，卡片因此能稳定出现。
 *
 * 为什么不用 Activity / Dialog：
 *  - Dialog 在部分 ROM 上会被铺成整屏半透明背板并抢焦点，正是用户反复反馈的
 *    「一层遮罩弹窗、屏幕点不动」；
 *  - 普通 Activity 会把本应用 task 拉到前台，打断用户正在做的事。
 *  - 系统悬浮窗 {@code TYPE_APPLICATION_OVERLAY} 是唯一「浮在别人之上、不切换任务、
 *    不 pause 前台应用」的窗口；配合 WRAP_CONTENT + NOT_FOCUSABLE + NOT_TOUCH_MODAL，
 *    卡片之外的触摸与返回键全部穿透给前台应用，不会挡住别人。
 *
 * 连悬浮窗权限都拿不到时，退化为一条带「允许 / 拒绝」按钮的横幅通知，
 * 用户仍可当场决策，绝不降级成需要切回本应用的普通页面。
 *
 * sources 是调用方给的原始 URI 快照，允许后续「安装成功后删除安装包」
 * 删到原文件；uris 是本应用私有缓存里的 staged 副本，只用于安装。
 */
final class AuthPrompt {
    private static final String TAG = "AuthPrompt";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    /** 无人处理时自动收起，避免卡片长期悬在屏幕上。 */
    private static final long TIMEOUT_MS = 90_000L;

    /** 一次询问的完整快照，可经 Intent extras 重建（服务可能被系统重建）。 */
    static final class Req {
        String label = "安装包";
        String caller = "@unknown";
        String callerLabel = "";
        String uninstallPkg = "";
        boolean uninstall;
        final ArrayList<String> uris = new ArrayList<>();
        final ArrayList<String> sources = new ArrayList<>();
    }

    private static WindowManager wm;
    private static View view;
    private static Runnable timeout;
    private static Req current;

    private AuthPrompt() {
    }

    /**
     * 弹出授权卡片。优先交给前台服务保活；服务启动被 ROM 拒绝时，
     * 退化为直接在当前进程里申请权限并 addView（尽力而为，不保证保活）。
     *
     * @return 恒为 {@code true}：本次询问已交给悬浮卡片或横幅通知，
     *         调用方不需要再发「回本应用重新授权」的旧式通知。
     */
    static boolean show(Context c, String label, String caller, String callerLabel,
                        List<String> uris, List<String> sources,
                        boolean uninstall, String uninstallPkg) {
        Req r = new Req();
        r.label = label;
        r.caller = caller;
        r.callerLabel = callerLabel;
        r.uninstall = uninstall;
        r.uninstallPkg = uninstallPkg;
        if (uris != null) {
            r.uris.addAll(uris);
        }
        if (sources != null) {
            r.sources.addAll(sources);
        }
        final Context app = c.getApplicationContext();
        synchronized (LOCK) {
            current = r;
        }
        try {
            Intent i = AuthForegroundService.showIntent(app, r);
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "foreground service unavailable, attach directly", t);
            attach(app, r);
        }
        return true;
    }

    /** 由前台服务调用：进程已处于前台，此时申请权限并 addView 才可靠。 */
    static void attach(Context app, Req r) {
        synchronized (LOCK) {
            current = r;
        }
        if (FloatingIndicator.canDraw(app)) {
            MAIN.post(() -> add(app, r));
            return;
        }
        FloatingIndicator.ensurePermissionAsync(app, () -> {
            if (FloatingIndicator.canDraw(app)) {
                add(app, r);
            } else {
                // 权限拿不到（特权代理未就绪 / ROM 限制）：横幅通知仍可当场决策
                Notifier.authHeadsUp(app, r);
            }
        });
    }

    /** 主动收起（服务销毁、任务取消）。 */
    static void dismiss() {
        MAIN.post(AuthPrompt::removeNow);
    }

    private static void removeNow() {
        synchronized (LOCK) {
            if (timeout != null) {
                MAIN.removeCallbacks(timeout);
                timeout = null;
            }
            if (view != null && wm != null) {
                try {
                    wm.removeViewImmediate(view);
                } catch (Throwable ignored) {
                }
            }
            view = null;
            wm = null;
        }
    }

    private static void add(Context app, Req r) {
        removeNow();
        View root;
        try {
            root = LayoutInflater.from(app).inflate(R.layout.overlay_auth, null);
        } catch (Throwable t) {
            Log.w(TAG, "inflate failed", t);
            Notifier.authHeadsUp(app, r);
            return;
        }

        String who = r.callerLabel == null || r.callerLabel.trim().isEmpty()
                ? app.getString(R.string.caller_unknown) : r.callerLabel.trim();
        String message = r.uninstall
                ? app.getString(R.string.auth_dialog_uninstall, who)
                : app.getString(R.string.auth_dialog_install, who);
        ((TextView) root.findViewById(R.id.ovMessage)).setText(message);
        ((TextView) root.findViewById(R.id.ovCaller)).setText(
                "@unknown".equals(r.caller) ? app.getString(R.string.caller_unknown) : r.caller);

        CheckBox remember = root.findViewById(R.id.ovRemember);
        if ("@unknown".equals(r.caller)) {
            // 匿名来源不能被永久记住，否则一次允许会放行后续所有匿名调用
            remember.setEnabled(false);
            remember.setChecked(false);
            remember.setText(R.string.auth_remember_anonymous);
        }

        root.findViewById(R.id.btnOvAllow).setOnClickListener(
                v -> decide(app, true, remember.isChecked(), r));
        root.findViewById(R.id.btnOvDeny).setOnClickListener(
                v -> decide(app, false, false, r));

        try {
            WindowManager manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) {
                Notifier.authHeadsUp(app, r);
                return;
            }
            float density = app.getResources().getDisplayMetrics().density;
            int maxWidth = (int) (360 * density);
            int screen = app.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(maxWidth, (int) (screen * 0.88f));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            // 不要 dim：悬浮窗自带半透明背板就是「遮罩」，这里必须全透明
            lp.dimAmount = 0f;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.windowAnimations = 0;
            manager.addView(root, lp);
            synchronized (LOCK) {
                wm = manager;
                view = root;
                current = r;
                timeout = () -> {
                    Log.i(TAG, "auth prompt timeout, dismissed");
                    removeNow();
                    Notifier.cancelAuthHeadsUp(app);
                    AuthForegroundService.stop(app);
                };
                MAIN.postDelayed(timeout, TIMEOUT_MS);
            }
        } catch (Throwable t) {
            // ROM 额外拦截时 addView 会抛异常：横幅通知兜底，绝不改弹 Activity
            Log.w(TAG, "addView auth prompt failed", t);
            synchronized (LOCK) {
                view = null;
                wm = null;
            }
            Notifier.authHeadsUp(app, r);
        }
    }

    /** 悬浮卡片与通知 Action 共用的决策入口，任何线程都可调用。 */
    static void decide(Context app, boolean allow, boolean remember, Req r) {
        final Req req;
        synchronized (LOCK) {
            req = r != null ? r : current;
        }
        MAIN.post(() -> {
            removeNow();
            Notifier.cancelAuthHeadsUp(app);
            AuthForegroundService.stop(app);
        });
        if (req == null) {
            return;
        }
        final boolean rememberFinal = remember && !"@unknown".equals(req.caller);
        App.executor().execute(() -> {
            try {
                JSONObject a = new JSONObject()
                        .put("caller", req.caller)
                        .put("remember", rememberFinal);
                RustCore.dispatch(allow ? "callerGrant" : "callerDeny", a.toString());
            } catch (Throwable ignored) {
            }
        });
        if (!allow) {
            DenyLog.record(app, req.uninstall, req.label, req.caller, req.callerLabel);
            return;
        }
        if (!req.uris.isEmpty() || req.uninstall) {
            // 直接续装本次请求：用户根本不用回到原应用重新点一次
            TaskLauncher.launch(app, req.uninstall, req.uris, req.sources,
                    req.uninstallPkg, req.caller, req.label);
        }
    }
}
