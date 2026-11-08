package io.github.lianxia233.privbroker;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
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
 * 无线调试配对页（仅支持 Android 13+）。
 *
 * 交互模型把两件事明确拆开，二者不再耦合：
 *
 * <b>配对（一次性）</b>：把本机 ADB 公钥登记到设备 adbd。系统「无线调试」页的
 * 配对码弹窗不能退出、不能返回，一离开就消失，因此配对码只能在常驻通知的
 * RemoteInput 里输入（下拉通知栏不会让设置页失去焦点）。公钥与本机
 * {@code adb_key.pk8} 会持久保存，配对成功后一般无需再做。
 *
 * <b>激活服务（随时可重复）</b>：用已登记的公钥连接 {@code _adb-tls-connect}
 * 端口，经 ADB TLS 通道以 shell 身份拉起特权代理。设备重启、代理进程被回收后，
 * 只要无线调试仍开启且连着 WLAN，随时点「激活服务」即可重新拉起，无需重新配对。
 * 这一步是本页的一等公民主按钮，独立于配对流程、可反复点击、结果明确。
 *
 * 连接地址不假定 loopback：mDNS 解析出的 WLAN 接口 IP、本机网卡 IPv4 与
 * loopback 一起作为候选交给 native 层逐个尝试。真机上 adbd 常绑在 WLAN IP。
 */
public final class PairingActivity extends Activity {

    private static final String TAG = "PairingActivity";

    private static final long AUTO_ACTIVATE_WAIT_MS = 8000L;
    /** 激活后轮询探测特权代理 abstract socket 的总时长与间隔。 */
    private static final long SERVICE_PROBE_TIMEOUT_MS = 7000L;
    private static final long SERVICE_PROBE_INTERVAL_MS = 400L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService probeWorker = Executors.newCachedThreadPool();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView tvServiceBadge;
    private View vStatusDot;
    private TextView tvServiceState;
    private TextView tvServiceHint;
    private TextView tvDiscover;
    private TextView tvResult;
    private EditText etConnectPort;
    private EditText etPort;
    private EditText etCode;
    private Button btnActivate;
    private Button btnPair;
    private LinearLayout cardNotif;
    private LinearLayout cardMiui;
    /** 激活按钮旁的高亮「通知栏输码」入口（容器 + 副标题，用于强调态）。 */
    private View cardPairNotif;
    private TextView tvPairNotifSub;
    private boolean pairEntryEmphasized;

