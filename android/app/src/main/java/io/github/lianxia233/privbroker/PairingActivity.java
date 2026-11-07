package io.github.lianxia233.privbroker;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 无线调试配对页：教程、服务发现、配对码输入、结果反馈都在同一个前台界面完成。
 *
 * 结构上把「教程」与「输码」合成一页，是为了避开弹窗生命周期问题：用户需要
 * 跳到系统开发者选项打开配对弹窗，期间本页会进入后台；若输码依赖 Dialog，
 * 后台弹出的窗口既可能不显示，也可能在界面重建后丢失。
 *
 * 本页不自动启动任何前台服务。mDNS 发现直接绑定本页生命周期，配对码在页面内
 * 输入，因此通知权限、通知样式、前台服务类型、ROM 后台策略都不会影响配对能力。
 *
 * 链路按参考实现分成两个阶段：
 * 1. 配对：只用配对服务端口 + 6 位码注册 ADB 公钥；
 * 2. 启动：配对成功后系统可能重分配连接服务端口，重新发现该端口再连接并
 *    执行 shell。配对阶段不再要求连接服务已就绪，两阶段各自可独立失败/重试。
 */
public final class PairingActivity extends Activity {

    private static final String TAG = "PairingActivity";

    private static final int STATE_IDLE = 0;
    private static final int STATE_PAIRING = 1;
    private static final int STATE_AWAIT_CONNECT = 2;
    private static final int STATE_STARTING = 3;
    private static final int STATE_DONE = 4;

    private static final long CONNECT_WAIT_MS = 8000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView resultText;
    private LinearLayout cardNotif;
    private LinearLayout cardMiui;
    private Button btnPair;
    private Button btnNotification;
    private EditText portInput;
    private EditText codeInput;

