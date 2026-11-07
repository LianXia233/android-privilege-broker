package io.github.lianxia233.privbroker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;

/**
 * 授权询问期间的短时前台服务，只负责「保活 + 通知」，不画任何界面。
 *
 * 为什么必须有它：外部应用通过 startActivity 拉起 {@link SilentInstallActivity}，
 * 该入口对普通调用会在 onCreate 内立即 finish，进程随即从前台降为 cached。
 * 而跨应用授权卡片是 {@code TYPE_APPLICATION_OVERLAY} 悬浮窗，需要异步申请
 * SYSTEM_ALERT_WINDOW 权限后再 addView —— 等权限回来时进程可能已被冻结或回收，
 * 在国产 ROM 上表现为「弹窗根本没弹出来 / 一闪而过」。
 *
 * 前台服务让进程在询问期间保持前台优先级，悬浮窗因此能稳定、即时地浮在
 * 当前正在使用的应用之上；用户做出选择或超时后立刻 stopForeground + stopSelf，
 * 通知同步移除，不做任何常驻。Android 14+ 以 shortService 类型启动，匹配
 * 90 秒级别的短时交互，系统也允许这类服务从「刚被拉起的前台应用」启动。
 *
 * 通知上的「允许 / 拒绝」Action 与悬浮卡片共用 {@link AuthPrompt#decide}，
 * 即使悬浮窗被 ROM 拦下、退化为横幅通知，用户仍可当场决策而不必切回本应用。
 */
public final class AuthForegroundService extends Service {
    private static final String TAG = "AuthFgService";
    private static final String CHANNEL = "auth_foreground";
    private static final int NOTIF_ID = 4101;

    static final String ACTION_SHOW = "io.github.lianxia233.privbroker.action.AUTH_SHOW";
    static final String ACTION_ALLOW = "io.github.lianxia233.privbroker.action.AUTH_ALLOW";
    static final String ACTION_DENY = "io.github.lianxia233.privbroker.action.AUTH_DENY";

    /** 构造一次询问意图。extras 的 key 复用入口常量，授权页可直接读同一份数据。 */
    static Intent showIntent(Context c, AuthPrompt.Req r) {
        Intent i = new Intent(c, AuthForegroundService.class).setAction(ACTION_SHOW);
        fill(i, r);
        return i;
    }

    /** 通知正文点击后进入普通不透明授权页，携带与悬浮卡片一致的完整请求快照。 */
    static Intent authPageIntent(Context c, AuthPrompt.Req r) {
        Intent i = new Intent(c, CallerAuthActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fill(i, r);
        return i;
    }

    /** 通知 Action 用的意图，带完整请求数据，服务被重建也能独立决策。 */
    static Intent decisionIntent(Context c, AuthPrompt.Req r, boolean allow) {
        Intent i = new Intent(c, AuthForegroundService.class)
                .setAction(allow ? ACTION_ALLOW : ACTION_DENY);
        fill(i, r);
        return i;
    }

    private static void fill(Intent i, AuthPrompt.Req r) {
        i.putExtra(SilentInstallActivity.EXTRA_LABEL, r.label == null ? "" : r.label);
        i.putExtra(SilentInstallActivity.EXTRA_CALLER, r.caller == null ? "@unknown" : r.caller);
        i.putExtra(SilentInstallActivity.EXTRA_CALLER_LABEL,
                r.callerLabel == null ? "" : r.callerLabel);
        i.putExtra(SilentInstallActivity.EXTRA_UNINSTALL, r.uninstall);
        i.putExtra(SilentInstallActivity.EXTRA_UNINSTALL_PKG,
                r.uninstallPkg == null ? "" : r.uninstallPkg);
        i.putStringArrayListExtra(SilentInstallActivity.EXTRA_URIS, new ArrayList<>(r.uris));
        i.putStringArrayListExtra(SilentInstallActivity.EXTRA_SOURCES, new ArrayList<>(r.sources));
    }

    static AuthPrompt.Req requestFrom(Intent i) {
        AuthPrompt.Req r = new AuthPrompt.Req();
        if (i == null) {
            return r;
        }
        r.label = i.getStringExtra(SilentInstallActivity.EXTRA_LABEL);
        r.caller = i.getStringExtra(SilentInstallActivity.EXTRA_CALLER);
        r.callerLabel = i.getStringExtra(SilentInstallActivity.EXTRA_CALLER_LABEL);
        r.uninstall = i.getBooleanExtra(SilentInstallActivity.EXTRA_UNINSTALL, false);
        r.uninstallPkg = i.getStringExtra(SilentInstallActivity.EXTRA_UNINSTALL_PKG);
        ArrayList<String> u = i.getStringArrayListExtra(SilentInstallActivity.EXTRA_URIS);
        ArrayList<String> s = i.getStringArrayListExtra(SilentInstallActivity.EXTRA_SOURCES);
        if (u != null) {
            r.uris.addAll(u);
        }
        if (s != null) {
            r.sources.addAll(s);
        }
        if (r.caller == null || r.caller.isEmpty()) {
            r.caller = "@unknown";
        }
        if (r.label == null) {
            r.label = "安装包";
        }
        if (r.callerLabel == null) {
            r.callerLabel = "";
        }
        if (r.uninstallPkg == null) {
            r.uninstallPkg = "";
        }
        return r;
    }

    /** 停止保活服务（决策完成 / 超时 / 被取消时调用）。 */
    static void stop(Context c) {
        try {
            c.getApplicationContext().stopService(
                    new Intent(c.getApplicationContext(), AuthForegroundService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        // 无论哪个 action，都先确保进程处于前台；ACTION_SHOW 必须如此，
        // 通知 Action 在服务已被回收重建时也需要先合法化前台态。
        raiseForeground(i);
        String action = i == null ? null : i.getAction();
        if (ACTION_ALLOW.equals(action)) {
            AuthPrompt.decide(getApplicationContext(), true, false, requestFrom(i));
            stopNow();
        } else if (ACTION_DENY.equals(action)) {
            AuthPrompt.decide(getApplicationContext(), false, false, requestFrom(i));
            stopNow();
        } else {
            AuthPrompt.attach(getApplicationContext(), requestFrom(i));
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        AuthPrompt.dismiss();
        super.onDestroy();
    }

    private void stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void raiseForeground(Intent src) {
        try {
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_action_notification)
                    .setContentTitle(getString(R.string.auth_fg_title))
                    .setContentText(getString(R.string.auth_fg_text))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(Notification.VISIBILITY_PUBLIC);
            // 悬浮窗之外的补充入口：点通知进入普通授权页，携带同一份安装包快照，
            // 授权后可直接续装，不必回到原应用重新发起。
            Intent auth = new Intent(this, CallerAuthActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (src != null && src.getExtras() != null) {
                auth.putExtras(src.getExtras());
            }
            b.setContentIntent(PendingIntent.getActivity(this, 51, auth,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.auth_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.auth_channel_desc));
            ch.enableVibration(false);
            ch.setSound(null, null);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {
        }
    }
}
