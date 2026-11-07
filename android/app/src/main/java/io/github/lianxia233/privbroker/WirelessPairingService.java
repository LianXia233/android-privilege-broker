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
 * 通知栏输码通道（可选）。
 *
 * 主路径是 PairingActivity 里的页面内输码，本服务只在用户显式点「改用通知栏输码」
 * 时启动。形态与同类成熟实现一致：清单里只声明 shortService，发现配对服务端口后
 * 才发带 RemoteInput 的通知，回复以前台服务方式投递，端口放进 Intent 以免服务
 * 被回收后丢失。
 *
 * 配对与启动同样解耦：配对阶段只依赖配对服务端口；成功后系统可能重分配连接
 * 服务端口，需重新发现该端口再执行连接与 shell 启动。
 *
 * 一类必须防御的系统行为：调用 startForegroundService 后若服务没能在 5 秒内
 * 成功 startForeground，系统会抛 ForegroundServiceDidNotStartInTimeException
 * 直接终止应用进程。因此 startForeground 一旦失败，立即 notify + stopSelf，
 * 让系统撤销这次前台启动；绝不允许服务带着未完成的 FGS 启动挂着。
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
    private static final long CONNECT_WAIT_MS = 8000L;

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
    private volatile boolean working;
    private volatile boolean awaitingConnect;
    private volatile boolean discovering;
    private volatile Runnable connectTimeout;

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

    /**
     * 传 FOREGROUND_SERVICE_TYPE_MANIFEST 由系统按清单取值；失败立即收尾，
     * 避免系统在 5 秒后因 FGS 未启动而终止进程。
     */
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
            // 关键：撤销这次前台启动，否则系统会以超时异常杀掉整个进程
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
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
                            if (MdnsFilter.accept(resolved, port)) {
                                main.post(() -> onPort(isPairing, port));
                            }
                        }
                    });
                } catch (Throwable ignored) {
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
        // 配对成功后等的是「新的」连接服务端口
        if (awaitingConnect && !isPairing && port > 0 && !working) {
            startConnect(port);
            return;
        }
        if (working || awaitingConnect) {
            return;
        }
        // 与参考实现一致：只有发现配对服务后才推送可输码的通知
        if (isPairing) {
            push(inputNotification(null));
        }
    }

    // ------------------------------------------------------------ 阶段一：配对

    private void startPair(final String code) {
        working = true;
        push(workingNotification());
        final int pPort = pairingPort;
        new Thread(() -> {
            String raw = WirelessRunner.pair(WirelessPairingService.this, pPort, code);
            main.post(() -> handleResult(raw));
        }, "wireless-pairing").start();
    }

    // ------------------------------------------------------------ 阶段二：连接并启动

    private void startConnect(final int port) {
        cancelConnectTimeout();
        awaitingConnect = false;
        working = true;
        push(workingNotification());
        new Thread(() -> {
            String raw = WirelessRunner.start(WirelessPairingService.this, port);
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

    private void handleResult(String resultJson) {
        boolean ok = false;
        String code = "";
        String message = "";
        String stage = "";
        try {
            JSONObject j = new JSONObject(resultJson);
            ok = j.optBoolean("ok", false);
            code = j.optString("code", "");
            message = j.optString("message", "");
            stage = j.optString("stage", "");
        } catch (Throwable ignored) {
            message = "结果解析失败";
        }
        working = false;

        if (ok && WirelessRunner.STAGE_PAIRED.equals(stage)) {
            // 配对成功：丢弃旧连接端口，等待系统重新广播连接服务
            awaitingConnect = true;
            connectPort = -1;
            push(waitingNotification());
            scheduleConnectTimeout();
            return;
        }
        if (ok && WirelessRunner.STAGE_STARTED.equals(stage)) {
            stopDiscovery();
            notifyResult(getString(R.string.wireless_success_title),
                    getString(R.string.wireless_success_text), true);
            stopForeground(STOP_FOREGROUND_REMOVE);
            cancelConnectTimeout();
            stopSelf();
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
                return (stage.isEmpty() ? "" : stage + ": ") + message;
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
                .setLabel(getString(R.string.wireless_code_hint))
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
        return base()
                .setContentTitle(getString(R.string.wireless_input_title))
                .setContentText(text)
                .addAction(new Notification.Action.Builder(
                        (Icon) null, getString(R.string.wireless_input_code), pi)
                        .addRemoteInput(remoteInput)
                        .build())
                .build();
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
        // shortService 约 3 分钟时限到达：只收尾，不再发常驻提示
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
