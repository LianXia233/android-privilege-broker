package io.github.lianxia233.privbroker;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
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
 * 无线调试配对教程页。
 *
 * 形态与成熟实现一致：本页只负责引导与检查前置条件，**一进入页面就启动
 * 前台服务**，让通知栏立刻出现常驻通知。用户随后点「去无线调试」进入系统
 * 设置、打开配对码弹窗，此时不能退出也不能返回（离开设置页弹窗即消失），
 * 只能下拉通知栏 —— 下拉不会让设置页失去焦点 —— 在常驻通知里输入 6 位码。
 *
 * 因此通知栏是唯一主路径；页面内的端口/配对码输入框只作为小窗、分屏或
 * 平板等多窗口场景下的备用入口，不依赖通知权限与 ROM 通知样式。
 *
 * 连接地址不再假定 loopback：mDNS 解析出的 WLAN 接口 IP 与本机网卡地址、
 * 127.0.0.1 一起作为候选交给 native 层逐个尝试。
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
    private Button btnRestart;
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
        btnRestart = findViewById(R.id.btnNotification);
        portInput = findViewById(R.id.portInput);
        codeInput = findViewById(R.id.codeInput);

        findViewById(R.id.btnDevOptions).setOnClickListener(v -> openDeveloperOptions());
        findViewById(R.id.btnNotifSettings).setOnClickListener(v -> openNotificationSettings());
        findViewById(R.id.btnBattery).setOnClickListener(v -> openBatterySettings());
        btnPair.setOnClickListener(v -> onPairClicked());
        btnRestart.setOnClickListener(v -> startPairingService(true));

        if (RomCompat.isMiuiLike()) {
            cardMiui.setVisibility(View.VISIBLE);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            statusText.setText(R.string.pairing_requires_android11);
            btnPair.setEnabled(false);
            btnRestart.setEnabled(false);
            return;
        }

        notificationEnabled = isNotificationEnabled();
        syncNotificationCard();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            } catch (Throwable t) {
                Log.w(TAG, "request POST_NOTIFICATIONS failed", t);
            }
        }

        // 通知已开启时立即启动前台服务，用户离开本页之前通知栏就有常驻通知。
        if (notificationEnabled) {
            startPairingService(false);
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
            // 用户刚在通知设置里放行：立刻补启动前台服务
            if (enabled) {
                startPairingService(false);
            }
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
        if (notificationEnabled) {
            startPairingService(false);
        }
    }

    // ------------------------------------------------------------ 权限与前置条件

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

    /**
     * 启动通知栏输码通道。参考实现在教程页 onCreate / onResume 里直接调用
     * startForegroundService；被系统禁止后台启动前台服务时降级为普通 startService
     * 并提示用户关闭电池优化。
     */
    private void startPairingService(boolean userInitiated) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        Intent intent = WirelessPairingService.startIntent(this);
        try {
            startForegroundService(intent);
            if (userInitiated) {
                toast(getString(R.string.pairing_notif_mode_started));
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed", t);
            // 用类名判断而非 instanceof：该异常类只在 Android 12+ 存在，
            // 避免在低版本 ROM 上触发类解析失败。
            boolean startNotAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && t.getClass().getName().contains("ForegroundServiceStartNotAllowedException");
            if (startNotAllowed) {
                try {
                    AppOpsManager ops = getSystemService(AppOpsManager.class);
                    int mode = ops == null ? AppOpsManager.MODE_ALLOWED
                            : ops.noteOpNoThrow("android:start_foreground",
                            android.os.Process.myUid(), getPackageName(), null, null);
                    if (mode == AppOpsManager.MODE_ERRORED) {
                        toast(getString(R.string.pairing_fgs_blocked));
                        openBatterySettings();
                        return;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    startService(intent);
                    if (userInitiated) {
                        toast(getString(R.string.pairing_notif_mode_started));
                    }
                } catch (Throwable t2) {
                    Log.w(TAG, "fallback startService failed", t2);
                    toast(getString(R.string.pairing_notif_mode_failed));
                }
            } else {
                toast(getString(R.string.pairing_notif_mode_failed));
            }
        }
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

    /** 部分 ROM 在应用不可见时禁止联网，即使有前台服务；需加入电池优化白名单。 */
    private void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            Log.w(TAG, "open battery settings failed", t);
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

    // ------------------------------------------------------------ 备用：页面内输码

    private void onPairClicked() {
        if (state == STATE_DONE) {
            finish();
            return;
        }
        if (state == STATE_PAIRING || state == STATE_STARTING) {
            return;
        }
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
        final String host = MdnsProbe.get(this).resolvedHost();
        worker.execute(() -> {
            String raw = WirelessRunner.pair(PairingActivity.this, finalPort, finalCode, host);
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

    private void doStart(int port) {
        if (state == STATE_STARTING || state == STATE_DONE) {
            return;
        }
        cancelConnectTimeout();
        state = STATE_STARTING;
        setWorking(true, R.string.pairing_starting);
        final int finalPort = port;
        final String host = MdnsProbe.get(this).resolvedHost();
        worker.execute(() -> {
            String raw = WirelessRunner.start(PairingActivity.this, finalPort, host);
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
        btnRestart.setEnabled(!working);
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
