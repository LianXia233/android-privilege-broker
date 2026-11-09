package io.github.lianxia233.privbroker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * 无线调试配对的通知栏主通道。
 *
 * 交互模型与成熟实现一致，也是这类链路唯一可行的形态：系统「无线调试」页里的
 * 配对码弹窗一旦离开该页就会消失，用户不能切回应用输码；而下拉通知栏不会
 * 让设置页失去焦点，弹窗留在原地。因此服务必须在用户离开本应用之前就进入
 * 前台并常驻一条通知，发现配对服务后把通知换成带 RemoteInput 的输码框。
 *
 * 生命周期要点：
 * - {@code ACTION_START} 立即 {@code startForeground}，先显示「搜索中」通知；
 * - mDNS 发现配对端口后 {@code notify()} 替换为可输码通知，端口写进 Intent，
 *   服务被回收后重启仍能拿到；
 * - RemoteInput 回复以前台服务方式投递；
 * - {@code startForeground} 失败时降级为普通通知并撤销前台启动，避免系统在
 *   5 秒后以 ForegroundServiceDidNotStartInTimeException 终止进程。
 *
 * 连接地址：mDNS 解析出的地址（通常是 WLAN 接口 IP）与本机网卡地址、loopback
 * 一起作为候选交给 native 层逐个尝试，不再假定 adbd 绑定在 127.0.0.1。
 *
 * 配对与启动解耦：配对只用配对端口；成功后系统可能重分配连接端口，重新发现
 * 后再连接并启动特权代理。
 */
public final class WirelessPairingService extends Service {

    public static final String CHANNEL = "wireless_pairing";

    private static final String TAG = "WirelessPairing";
    private static final String ACTION_START = "start";
    private static final String ACTION_STOP = "stop";
    private static final String ACTION_REPLY = "reply";
    private static final String KEY_CODE = "pairing_code";
    private static final String KEY_PORT = "pairing_port";
    private static final int NOTIFICATION_ID = 42;
    private static final int REQ_REPLY = 1;
    private static final int REQ_STOP = 2;
    private static final int REQ_LOG = 3;
    private static final int REQ_OPEN = 4;
    private static final long CONNECT_WAIT_MS = 8000L;
    private static final long START_VERIFY_TIMEOUT_MS = 7000L;
    private static final long START_VERIFY_INTERVAL_MS = 400L;

    private static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp";
    private static final String SERVICE_CONNECT = "_adb-tls-connect._tcp";

    private final Handler main = new Handler(Looper.getMainLooper());

    private NotificationManager notifications;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener pairingListener;
    private NsdManager.DiscoveryListener connectListener;

    private volatile int pairingPort = -1;
    private volatile int connectPort = -1;
    private volatile int lastConnectPort = -1;
    private volatile String resolvedHost;
    private volatile boolean working;
    private volatile boolean awaitingConnect;
    private volatile boolean discovering;
    private volatile Runnable connectTimeout;
    private volatile long activationStartedAt;

    public static Intent startIntent(Context c) {
        return new Intent(c, WirelessPairingService.class).setAction(ACTION_START);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                getString(R.string.wireless_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        // 服务可能被系统回收后重启，此时教程页的探测结果仍可复用
        String cached = MdnsProbe.get(this).resolvedHost();
        if (cached != null) {
            resolvedHost = cached;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Notification notification;
        if (ACTION_STOP.equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        } else if (ACTION_REPLY.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = results == null ? null : results.getCharSequence(KEY_CODE);
            int port = intent.getIntExtra(KEY_PORT, -1);
            notification = code == null ? onStart() : onInput(code.toString().trim(), port);
        } else if (ACTION_START.equals(action) || action == null) {
            notification = onStart();
        } else {
            return START_NOT_STICKY;
        }

        if (notification != null) {
            startInForeground(notification);
        }
        return START_REDELIVER_INTENT;
    }

    /** 传 MANIFEST 类型由系统按清单取值；失败时保留普通通知并撤销前台启动。 */
    private void startInForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed, falling back to plain notification", t);
            try {
                notifications.notify(NOTIFICATION_ID, n);
            } catch (Throwable ignored) {
            }
            // DETACH 只解除前台关联、不删除刚发出的通知；再停掉服务，避免系统
            // 因 5 秒内未成功进入前台而终止整个应用进程。
            try {
                stopForeground(STOP_FOREGROUND_DETACH);
            } catch (Throwable ignored) {
            }
            stopSelf();
        }
    }