    private int pairingPort = -1;
    private int connectPort = -1;
    private int lastConnectPort = -1;
    private boolean working;
    private boolean paired;
    private boolean notificationEnabled;
    private boolean serviceActive;
    /** 配对成功后等新的连接服务端口，就绪则自动激活一次（按钮始终可手动点）。 */
    private boolean pendingAutoActivate;
    private Runnable autoActivateTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);

        tvServiceBadge = findViewById(R.id.tvServiceBadge);
        vStatusDot = findViewById(R.id.vStatusDot);
        tvServiceState = findViewById(R.id.tvServiceState);
        tvServiceHint = findViewById(R.id.tvServiceHint);
        tvDiscover = findViewById(R.id.tvDiscover);
        tvResult = findViewById(R.id.tvResult);
        etConnectPort = findViewById(R.id.etConnectPort);
        etPort = findViewById(R.id.etPort);
        etCode = findViewById(R.id.etCode);
        btnActivate = findViewById(R.id.btnActivate);
        btnPair = findViewById(R.id.btnPair);
        cardNotif = findViewById(R.id.cardNotif);
        cardMiui = findViewById(R.id.cardMiui);
        cardPairNotif = findViewById(R.id.cardPairNotif);
        tvPairNotifSub = findViewById(R.id.tvPairNotifSub);

        btnActivate.setOnClickListener(v -> onActivateClicked());
        btnPair.setOnClickListener(v -> onPairClicked());
        // 高亮输码入口：紧邻激活按钮，点击立即把常驻输码通知置顶并给出引导。
        cardPairNotif.setOnClickListener(v -> onPairNotifClicked());
        findViewById(R.id.btnPairNotif).setOnClickListener(v -> onPairNotifClicked());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshServiceState());
        findViewById(R.id.btnDevOptions).setOnClickListener(v -> openDeveloperOptions());
        findViewById(R.id.btnNotifSettings).setOnClickListener(v -> openNotificationSettings());
        findViewById(R.id.btnBattery).setOnClickListener(v -> openBatterySettings());
        findViewById(R.id.btnNotification).setOnClickListener(v -> onPairNotifClicked());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        if (RomCompat.isMiuiLike()) {
            cardMiui.setVisibility(View.VISIBLE);
        }

        // Android 13+ 恒需要运行时请求通知权限。
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            } catch (Throwable t) {
                Log.w(TAG, "request POST_NOTIFICATIONS failed", t);
            }
        }

        notificationEnabled = isNotificationEnabled();
        syncNotificationCard();
        // 通知可用时立即拉起前台服务，让用户离开本页前通知栏就有常驻输码通知。
        if (notificationEnabled) {
            startPairingService(false);
        }

        try {
            MdnsProbe.get(this).start(this::onPorts);
        } catch (Throwable t) {
            Log.w(TAG, "mDNS start failed", t);
            tvDiscover.setText(R.string.pairing_mdns_failed);
        }
        applyCachedPorts();
        refreshServiceState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = isNotificationEnabled();
        if (enabled != notificationEnabled) {
            notificationEnabled = enabled;
            syncNotificationCard();
            if (enabled) {
                startPairingService(false);
            }
        }
        applyCachedPorts();
        refreshServiceState();
    }

    @Override
    protected void onDestroy() {
        cancelAutoActivateTimeout();
        try {
            MdnsProbe.get(this).clearCallback();
        } catch (Throwable ignored) {
        }
        worker.shutdownNow();
        probeWorker.shutdownNow();
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

    // ------------------------------------------------------------ 服务状态

    /**
     * 只探测 abstract socket 是否被监听（连上即代表特权代理进程在跑），不做
     * 完整握手，几乎瞬时。放在独立线程池，绝不阻塞激活/配对的 worker，也不卡 UI。
     */
    private void refreshServiceState() {
        probeWorker.execute(() -> {
            final boolean active = isServiceUp();
            ui(() -> applyServiceState(active));
        });
    }

    /** 同步探测 abstract socket 是否被监听（联上即代表代理在跑），可在 worker 线程调用。 */
    private boolean isServiceUp() {
        try {
            LocalSocket s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(
                        BrokerClient.socketName(getApplicationContext()),
                        LocalSocketAddress.Namespace.ABSTRACT));
                return true;
            } finally {
                try { s.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 投递启动命令后轮询等待代理真正常驻。命令投递成功不等于服务已起来，
     * 只有 abstract socket 被监听才算激活成功，避免出现「假成功」。
     */
    private boolean awaitServiceUp() {
        long deadline = System.currentTimeMillis() + SERVICE_PROBE_TIMEOUT_MS;
        do {
            if (isServiceUp()) {
                return true;
            }
            try {
                Thread.sleep(SERVICE_PROBE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < deadline);
        return isServiceUp();
    }

    private void applyServiceState(boolean active) {
        serviceActive = active;
        emphasizePairEntry(!active);
        if (active) {
            tvServiceBadge.setText(R.string.pairing_badge_active);
            vStatusDot.setBackgroundResource(R.drawable.bg_status_dot_ok);
            tvServiceState.setText(R.string.pairing_state_on);
            tvServiceHint.setText(R.string.pairing_state_hint_on);
            btnActivate.setText(R.string.pairing_btn_activate_again);
        } else {
            tvServiceBadge.setText(R.string.pairing_badge_inactive);
            vStatusDot.setBackgroundResource(R.drawable.bg_status_dot);
            tvServiceState.setText(R.string.pairing_state_off);
            tvServiceHint.setText(R.string.pairing_state_hint_off);
            btnActivate.setText(R.string.pairing_btn_activate);
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
     * 高亮输码入口：立即重启常驻通知通道，把带 RemoteInput 的输码通知顶到通知栏，
     * 并在结果条给出「先去无线调试点配对码 → 下拉通知栏输码」的明确引导。
     * 通知被关闭时直接引导去开权限，而不是静默失败。
     */
    private void onPairNotifClicked() {
        if (!isNotificationEnabled()) {
            showResult(getString(R.string.pairing_notif_blocked), RESULT_ERR);
            toast(getString(R.string.pairing_notif_mode_unavailable));
            openNotificationSettings();
            return;
        }
        startPairingService(true);
        showResult(getString(R.string.pairing_notif_howto), RESULT_INFO);
    }

    /** 未激活/激活失败时把输码入口切成强调态；服务已运行时恢复常态。 */
    private void emphasizePairEntry(boolean emphasize) {
        if (pairEntryEmphasized == emphasize) {
            return;
        }
        pairEntryEmphasized = emphasize;
        if (cardPairNotif != null) {
            cardPairNotif.setBackgroundResource(emphasize
                    ? R.drawable.bg_pair_notif_urgent : R.drawable.bg_pair_notif);
        }
        if (tvPairNotifSub != null) {
            tvPairNotifSub.setText(emphasize
                    ? R.string.pairing_notif_sub_urgent : R.string.pairing_notif_sub);
        }
    }

    /** 通知栏输码通道：进入页面即启动；被系统禁止后台启动前台服务时降级普通 startService。 */
    private void startPairingService(boolean userInitiated) {
        Intent intent = WirelessPairingService.startIntent(this);
        try {
            startForegroundService(intent);
            if (userInitiated) {
                toast(getString(R.string.pairing_notif_mode_started));
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed", t);
            boolean startNotAllowed = t.getClass().getName()
                    .contains("ForegroundServiceStartNotAllowedException");
            if (startNotAllowed) {
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

        // 连接端口自动回填到激活输入框（用户没在改时）。
        if (c > 0 && etConnectPort.getText().toString().trim().isEmpty()) {
            etConnectPort.setText(String.valueOf(c));
            etConnectPort.setSelection(etConnectPort.getText().length());
        }
        if (p > 0 && etPort.getText().toString().trim().isEmpty()) {
            etPort.setText(String.valueOf(p));
            etPort.setSelection(etPort.getText().length());
        }
        if (c > 0) {
            tvDiscover.setText(getString(R.string.pairing_discover_found, c));
        } else {
            tvDiscover.setText(R.string.pairing_discover_searching);
        }

        // 配对成功后系统可能重分配连接端口：拿到新端口自动激活一次。
        if (pendingAutoActivate && c > 0 && !working) {
            pendingAutoActivate = false;
            cancelAutoActivateTimeout();
            doActivate(c);
        }
    }

    // ------------------------------------------------------------ 主 CTA：激活服务

    /**
     * 激活服务：用已登记的公钥连接连接端口并启动特权代理。
     * 端口优先取输入框（自动发现回填或用户手填），否则退回缓存端口。
     * 随时可点、可重复；失败只更新结果条，不改变按钮可用性。
     */
    private void onActivateClicked() {
        if (working) {
            return;
        }
        int port = parsePort(etConnectPort);
        if (port <= 0) {
            port = connectPort > 0 ? connectPort : lastConnectPort;
        }
        if (port <= 0) {
            showResult(getString(R.string.pairing_activate_need_port), RESULT_ERR);
            return;
        }
        pendingAutoActivate = false;
        cancelAutoActivateTimeout();
        doActivate(port);
    }

    private void doActivate(int port) {
        if (working || isFinishing() || isDestroyed()) {
            return;
        }
        working = true;
        setWorking(true);
        showResult(getString(R.string.pairing_activating), RESULT_INFO);
        final int finalPort = port;
        final String host = MdnsProbe.get(this).resolvedHost();
        final long startedAt = System.currentTimeMillis();
        worker.execute(() -> {
            String raw = WirelessRunner.start(PairingActivity.this, finalPort, host);
            // 命令投递后实测代理是否常驻；这是激活成功的唯一判据。
            final boolean up = awaitServiceUp();
            ui(() -> onActivateResult(raw, up, finalPort, host,
                    System.currentTimeMillis() - startedAt));
        });
    }

    private void onActivateResult(String raw, boolean serviceUp, int port,
                                  String host, long durationMs) {
        working = false;
        setWorking(false);
        JSONObject j = parse(raw);
        String code = j.optString("code", "");
        String stage = j.optString("stage", "");
        String message = j.optString("message", "");
        String output = j.optString("output", "").trim();
        // native 侧已把失败行单独结构化带回；即使启动日志被通道时序截断，
        // 完整错误原因也不会丢。补进 output 供落库与诊断摘要共用。
        String failure = j.optString("failureReason", "").trim();
        if (!failure.isEmpty() && !output.contains(failure)) {
            output = output.isEmpty() ? failure : output + "\n" + failure;
        }

        if (serviceUp) {
            paired = true;
            emphasizePairEntry(false);
            showResult(getString(R.string.pairing_activate_ok), RESULT_OK);
            applyServiceState(true);
            ActivationLog.record(this, true, port, host, "",
                    getString(R.string.pairing_activate_ok), output, durationMs);
            return;
        }

        // 结果条只显示人话结论 + 完整原因 + 过程要点；raw 内部字段不再外泄，
        // 完整启动日志（含 errno）另行写入「执行日志」，不再被字符截断吃掉。
        String summary = ActivationLog.summarize(this, output, false);
        String lead = friendlyMessage(code, stage, message);
        if (!lead.isEmpty() && !WirelessRunner.STAGE_STARTED.equals(stage)) {
            summary = lead + "\n" + summary;
        }
        ActivationLog.record(this, false, port, host, code, summary, output, durationMs);

        StringBuilder sb = new StringBuilder(summary);
        sb.append("\n\n").append(getString(R.string.pairing_activate_failed_hint));
        sb.append("\n").append(getString(R.string.pairing_diag_view_log));
        showResult(sb.toString(), RESULT_ERR);
        emphasizePairEntry(true);
        applyServiceState(false);
    }

    // ------------------------------------------------------------ 备用：页面内配对

    private void onPairClicked() {
        if (working) {
            return;
        }
        String code = etCode.getText().toString().trim();
        if (!code.matches("\\d{6}")) {
            showResult(getString(R.string.pairing_bad_code), RESULT_ERR);
            return;
        }
        int port = parsePort(etPort);
        if (port <= 0) {
            port = pairingPort;
        }
        if (port <= 0) {
            showResult(getString(R.string.pairing_bad_port), RESULT_ERR);
            return;
        }
        working = true;
        setWorking(true);
        showResult(getString(R.string.pairing_working), RESULT_INFO);
        final int finalPort = port;
        final String finalCode = code;
        final String host = MdnsProbe.get(this).resolvedHost();
        worker.execute(() -> {
            String raw = WirelessRunner.pair(PairingActivity.this, finalPort, finalCode, host);
            ui(() -> onPairResult(raw));
        });
    }

    private void onPairResult(String raw) {
        working = false;
        setWorking(false);
        JSONObject j = parse(raw);
        if (j.optBoolean("ok", false) && WirelessRunner.STAGE_PAIRED.equals(j.optString("stage", ""))) {
            paired = true;
            showResult(getString(R.string.pairing_paired_activate_hint), RESULT_OK);
            // 配对成功后连接端口可能重分配：重启发现，拿到新端口自动激活一次；
            // 无论自动是否成功，激活按钮始终可手动点。
            pendingAutoActivate = true;
            try {
                MdnsProbe.get(this).restart(this::onPorts);
            } catch (Throwable t) {
                Log.w(TAG, "restart discovery failed", t);
            }
            scheduleAutoActivateTimeout();
            return;
        }
        showResult(friendlyMessage(
                j.optString("code", ""), j.optString("stage", ""), j.optString("message", "")),
                RESULT_ERR);
    }

    private void scheduleAutoActivateTimeout() {
        cancelAutoActivateTimeout();
        autoActivateTimeout = () -> {
            if (!pendingAutoActivate || isFinishing() || isDestroyed() || working) {
                return;
            }
            pendingAutoActivate = false;
            // 没等到新端口：把当前端口填入输入框，交给用户手动点激活按钮。
            int port = connectPort > 0 ? connectPort : lastConnectPort;
            if (port > 0 && etConnectPort.getText().toString().trim().isEmpty()) {
                etConnectPort.setText(String.valueOf(port));
            }
            showResult(getString(R.string.pairing_paired_activate_hint), RESULT_INFO);
        };
        uiHandler.postDelayed(autoActivateTimeout, AUTO_ACTIVATE_WAIT_MS);
    }

    private void cancelAutoActivateTimeout() {
        if (autoActivateTimeout != null) {
            uiHandler.removeCallbacks(autoActivateTimeout);
            autoActivateTimeout = null;
        }
    }

    // ------------------------------------------------------------ 结果条与工具

    private static final int RESULT_INFO = 0;
    private static final int RESULT_OK = 1;
    private static final int RESULT_ERR = 2;

    private void showResult(String text, int kind) {
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(text);
        if (kind == RESULT_OK) {
            tvResult.setBackgroundResource(R.drawable.bg_result_strip_ok);
            tvResult.setTextColor(getResources().getColor(R.color.success, getTheme()));
        } else if (kind == RESULT_ERR) {
            tvResult.setBackgroundResource(R.drawable.bg_result_strip_err);
            tvResult.setTextColor(getResources().getColor(R.color.danger, getTheme()));
        } else {
            tvResult.setBackgroundResource(R.drawable.bg_result_strip);
            tvResult.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        }
    }

    private void setWorking(boolean on) {
        btnActivate.setEnabled(!on);
        btnPair.setEnabled(!on);
    }

    private int parsePort(EditText field) {
        try {
            int p = Integer.parseInt(field.getText().toString().trim());
            if (p >= 1 && p <= 65535) {
                return p;
            }
        } catch (NumberFormatException ignored) {
        }
        return -1;
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
                // 不再把 started / pairing 这类内部阶段名原样显示给用户
                return message;
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
