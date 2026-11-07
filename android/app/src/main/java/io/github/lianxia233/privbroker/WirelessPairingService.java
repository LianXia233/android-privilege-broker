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

import org.json.JSONObject;

import java.io.File;

/**
 * 无电脑自助启用链路。
 *
 * 前台服务持续发现无线调试的配对服务与连接服务，通知栏直接输入 6 位配对码，
 * 交由 native 层完成 TLS 握手、SPAKE2 配对、公钥注册、ADB TLS 连接与 shell 启动。
 * 全程不需要电脑、不需要 USB，也不需要任何第三方特权框架。
 */
public final class WirelessPairingService extends Service {

    public static final String CHANNEL = "wireless_pairing";

    private static final String ACTION_START = "start";
    private static final String ACTION_STOP = "stop";
    private static final String ACTION_REPLY = "reply";
    private static final String KEY_CODE = "pairing_code";
    private static final int NOTIFICATION_ID = 42;

    private static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp";
    private static final String SERVICE_CONNECT = "_adb-tls-connect._tcp";

    private final Handler main = new Handler(Looper.getMainLooper());

    private NotificationManager notifications;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener pairingListener;
    private NsdManager.DiscoveryListener connectListener;

    private volatile int pairingPort = -1;
    private volatile int connectPort = -1;
    private volatile String pendingCode;
    private volatile boolean working;
    private volatile boolean discovering;

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
        if (ACTION_STOP.equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (ACTION_REPLY.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = results == null ? null : results.getCharSequence(KEY_CODE);
            if (code != null) {
                onCode(code.toString().trim());
            }
        }
        startInForeground(currentNotification());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !discovering && nsd != null) {
            startDiscovery();
        }
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ mDNS 发现

    private void startDiscovery() {
        if (discovering) {
            return;
        }
        discovering = true;
        pairingListener = makeListener(true);
        connectListener = makeListener(false);
        try {
            nsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener);
        } catch (Throwable ignored) {
        }
        try {
            nsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener);
        } catch (Throwable ignored) {
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
                            if (port <= 0) {
                                return;
                            }
                            main.post(() -> onPort(isPairing, port));
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
        }
        if (!working) {
            if (isPairing && pendingCode == null) {
                pushNotification();
            }
            if (!isPairing && pendingCode != null) {
                String code = pendingCode;
                pendingCode = null;
                startWork(code);
            }
        }
    }

    // ------------------------------------------------------------ 配对与启动

    private void onCode(String code) {
        if (working) {
            return;
        }
        if (code.length() != 6 || !code.matches("\\d{6}")) {
            showInputHint(getString(R.string.wireless_bad_code));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showInputHint(getString(R.string.wireless_requires_android11));
            return;
        }
        if (pairingPort <= 0) {
            showInputHint(getString(R.string.wireless_no_pairing_service));
            return;
        }
        if (connectPort <= 0) {
            pendingCode = code;
            pushNotification();
            return;
        }
        startWork(code);
    }

    private void startWork(final String code) {
        working = true;
        pendingCode = null;
        pushNotification();
        final int pPort = pairingPort;
        final int cPort = connectPort;
        new Thread(() -> {
            String result;
            try {
                JSONObject args = new JSONObject();
                args.put("host", "127.0.0.1");
                args.put("pairingPort", pPort);
                args.put("pairingCode", code);
                args.put("connectPort", cPort);
                args.put("keyPath", new File(getFilesDir(), "adb_key.pk8").getAbsolutePath());
                args.put("apkPath", getApplicationInfo().sourceDir);
                args.put("nativeLibDir", getApplicationInfo().nativeLibraryDir);
                args.put("token", BrokerClient.token(WirelessPairingService.this));
                args.put("client", BrokerClient.clientId(WirelessPairingService.this));
                args.put("endpoint", BrokerClient.endpoint(WirelessPairingService.this));
                WirelessCore.ensureLoaded(null);
                result = WirelessCore.run(args.toString());
            } catch (Throwable t) {
                result = errorJson("BRIDGE", "本地调用失败: " + t.getMessage());
            }
            showResult(result);
        }, "wireless-pairing").start();
    }

    private static String errorJson(String code, String message) {
        try {
            return new JSONObject()
                    .put("ok", false)
                    .put("stage", "config")
                    .put("code", code)
                    .put("message", message)
                    .toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"stage\":\"config\",\"code\":\"PROTOCOL\",\"message\":\"结果异常\"}";
        }
    }

    private void showResult(String resultJson) {
        main.post(() -> {
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
            if (ok) {
                Notification done = new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_action_broker)
                        .setContentTitle(getString(R.string.wireless_success_title))
                        .setContentText(getString(R.string.wireless_success_text))
                        .setStyle(new Notification.BigTextStyle()
                                .bigText(getString(R.string.wireless_success_text)))
                        .setAutoCancel(true)
                        .build();
                notifications.notify(NOTIFICATION_ID, done);
                stopDiscovery();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            } else {
                showInputHint(friendlyMessage(code, message, stage));
            }
        });
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
            case "PROTOCOL":
                return (stage.isEmpty() ? "" : stage + ": ") + message;
            default:
                return (stage.isEmpty() ? "" : stage + ": ") + message;
        }
    }

    // ------------------------------------------------------------ 通知

    private void startInForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            notifications.notify(NOTIFICATION_ID, n);
        }
    }

    private void pushNotification() {
        notifications.notify(NOTIFICATION_ID, currentNotification());
    }

    private void showInputHint(String text) {
        notifications.notify(NOTIFICATION_ID, inputNotification(text));
    }

    private Notification currentNotification() {
        if (working) {
            return workingNotification();
        }
        if (pendingCode != null) {
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
        PendingIntent pi = PendingIntent.getService(this, 3, stop, immutableFlags());
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
        Intent reply = new Intent(this, WirelessPairingService.class).setAction(ACTION_REPLY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getForegroundService(this, 1, reply, flags);
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

    private void stopAll() {
        stopDiscovery();
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifications.cancel(NOTIFICATION_ID);
        stopSelf();
    }
}
