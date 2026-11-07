package io.github.lianxia233.privbroker;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 安装期间的左上角科幻风圆形进度指示器。
 *
 * 只使用 {@code TYPE_APPLICATION_OVERLAY}：这是唯一能浮在其他应用之上、完全不切换
 * task、不 pause 前台应用、点击穿透的窗口类型，不会染黑状态栏/导航栏，也不会出现
 * 全屏遮罩。
 *
 * 绝不再用普通 Activity 窗口来画小圆：Activity 主窗口在多数 ROM 上会被强制回
 * MATCH_PARENT，配合透明/空背景会合成出整屏纯黑；而 {@code startActivity} 又会把
 * 本应用 task 拉到前台 —— 那正是「安装时整屏黑 + 跳回软件」的确切原因。
 *
 * 没有 SYSTEM_ALERT_WINDOW 权限时，经自主特权通道以 shell 身份执行
 * {@code appops set <pkg> SYSTEM_ALERT_WINDOW allow} 自动授予。自动授权也失败时
 * 不显示任何窗口（安装照常由通知反馈），绝不降级成 Activity。
 */
final class FloatingIndicator {
    private static final String TAG = "FloatingIndicator";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static boolean active;
    private static boolean granting;
    private static WindowManager overlayWm;
    private static View overlayView;
    private static SciFiSpinner overlaySpinner;

    private FloatingIndicator() {
    }

    static boolean canDraw(Context c) {
        try {
            return Settings.canDrawOverlays(c.getApplicationContext());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void openPermissionSettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 安装任务开始时调用；任意线程、任意次数均幂等。 */
    static void show(Context c, boolean enabled) {
        if (!enabled) {
            return;
        }
        final Context app = c.getApplicationContext();
        synchronized (LOCK) {
            if (active) {
                return;
            }
            active = true;
        }
        if (canDraw(app)) {
            MAIN.post(() -> addOverlay(app));
            return;
        }
        // 没有悬浮窗权限：后台经自主通道尝试自动授权，成功后才挂 overlay。
        // 授权失败就静默放弃指示器，绝不启动任何 Activity。
        synchronized (LOCK) {
            if (granting) {
                return;
            }
            granting = true;
        }
        App.executor().execute(() -> {
            boolean ok = grantOverlayPermission(app);
            synchronized (LOCK) {
                granting = false;
            }
            if (ok) {
                MAIN.post(() -> addOverlay(app));
            }
        });
    }

    /** 设置页手动触发：尝试自动授权，完成后在主线程回调（无论成功与否）。 */
    static void ensurePermissionAsync(Context c, Runnable onDone) {
        final Context app = c.getApplicationContext();
        if (canDraw(app)) {
            if (onDone != null) {
                MAIN.post(onDone);
            }
            return;
        }
        synchronized (LOCK) {
            if (granting) {
                if (onDone != null) {
                    MAIN.post(onDone);
                }
                return;
            }
            granting = true;
        }
        App.executor().execute(() -> {
            grantOverlayPermission(app);
            synchronized (LOCK) {
                granting = false;
            }
            if (onDone != null) {
                MAIN.post(onDone);
            }
        });
    }

    /**
     * 经自主特权通道以 shell 身份把本应用的 AppOps OP 24
     * (SYSTEM_ALERT_WINDOW) 设为 allow，等价于
     * {@code adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow}。
     * 不同 ROM 的命令入口不一致，逐个尝试并以 canDrawOverlays 的真实结果为准。
     */
    private static boolean grantOverlayPermission(Context app) {
        if (canDraw(app)) {
            return true;
        }
        String pkg = app.getPackageName();
        String[][] candidates = {
                {"appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"},
                {"cmd", "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"},
                {"appops", "set", "--user", "0", pkg, "SYSTEM_ALERT_WINDOW", "allow"},
                {"appops", "set", pkg, "24", "allow"},
        };
        for (String[] argv : candidates) {
            try {
                JSONArray arr = new JSONArray();
                for (String s : argv) {
                    arr.put(s);
                }
                JSONObject args = new JSONObject().put("argv", arr);
                String resp = BrokerBridge.call("exec", args.toString());
                if (resp == null) {
                    continue;
                }
                JSONObject o = new JSONObject(resp);
                // {"error":...} 表示桥接层/特权代理未就绪，换下一条命令或放弃
                if (o.has("error")) {
                    continue;
                }
                // 退出码不可靠，以系统真实状态为准
                if (canDraw(app)) {
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "overlay grant attempt failed", t);
            }
        }
        return canDraw(app);
    }

    private static void addOverlay(Context app) {
        synchronized (LOCK) {
            if (!active || overlayView != null) {
                return;
            }
            try {
                WindowManager wm = (WindowManager)
                        app.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) {
                    return;
                }
                float density = app.getResources().getDisplayMetrics().density;
                int size = (int) (40 * density);
                int pad = (int) (8 * density);
                SciFiSpinner v = new SciFiSpinner(app);
                // 刻意不加 FLAG_LAYOUT_NO_LIMITS：部分 ROM 会因此让窗口延伸到
                // 系统栏区域并干扰系统栏配色。窗口保持 40dp、不聚焦、不可触摸，
                // 落在内容区左上角（状态栏下方），不覆盖也不改变系统栏。
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        size, size,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = pad;
                lp.y = pad;
                wm.addView(v, lp);
                overlayWm = wm;
                overlayView = v;
                overlaySpinner = v;
                v.startAnimation();
            } catch (Throwable t) {
                // 权限被 ROM 额外拦截时 addView 会抛异常：静默放弃，绝不降级成窗口
                Log.w(TAG, "addView overlay failed, indicator skipped", t);
            }
        }
    }

    /** 0..100，负数表示不确定进度。 */
    static void progress(int pct) {
        MAIN.post(() -> {
            synchronized (LOCK) {
                if (overlaySpinner != null) {
                    overlaySpinner.setProgress(pct);
                }
            }
        });
    }

    /** 安装结束（无论成功失败）后移除指示器。 */
    static void dismiss() {
        MAIN.post(() -> {
            synchronized (LOCK) {
                active = false;
                if (overlayView != null) {
                    try {
                        if (overlayWm != null) {
                            overlayWm.removeViewImmediate(overlayView);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (overlaySpinner != null) {
                    overlaySpinner.stopAnimation();
                }
                overlayView = null;
                overlaySpinner = null;
                overlayWm = null;
            }
        });
    }

}
