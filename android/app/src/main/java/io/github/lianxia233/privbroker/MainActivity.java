package io.github.lianxia233.privbroker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 用户主动点开图标时才出现的主界面；外部安装/卸载意图由 SilentInstallActivity 接管。 */
public final class MainActivity extends Activity {
    private static final int REQ_PICK = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int REQ_NOTIF_PAIR = 1003;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView tvStatusTitle;
    private TextView tvStatusHint;
    private TextView tvStatusBadge;
    private Button btnPermission;
    private Switch swConfirm;
    private Switch swDeleteAfter;
    private Switch swDowngrade;

    private JSONArray cachedAllowList = new JSONArray();
    private String pendingPlan;
    private String lastPackage;
    private String installLabel = "安装包";
    private String currentState = "UNAVAILABLE";
    private boolean syncingPrefs;
    private boolean resumed;
    private boolean awaitingPorts;
    private String pendingCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatusTitle = findViewById(R.id.tvStatusTitle);
        tvStatusHint = findViewById(R.id.tvStatusHint);
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        btnPermission = findViewById(R.id.btnPermission);
        swConfirm = findViewById(R.id.swConfirm);
        swDeleteAfter = findViewById(R.id.swDeleteAfter);
        swDowngrade = findViewById(R.id.swDowngrade);