    private Notification onStart() {
        startDiscovery();
        return currentNotification();
    }

    private Notification onInput(String code, int port) {
        if (working || awaitingConnect) {
            return workingNotification();
        }
        if (!code.matches("\\d{6}")) {
            return inputNotification(getString(R.string.wireless_bad_code));
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return inputNotification(getString(R.string.wireless_requires_android11));
        }
        if (port > 0) {
            pairingPort = port;
        }
        if (pairingPort <= 0) {
            return inputNotification(getString(R.string.wireless_no_pairing_service));
        }
        startPair(code);
        return workingNotification();
    }

    // ------------------------------------------------------------ mDNS 发现

    private void startDiscovery() {
        if (discovering || nsd == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        discovering = true;
        pairingListener = makeListener(true);
        connectListener = makeListener(false);
        try {
            nsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener);
        } catch (Throwable t) {
            Log.w(TAG, "discover pairing service failed", t);
        }
        try {
            nsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener);
        } catch (Throwable t) {
            Log.w(TAG, "discover connect service failed", t);
        }
    }

    private void stopDiscovery() {
        if (!discovering || nsd == null) {
            return;
        }
        discovering = false;
        try {
            nsd.stopServiceDiscovery(pairingListener);
        } catch (Throwable ignored) {
        }
        try {
            nsd.stopServiceDiscovery(connectListener);
        } catch (Throwable ignored) {
        }
    }

    private NsdManager.DiscoveryListener makeListener(final boolean isPairing) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "start discovery failed: " + serviceType + " code=" + errorCode);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                if (!isPairing) {
                    main.post(() -> connectPort = -1);
                }
            }

            @Override
            public void onServiceFound(final NsdServiceInfo serviceInfo) {
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        }

                        @Override
                        public void onServiceResolved(final NsdServiceInfo resolved) {
                            final int port = resolved.getPort();
                            final String host = MdnsFilter.hostOf(resolved);
                            if (!MdnsFilter.accept(resolved, port)) {
                                return;
                            }
                            if (host != null) {
                                resolvedHost = host;
                            }
                            main.post(() -> onPort(isPairing, port));
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "resolve service failed", t);
                }
            }
        };
    }

    private void onPort(boolean isPairing, int port) {
        if (isPairing) {
            pairingPort = port;
        } else {
            connectPort = port;
            lastConnectPort = port;
        }
        if (awaitingConnect && !isPairing && port > 0 && !working) {
            startConnect(port);
            return;
        }
        if (working || awaitingConnect) {
            return;
        }
        // 只有发现配对服务后才推送可输码的通知（与参考实现一致）
        if (isPairing) {
            push(inputNotification(null));
        }
    }

    // ------------------------------------------------------------ 阶段一：配对

    private void startPair(final String code) {
        working = true;
        push(workingNotification());
        final int pPort = pairingPort;
        final String host = resolvedHost;
        new Thread(() -> {
            String raw = WirelessRunner.pair(WirelessPairingService.this, pPort, code, host);
            main.post(() -> handleResult(raw));
        }, "wireless-pairing").start();
    }

    // ------------------------------------------------------------ 阶段二：连接并启动

    private void startConnect(final int port) {
        cancelConnectTimeout();
        awaitingConnect = false;
        working = true;
        activationStartedAt = System.currentTimeMillis();
        push(workingNotification());
        final String host = resolvedHost;
        new Thread(() -> {
            String raw = WirelessRunner.start(WirelessPairingService.this, port, host);
            main.post(() -> handleResult(raw));
        }, "wireless-start").start();
    }

    private void scheduleConnectTimeout() {
        cancelConnectTimeout();
        connectTimeout = () -> {
            if (!awaitingConnect) {
                return;
            }
            if (lastConnectPort > 0) {
                startConnect(lastConnectPort);
            } else {
                awaitingConnect = false;
                push(inputNotification(getString(R.string.wireless_no_connect_service)));
            }
        };
        main.postDelayed(connectTimeout, CONNECT_WAIT_MS);
    }

    private void cancelConnectTimeout() {
        if (connectTimeout != null) {
            main.removeCallbacks(connectTimeout);
            connectTimeout = null;
        }
    }

    /**
     * 同步判定「特权代理可用」，与激活页共用同一判据，保证通知栏自动激活与
     * 页面状态一致。
     *
     * <p>必须用完整握手而不是只探端口：端点是按配对令牌派生的固定端口，被
     * 陈旧实例占住时端口探测恒为真，一次实际失败的激活会被误报成成功。</p>
     */
    private boolean isServiceUp() {
        return BrokerClient.handshake(getApplicationContext());
    }

    /** 投递启动命令后轮询等待代理真正常驻，返回是否实测到。 */
    private boolean awaitServiceUp() {
        long deadline = System.currentTimeMillis() + START_VERIFY_TIMEOUT_MS;
        do {
            if (isServiceUp()) {
                return true;
            }
            try {
                Thread.sleep(START_VERIFY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < deadline);
        return isServiceUp();
    }

    private void handleResult(String resultJson) {
        boolean ok = false;
        String code = "";
        String message = "";
        String stage = "";
        String output = "";
        String failure = "";
        try {
            JSONObject j = new JSONObject(resultJson);
            ok = j.optBoolean("ok", false);
            code = j.optString("code", "");
            message = j.optString("message", "");
            stage = j.optString("stage", "");
            output = j.optString("output", "").trim();
            failure = j.optString("failureReason", "").trim();
        } catch (Throwable ignored) {
            message = "结果解析失败";
        }
        // native 侧把失败行单独结构化带回，通道时序截断日志时也不丢原因
        if (!failure.isEmpty() && !output.contains(failure)) {
            output = output.isEmpty() ? failure : output + "\n" + failure;
        }
        final String diag = output;
        final String resultCode = code;
        final long durationMs = activationStartedAt > 0
                ? System.currentTimeMillis() - activationStartedAt : 0L;
        working = false;

        if (ok && WirelessRunner.STAGE_PAIRED.equals(stage)) {
            awaitingConnect = true;
            connectPort = -1;
            push(waitingNotification());
            scheduleConnectTimeout();
            return;
        }
        if (ok && WirelessRunner.STAGE_STARTED.equals(stage)) {
            // 唯一成功判据：app 侧实测到代理监听端点。启动日志里的
            // 「监听成功 / 已在运行」只作诊断线索，不能替代实测，否则会出现
            // 「报激活成功但页面显示未运行」的自相矛盾。
            final boolean confirmed = ActivationLog.startLogConfirmsRunning(resultJson);
            working = true;
            push(workingNotification());
            new Thread(() -> {
                boolean up = false;
                try {
                    up = awaitServiceUp();
                } catch (Throwable t) {
                    // 校验线程里的异常同样不能穿透：它是唯一复位 working、
                    // 发出结果通知的地方，穿透会让通知栏永久停在「激活中」，
                    // 之后所有激活/配对点击都变成静默 no-op。
                    Log.w(TAG, "无线激活校验异常", t);
                }
                final boolean probed = up;
                main.post(() -> {
                    working = false;
                    final int port = lastConnectPort;
                    final String host = resolvedHost;
                    if (probed) {
                        stopDiscovery();
                        String successText = getString(R.string.wireless_success_text);
                        notifyResult(getString(R.string.wireless_success_title),
                                successText, true);
                        ActivationLog.record(WirelessPairingService.this, true, port, host, "",
                                successText, diag, durationMs);
                        cancelConnectTimeout();
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    } else {
                        awaitingConnect = false;
                        cancelConnectTimeout();
                        String summary = confirmed
                                ? getString(R.string.pairing_unreachable_confirmed)
                                : ActivationLog.summarize(
                                        WirelessPairingService.this, diag, false);
                        ActivationLog.record(WirelessPairingService.this, false, port, host,
                                resultCode, summary, diag, durationMs);
                        // 失败通知仍保留输码入口，但大文本里给出人话诊断与下一步
                        push(inputNotification(summary + "\n\n"
                                + getString(R.string.wireless_start_not_verified)));
                    }
                });
            }, "wireless-verify").start();
            return;
        }
        awaitingConnect = false;
        cancelConnectTimeout();
        push(inputNotification(friendlyMessage(code, message, stage)));
    }

    private String friendlyMessage(String code, String message, String stage) {
        switch (code) {
            case "BAD_PAIRING_CODE":
                return getString(R.string.wireless_wrong_code);
            case "IO":
                return getString(R.string.wireless_io_error);
            case "TLS":
                return getString(R.string.wireless_tls_error);
            case "KEY":
                return getString(R.string.wireless_key_error) + message;
            default:
                // 不再把 started 这类内部阶段名原样显示给用户
                return message;
        }
    }

    // ------------------------------------------------------------ 通知

    private void push(Notification n) {
        try {
            notifications.notify(NOTIFICATION_ID, n);
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
        }
    }

    private void notifyResult(String title, String text, boolean autoCancel) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_action_broker)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .addAction(viewLogAction())
                .setAutoCancel(autoCancel)
                .build();
        try {
            notifications.notify(NOTIFICATION_ID + 1, n);
        } catch (Throwable ignored) {
        }
    }

    private Notification currentNotification() {
        if (working) {
            return workingNotification();
        }
        if (awaitingConnect) {
            return waitingNotification();
        }
        if (pairingPort > 0) {
            return inputNotification(null);
        }
        return searchingNotification();
    }

    private Notification.Builder base() {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_action_broker)
                .setColor(getColor(R.color.brand_primary))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true);
    }

    private Notification searchingNotification() {
        Intent stop = new Intent(this, WirelessPairingService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, REQ_STOP, stop, immutableFlags());
        return base()
                .setContentTitle(getString(R.string.wireless_searching_title))
                .setContentText(getString(R.string.wireless_searching_text))
                .addAction(new Notification.Action.Builder(
                        (Icon) null, getString(R.string.wireless_stop), pi).build())
                .build();
    }

    private Notification inputNotification(String hint) {
        RemoteInput remoteInput = new RemoteInput.Builder(KEY_CODE)
                .setLabel(getString(R.string.wireless_code_input_label))
                .setAllowFreeFormInput(true)
                .build();
        // 端口放进 Intent：服务可能在用户输码前被回收，重启后仍能拿到端口
        Intent reply = new Intent(this, WirelessPairingService.class)
                .setAction(ACTION_REPLY)
                .putExtra(KEY_PORT, pairingPort);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getForegroundService(this, REQ_REPLY, reply, flags);
        String text = hint == null ? getString(R.string.wireless_input_text) : hint;
        // 点通知主体回到激活页：输完码紧接着就是点「激活服务」。
        Intent open = new Intent(this, PairingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, REQ_OPEN, open, immutableFlags());
        String step = getString(R.string.wireless_input_step);
        return base()
                .setContentTitle(getString(R.string.wireless_input_title))
                .setContentText(text)
                .setSubText(step)
                .setContentIntent(contentPi)
                // 整条通知着色 + 品牌大图标，让输码通知在下拉列表里一眼可见。
                .setColorized(true)
                .setColor(getColor(R.color.brand_primary))
                .setLargeIcon(Icon.createWithResource(this, R.drawable.ic_launcher_monochrome))
                // 分层大文本：标题 / 步骤 / 说明，诊断摘要不再挤成一坨。
                .setStyle(new Notification.BigTextStyle()
                        .setBigContentTitle(getString(R.string.wireless_input_title))
                        .setSummaryText(step)
                        .bigText(text))
                // RemoteInput 依赖系统原生 Action 渲染，绝不能用自定义 RemoteViews
                // 覆盖（MIUI / HyperOS「原生样式」下会丢掉内嵌输入框）。
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notif_code),
                        getString(R.string.wireless_input_code), pi)
                        .addRemoteInput(remoteInput)
                        .build())
                .addAction(viewLogAction())
                .build();
    }

    /** 通知里直达「执行日志」，完整原始启动日志在那里可查。 */
    private Notification.Action viewLogAction() {
        Intent logs = new Intent(this, LogActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, REQ_LOG, logs, immutableFlags());
        return new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_action_logs),
                getString(R.string.wireless_notif_view_log), pi).build();
    }

    private Notification waitingNotification() {
        return base()
                .setContentTitle(getString(R.string.wireless_waiting_title))
                .setContentText(getString(R.string.wireless_waiting_text))
                .build();
    }

    private Notification workingNotification() {
        return base()
                .setContentTitle(getString(R.string.wireless_working_title))
                .setContentText(getString(R.string.wireless_working_text))
                .build();
    }

    private static int immutableFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    @Override
    public void onTimeout(int startId) {
        stopDiscovery();
        stopForeground(STOP_FOREGROUND_REMOVE);
        cancelConnectTimeout();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        cancelConnectTimeout();
        stopDiscovery();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopAll() {
        cancelConnectTimeout();
        stopDiscovery();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        try {
            notifications.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }
}