    private int pairingPort = -1;
    private int connectPort = -1;
    private int lastConnectPort = -1;
    private int state = STATE_IDLE;
    private boolean paired;
    private boolean notificationEnabled;
    private Runnable connectTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);

        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);
        cardNotif = findViewById(R.id.cardNotif);
        cardMiui = findViewById(R.id.cardMiui);
        btnPair = findViewById(R.id.btnPair);
        btnNotification = findViewById(R.id.btnNotification);
        portInput = findViewById(R.id.portInput);
        codeInput = findViewById(R.id.codeInput);

        findViewById(R.id.btnDevOptions).setOnClickListener(v -> openDeveloperOptions());
        findViewById(R.id.btnNotifSettings).setOnClickListener(v -> openNotificationSettings());
        btnPair.setOnClickListener(v -> onPairClicked());
        btnNotification.setOnClickListener(v -> startNotificationMode());

        if (RomCompat.isMiuiLike()) {
            cardMiui.setVisibility(View.VISIBLE);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            statusText.setText(R.string.pairing_requires_android11);
            btnPair.setEnabled(false);
            btnNotification.setEnabled(false);
            return;
        }

        notificationEnabled = isNotificationEnabled();
        syncNotificationCard();

        // 可选：顺带申请通知权限，但不阻塞配对主路径
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            } catch (Throwable t) {
                Log.w(TAG, "request POST_NOTIFICATIONS failed", t);
            }
        }

        try {
            MdnsProbe.get(this).start(this::onPorts);
        } catch (Throwable t) {
            Log.w(TAG, "mDNS start failed", t);
            statusText.setText(R.string.pairing_mdns_failed);
        }
        applyCachedPorts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = isNotificationEnabled();
        if (enabled != notificationEnabled) {
            notificationEnabled = enabled;
            syncNotificationCard();
        }
        applyCachedPorts();
    }

    @Override
    protected void onDestroy() {
        cancelConnectTimeout();
        try {
            MdnsProbe.get(this).clearCallback();
        } catch (Throwable ignored) {
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        notificationEnabled = isNotificationEnabled();
        syncNotificationCard();
    }

    // ------------------------------------------------------------ 教程与权限

    private boolean isNotificationEnabled() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null || !nm.areNotificationsEnabled()) {
                return false;
            }
            NotificationChannel channel = nm.getNotificationChannel(WirelessPairingService.CHANNEL);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (Throwable t) {
            Log.w(TAG, "check notification state failed", t);
            return false;
        }
    }

    private void syncNotificationCard() {
        cardNotif.setVisibility(notificationEnabled ? View.GONE : View.VISIBLE);
    }

    private void openDeveloperOptions() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.pairing_settings_unavailable));
        } catch (Throwable t) {
            Log.w(TAG, "open developer options failed", t);
            toast(getString(R.string.pairing_settings_unavailable));
        }
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "open notification settings failed", t);
        }
    }

    // ------------------------------------------------------------ 服务发现

    private void applyCachedPorts() {
        try {
            MdnsProbe probe = MdnsProbe.get(this);
            onPorts(probe.pairingPort(), probe.connectPort());
        } catch (Throwable ignored) {
        }
    }

    private void onPorts(int p, int c) {
        if (p > 0) {
            pairingPort = p;
        }
        if (c > 0) {
            connectPort = c;
            lastConnectPort = c;
        } else if (c < 0) {
            connectPort = -1;
        }
        if (isFinishing() || isDestroyed()) {
            return;
        }

        // 配对成功后等的是「新的」连接服务端口，重新出现即自动进入启动阶段
        if (state == STATE_AWAIT_CONNECT && c > 0) {
            doStart(c);
            return;
        }
        if (state == STATE_PAIRING || state == STATE_STARTING || state == STATE_DONE) {
            return;
        }

        if (pairingPort <= 0) {
            statusText.setText(R.string.pairing_status_searching);
            return;
        }
        portInput.setText(String.valueOf(pairingPort));
        portInput.setSelection(portInput.getText().length());
        if (connectPort > 0) {
            statusText.setText(getString(R.string.pairing_status_ready, pairingPort, connectPort));
        } else {
            statusText.setText(getString(R.string.pairing_status_wait_connect, pairingPort));
        }
    }

    // ------------------------------------------------------------ 阶段一：配对

    private void onPairClicked() {
        if (state == STATE_DONE) {
            finish();
            return;
        }
        if (state == STATE_PAIRING || state == STATE_STARTING) {
            return;
        }
        // 已配对时只需重试连接启动，不再消耗配对码
        if (paired) {
            int c = connectPort > 0 ? connectPort : lastConnectPort;
            if (c <= 0) {
                resultText.setVisibility(View.VISIBLE);
                resultText.setText(R.string.pairing_connect_lost);
                return;
            }
            doStart(c);
            return;
        }

        String code = codeInput.getText().toString().trim();
        if (!code.matches("\\d{6}")) {
            resultText.setVisibility(View.VISIBLE);
            resultText.setText(R.string.pairing_bad_code);
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (port < 1 || port > 65535) {
            resultText.setVisibility(View.VISIBLE);
            resultText.setText(R.string.pairing_bad_port);
            return;
        }

        state = STATE_PAIRING;
        setWorking(true, R.string.pairing_working);
        final String finalCode = code;
        final int finalPort = port;
        worker.execute(() -> {
            String raw = WirelessRunner.pair(PairingActivity.this, finalPort, finalCode);
            ui(() -> onPairResult(raw));
        });
    }

    private void onPairResult(String raw) {
        setWorking(false, 0);
        JSONObject j = parse(raw);
        boolean ok = j.optBoolean("ok", false);
        String stage = j.optString("stage", "");
        if (ok && WirelessRunner.STAGE_PAIRED.equals(stage)) {
            paired = true;
            state = STATE_AWAIT_CONNECT;
            resultText.setVisibility(View.VISIBLE);
            resultText.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            resultText.setText(R.string.pairing_paired_wait_connect);
            statusText.setText(R.string.pairing_paired_wait_connect);
            btnPair.setText(R.string.pairing_retry_connect);
            btnPair.setEnabled(false);
            // 强制重新解析连接服务：配对成功后其端口可能已重分配
            try {
                MdnsProbe.get(this).restart(this::onPorts);
            } catch (Throwable t) {
                Log.w(TAG, "restart discovery failed", t);
            }
            scheduleConnectTimeout();
            return;
        }
        state = STATE_IDLE;
        showFailure(j);
    }

    private void scheduleConnectTimeout() {
        cancelConnectTimeout();
        connectTimeout = () -> {
            if (state != STATE_AWAIT_CONNECT || isFinishing() || isDestroyed()) {
                return;
            }
            // 等不到新的连接服务广播时，退回配对前缓存的端口再试一次
            if (lastConnectPort > 0) {
                doStart(lastConnectPort);
            } else {
                state = STATE_IDLE;
                btnPair.setEnabled(true);
                resultText.setText(R.string.pairing_connect_lost);
            }
        };
        uiHandler.postDelayed(connectTimeout, CONNECT_WAIT_MS);
    }

    private void cancelConnectTimeout() {
        if (connectTimeout != null) {
            uiHandler.removeCallbacks(connectTimeout);
            connectTimeout = null;
        }
    }

    // ------------------------------------------------------------ 阶段二：连接并启动

    private void doStart(int port) {
        if (state == STATE_STARTING || state == STATE_DONE) {
            return;
        }
        cancelConnectTimeout();
        state = STATE_STARTING;
        setWorking(true, R.string.pairing_starting);
        final int finalPort = port;
        worker.execute(() -> {
            String raw = WirelessRunner.start(PairingActivity.this, finalPort);
            ui(() -> onStartResult(raw));
        });
    }

    private void onStartResult(String raw) {
        setWorking(false, 0);
        JSONObject j = parse(raw);
        if (j.optBoolean("ok", false)) {
            state = STATE_DONE;
            paired = true;
            statusText.setText(R.string.pairing_status_done);
            resultText.setVisibility(View.VISIBLE);
            resultText.setTextColor(getResources().getColor(R.color.success, getTheme()));
            resultText.setText(R.string.pairing_success);
            btnPair.setText(R.string.pairing_back);
            btnPair.setEnabled(true);
            try {
                MdnsProbe.get(this).stop();
            } catch (Throwable ignored) {
            }
            return;
        }
        state = STATE_IDLE;
        btnPair.setText(R.string.pairing_retry_connect);
        showFailure(j);
    }

    private void showFailure(JSONObject j) {
        resultText.setVisibility(View.VISIBLE);
        resultText.setTextColor(getResources().getColor(R.color.danger, getTheme()));
        resultText.setText(friendlyMessage(
                j.optString("code", ""), j.optString("stage", ""), j.optString("message", "")));
        btnPair.setEnabled(true);
        if (!paired) {
            btnPair.setText(R.string.pairing_retry);
        }
    }

    private JSONObject parse(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            try {
                return new JSONObject().put("message", getString(R.string.pairing_result_parse_failed));
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        }
    }

    private void setWorking(boolean working, int messageRes) {
        btnPair.setEnabled(!working);
        btnNotification.setEnabled(!working);
        if (messageRes != 0) {
            resultText.setVisibility(View.VISIBLE);
            resultText.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            resultText.setText(messageRes);
        }
    }

    private String friendlyMessage(String code, String stage, String message) {
        switch (code) {
            case "BAD_PAIRING_CODE":
                return getString(R.string.pairing_wrong_code);
            case "IO":
                return getString(R.string.pairing_io_error);
            case "TLS":
                return getString(R.string.pairing_tls_error);
            case "KEY":
                return getString(R.string.pairing_key_error) + message;
            case "BRIDGE":
                return getString(R.string.pairing_bridge_error) + message;
            default:
                return (stage.isEmpty() ? "" : stage + ": ") + message;
        }
    }

    // ------------------------------------------------------------ 可选通知通道

    /**
     * 只在用户显式点击时启动前台服务。这是通知栏输码的可选增强，
     * 主路径（页面内输码）不依赖它，服务启动失败也不会影响配对。
     */
    private void startNotificationMode() {
        if (!notificationEnabled) {
            toast(getString(R.string.pairing_notif_mode_unavailable));
            return;
        }
        try {
            startForegroundService(WirelessPairingService.startIntent(this));
            toast(getString(R.string.pairing_notif_mode_started));
        } catch (Throwable t) {
            Log.w(TAG, "start notification mode failed", t);
            toast(getString(R.string.pairing_notif_mode_failed));
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void ui(Runnable r) {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(r);
        }
    }
}