        BrokerBridge.setProgressListener((stage, index, total) ->
                ui(() -> log("进度 " + index + "/" + total + " · " + stage)));
        findViewById(R.id.btnCheck).setOnClickListener(v -> refreshStatus());
        btnPermission.setOnClickListener(v -> onPermissionAction());
        findViewById(R.id.btnInstall).setOnClickListener(v -> pickFile());
        // 卸载改为读取应用列表选择：可搜索、可按系统 / 用户应用筛选，
        // 不再要求用户手输包名。
        findViewById(R.id.btnUninstall).setOnClickListener(v ->
                startActivity(new Intent(this, AppPickerActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnLogs).setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.btnLaunch).setOnClickListener(v -> launchLast());
        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));

        CompoundButton.OnCheckedChangeListener persist = (b, checked) -> {
            if (!syncingPrefs) {
                savePrefs();
            }
        };
        swConfirm.setOnCheckedChangeListener(persist);
        swDeleteAfter.setOnCheckedChangeListener(persist);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        Notifier.ensureChannels(this);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshStatus();
        // 用户可能刚去开发者选项点开了配对弹窗再切回来：端口已由探测单例缓存，
        // 直接接手，不必重新搜索。
        if (awaitingPorts) {
            int port = MdnsProbe.get(this).pairingPort();
            if (port > 0) {
                onPorts(port, MdnsProbe.get(this).connectPort());
            }
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onDestroy() {
        // 探测单例是静态的，必须主动断开回调，否则持有已销毁的 Activity。
        MdnsProbe.get(this).clearCallback();
        awaitingPorts = false;
        pendingCode = null;
        BrokerBridge.setProgressListener(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 状态

    private void refreshStatus() {
        runAsync("status", "{}", r -> {
            if (!r.optBoolean("ok")) {
                showError(r);
                return;
            }
            JSONObject d = r.optJSONObject("data");
            if (d == null) {
                return;
            }
            currentState = d.optString("state", "UNAVAILABLE");
            applyStatusBadge(currentState);
            JSONObject g = d.optJSONObject("guidance");
            tvStatusTitle.setText(g == null ? currentState : g.optString("title"));
            String hint = g == null ? "" : (g.optString("title") + " · " + g.optString("hint"));
            tvStatusHint.setText(hint);
            String action = g == null ? "" : g.optString("action");
            btnPermission.setText(action.isEmpty() ? getString(R.string.btn_request) : action);
            btnPermission.setEnabled(!action.isEmpty());
            JSONObject cfg = d.optJSONObject("config");
            if (cfg != null) {
                JSONArray al = cfg.optJSONArray("allowList");
                cachedAllowList = al == null ? new JSONArray() : al;
                applyConfigToUi(cfg);
            }
        });
    }

    private void applyStatusBadge(String state) {
        boolean ready = "AUTHORIZED".equals(state);
        tvStatusBadge.setText(getString(ready
                ? R.string.status_badge_ready : R.string.status_badge_attention));
        tvStatusBadge.setTextColor(getResources().getColor(
                ready ? R.color.success : R.color.warning, getTheme()));
        tvStatusBadge.setBackgroundResource(ready
                ? R.drawable.bg_badge_ok : R.drawable.bg_badge_warn);
    }

    private void applyConfigToUi(JSONObject cfg) {
        syncingPrefs = true;
        swConfirm.setChecked(cfg.optBoolean("confirmBeforeInstall", false));
        swDeleteAfter.setChecked(cfg.optBoolean("deleteApkAfterInstall", false));
        syncingPrefs = false;
    }

    private void onPermissionAction() {
        showWirelessGuide();
    }

    /** 无电脑自助启用：开发者选项 → 无线调试 → 应用内输入 6 位配对码。 */
    private void showWirelessGuide() {
        boolean miui = RomCompat.isMiuiLike();
        new AlertDialog.Builder(this)
                .setTitle(R.string.wireless_guide_title)
                .setMessage(miui ? R.string.wireless_guide_text_miui : R.string.wireless_guide_text)
                .setPositiveButton(R.string.wireless_guide_start,
                        (d, w) -> beginWirelessPairing())
                .setNeutralButton(R.string.wireless_guide_open_settings,
                        (d, w) -> openWirelessAdbSettings())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void beginWirelessPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast(getString(R.string.wireless_requires_android11));
            return;
        }
        // 应用内输码不依赖通知，先把发现跑起来，再顺带申请通知权限。
        startPairingFlow();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF_PAIR);
        }
    }

    /**
     * 应用内输码主路径：先起 mDNS 探测，配对服务就绪后直接弹输入框。
     * 通知栏输码并行尝试，但整条配对能力不依赖通知是否显示——
     * 部分 ROM 会静默隐藏前台服务通知，通知不能作为唯一入口。
     */
    private void startPairingFlow() {
        awaitingPorts = true;
        pendingCode = null;
        MdnsProbe.get(this).restart(this::onPorts);
        toast(getString(R.string.wireless_searching_toast));
        startPairingNotificationService();
        scheduleSearchHint();
    }

    private void startPairingNotificationService() {
        if (!notificationsEnabled()) {
            return;
        }
        try {
            startForegroundService(WirelessPairingService.startIntent(this));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 用户很可能还没点「使用配对码配对设备」，此时配对服务根本不存在，
     * mDNS 永远等不到。等 10 秒仍无端口就主动把话说明白，而不是干等。
     */
    private void scheduleSearchHint() {
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (!awaitingPorts || isFinishing() || isDestroyed() || !resumed) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.wireless_no_service_title)
                    .setMessage(R.string.wireless_no_service_text)
                    .setPositiveButton(R.string.wireless_guide_open_settings,
                            (d, w) -> openWirelessAdbSettings())
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
        }, 10000);
    }

    private void onPorts(int pairingPort, int connectPort) {
        // 用户去系统设置开配对弹窗期间本 Activity 在后台，此时不能弹窗：
        // 端口留在 MdnsProbe 单例里，回到前台由 onResume 接手。
        if (isFinishing() || isDestroyed() || !resumed) {
            return;
        }
        if (awaitingPorts && pairingPort > 0) {
            awaitingPorts = false;
            showCodeInput(pairingPort, connectPort);
            return;
        }
        // 已输码但在等连接服务端口：端口就绪后自动继续
        if (pendingCode != null && pairingPort > 0 && connectPort > 0) {
            String code = pendingCode;
            pendingCode = null;
            runWirelessPairing(code, pairingPort, connectPort);
        }
    }

    private void showCodeInput(int pairingPort, int connectPort) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.wireless_code_hint);
        input.setTextSize(20);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.wireless_input_dialog_title)
                .setMessage(RomCompat.miuiNotificationStyleLikely()
                        ? R.string.wireless_input_dialog_text_miui
                        : R.string.wireless_input_dialog_text)
                .setView(input)
                .setPositiveButton(R.string.wireless_input_do, (d, w) -> {
                    String code = input.getText().toString().trim();
                    if (!code.matches("\\d{6}")) {
                        toast(getString(R.string.wireless_bad_code));
                        return;
                    }
                    if (connectPort <= 0) {
                        pendingCode = code;
                        toast(getString(R.string.wireless_waiting_connect));
                        return;
                    }
                    runWirelessPairing(code, pairingPort, connectPort);
                })
                .setNeutralButton(R.string.wireless_guide_open_settings,
                        (d, w) -> openWirelessAdbSettings())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void runWirelessPairing(final String code, final int pairingPort, final int connectPort) {
        toast(getString(R.string.wireless_pairing_now));
        worker.execute(() -> {
            String raw;
            try {
                JSONObject args = new JSONObject();
                args.put("host", "127.0.0.1");
                args.put("pairingPort", pairingPort);
                args.put("pairingCode", code);
                args.put("connectPort", connectPort);
                args.put("keyPath", new java.io.File(getFilesDir(), "adb_key.pk8").getAbsolutePath());
                args.put("apkPath", getApplicationInfo().sourceDir);
                args.put("nativeLibDir", getApplicationInfo().nativeLibraryDir);
                args.put("token", BrokerClient.token(this));
                args.put("client", BrokerClient.clientId(this));
                args.put("endpoint", BrokerClient.endpoint(this));
                WirelessCore.ensureLoaded(null);
                raw = WirelessCore.run(args.toString());
            } catch (Throwable t) {
                raw = "{\"ok\":false,\"stage\":\"bridge\",\"code\":\"BRIDGE\",\"message\":"
                        + JSONObject.quote(String.valueOf(t)) + "}";
            }
            final String f = raw;
            ui(() -> onPairingResult(f));
        });
    }

    private void onPairingResult(String raw) {
        boolean ok = false;
        String stage = "";
        String code = "";
        String message = "";
        try {
            JSONObject j = new JSONObject(raw);
            ok = j.optBoolean("ok", false);
            stage = j.optString("stage", "");
            code = j.optString("code", "");
            message = j.optString("message", "");
        } catch (Throwable t) {
            message = getString(R.string.wireless_result_parse_failed);
        }
        if (ok) {
            awaitingPorts = false;
            pendingCode = null;
            MdnsProbe.get(this).stop();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.wireless_success_title)
                    .setMessage(R.string.wireless_success_dialog_text)
                    .setPositiveButton(android.R.string.ok, (d, w) -> refreshStatus())
                    .show();
            return;
        }
        String detail = stage.isEmpty() ? message : (stage + ": " + message);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wireless_fail_title)
                .setMessage(getString(R.string.wireless_fail_dialog_text, code, detail))
                .setPositiveButton(R.string.wireless_retry, (d, w) -> startPairingFlow())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF_PAIR) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 发现流程已经在跑，这里只补上通知通道，不重复启动
                startPairingNotificationService();
            } else {
                // 通知被拒不影响应用内输码，只提示一次
                toast(getString(R.string.wireless_notif_denied_still_ok));
            }
        }
    }

    private boolean notificationsEnabled() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = nm.getNotificationChannel(WirelessPairingService.CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private void openWirelessAdbSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.wireless_settings_unavailable));
        }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ------------------------------------------------------------------ 安装

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        List<String> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                Uri u = clip.getItemAt(k).getUri();
                uris.add(keepPermission(u));
            }
        } else if (data.getData() != null) {
            uris.add(keepPermission(data.getData()));
        }
        if (!uris.isEmpty()) {
            startInstall(uris);
        }
    }

    private String keepPermission(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        return u.toString();
    }

    private void startInstall(List<String> uris) {
        installLabel = displayName(Uri.parse(uris.get(0)));
        JSONObject a = new JSONObject();
        try {
            a.put("uris", new JSONArray(uris));
            // 默认静默：仅当用户主动打开「安装前确认」开关时才走确认流程。
            a.put("options", buildOptions(!swConfirm.isChecked()));
        } catch (JSONException e) {
            log("参数构造失败: " + e);
            return;
        }
        log("准备安装，来源数量: " + uris.size());
        runAsync("prepareInstall", a.toString(), r -> {
            if (!r.optBoolean("ok")) {
                showError(r);
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject plan = d == null ? null : d.optJSONObject("plan");
            JSONObject summary = d == null ? null : d.optJSONObject("summary");
            if (plan == null) {
                log("错误: 未生成安装计划");
                return;
            }
            pendingPlan = plan.toString();
            if (summary != null) {
                String p = summary.optString("package", "");
                if (!p.isEmpty() && !"null".equals(p)) {
                    lastPackage = p;
                }
                log("计划: " + summary.optString("totalSizeHuman", "?")
                        + " / " + summary.optInt("parts") + " 个分片");
                if (summary.optBoolean("requiresConfirmation")) {
                    confirmInstall(summary);
                    return;
                }
            }
            commitInstall();
        });
    }

    private void confirmInstall(JSONObject summary) {
        StringBuilder msg = new StringBuilder();
        String p = summary.optString("package", "");
        if (!p.isEmpty() && !"null".equals(p)) {
            msg.append("包名: ").append(p).append('\n');
        }
        String v = summary.optString("version", "");
        if (!v.isEmpty() && !"null".equals(v)) {
            msg.append("版本: ").append(v).append('\n');
        }
        msg.append("大小: ").append(summary.optString("totalSizeHuman", "?")).append('\n');
        msg.append("分片数: ").append(summary.optInt("parts")).append('\n');
        JSONArray names = summary.optJSONArray("splitNames");
        if (names != null) {
            msg.append("分片: ");
            for (int i = 0; i < names.length(); i++) {
                msg.append(names.optString(i)).append(i + 1 < names.length() ? ", " : "");
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_confirm_title)
                .setMessage(msg.toString())
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm_btn, (d, w) -> commitInstall())
                .show();
    }

    private void commitInstall() {
        if (pendingPlan == null) {
            return;
        }
        JSONObject a = new JSONObject();
        try {
            a.put("plan", new JSONObject(pendingPlan));
            a.put("options", buildOptions(true));
            a.put("context", new JSONObject().put("label", installLabel).put("source", "ui"));
        } catch (JSONException e) {
            log("提交参数失败: " + e);
            return;
        }
        log("正在执行安装…");
        runAsync("commitInstall", a.toString(), r -> {
            if (!r.optBoolean("ok")) {
                showError(r);
                Notifier.finish(this, installLabel, false, false, errorText(r), "");
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject result = d == null ? null : d.optJSONObject("result");
            JSONObject outcome = result == null ? null : result.optJSONObject("outcome");
            boolean ok = outcome != null && outcome.optBoolean("ok");
            String raw = outcome == null ? "" : outcome.optString("raw", "");
            log((ok ? "安装成功" : "安装失败: " + (outcome == null ? "?" : outcome.optString("code"))));
            if (!raw.isEmpty()) {
                log(raw.trim());
            }
            Notifier.finish(this, installLabel, false, ok, raw.trim(), ok ? lastPackage : "");
            pendingPlan = null;
        });
    }

    // ------------------------------------------------------------------ 启动 / 复制

    private void launchLast() {
        if (lastPackage == null) {
            toast(getString(R.string.no_launcher_package));
            return;
        }
        JSONObject a = new JSONObject();
        try {
            a.put("package", lastPackage);
        } catch (JSONException e) {
            return;
        }
        runAsync("launch", a.toString(), r -> {
            if (!r.optBoolean("ok")) {
                showError(r);
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject res = d == null ? null : d.optJSONObject("result");
            log(res != null && res.optBoolean("ok") ? "已启动应用" : "启动失败");
        });
    }

    // ------------------------------------------------------------------ 配置

    private JSONObject buildOptions(boolean skipConfirm) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("replace", true);
        o.put("grantRuntimePermissions", true);
        o.put("allowDowngrade", swDowngrade.isChecked());
        o.put("deleteSourceAfter", swDeleteAfter.isChecked());
        o.put("skipConfirm", skipConfirm);
        return o;
    }

    private void savePrefs() {
        try {
            JSONObject cfg = new JSONObject();
            cfg.put("confirmBeforeInstall", swConfirm.isChecked());
            cfg.put("deleteApkAfterInstall", swDeleteAfter.isChecked());
            cfg.put("allowList", cachedAllowList);
            runAsync("setConfig", new JSONObject().put("config", cfg).toString(), r -> {
                if (!r.optBoolean("ok")) {
                    showError(r);
                }
            });
        } catch (JSONException ignored) {
        }
    }

    // ------------------------------------------------------------------ 工具

    private void runAsync(String method, String args, Consumer<JSONObject> cb) {
        worker.execute(() -> {
            String raw;
            try {
                raw = RustCore.dispatch(method, args);
            } catch (Throwable t) {
                raw = "{\"ok\":false,\"error\":{\"kind\":\"JNI\",\"message\":"
                        + JSONObject.quote(String.valueOf(t)) + "}}";
            }
            JSONObject o;
            try {
                o = new JSONObject(raw);
            } catch (Exception e) {
                o = new JSONObject();
            }
            final JSONObject f = o;
            ui(() -> cb.accept(f));
        });
    }

    private String errorText(JSONObject r) {
        JSONObject e = r.optJSONObject("error");
        return e == null ? "未知错误" : e.optString("message", "未知错误");
    }

    private void showError(JSONObject r) {
        JSONObject e = r.optJSONObject("error");
        String kind = e == null ? "?" : e.optString("kind", "?");
        log("错误 [" + kind + "]: " + errorText(r));
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (n >= 0 && !c.isNull(n)) {
                    String s = c.getString(n);
                    if (s != null && !s.isEmpty()) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String seg = u.getLastPathSegment();
        return seg == null || seg.isEmpty() ? "安装包" : seg;
    }

    /**
     * 主界面不再展示「本次执行日志」——完整的安装记录由独立的安装日志页负责。
     * 这里的操作提示只写 logcat，便于排查，不占用界面。
     */
    private void log(String line) {
        Log.i("MainActivity", line);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void ui(Runnable r) {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(r);
        }
    }
}
