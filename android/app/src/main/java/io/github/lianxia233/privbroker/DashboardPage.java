package io.github.lianxia233.privbroker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 仪表盘页：FlClash Dashboard 的卡片语言搬到本应用。
 *
 * <p>布局：状态 hero 卡（状态胶囊 + 大标题 + 圆形主操作）、安装/卸载统计
 * （迷你面积图）、安装模式三选一、两个设置开关、服务状态与缓存、安装/卸载
 * 操作、最近安装。全部数据来自 Rust 核心的 {@code status} / {@code logList}
 * 与 Java 桥接层的 {@code brokerState}。</p>
 */
final class DashboardPage {
    private static final int REQ_PICK = 1001;
    private static final String PREFS = "privilege_broker_ui";
    private static final String KEY_LAST_PACKAGE = "last_package";

    private final Activity a;
    private final View root;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView tvChip;
    private TextView tvTitle;
    private TextView tvHint;
    private TextView tvUid;
    private TextView tvVersion;
    private TextView tvAuth;
    private TextView tvInstallCount;
    private TextView tvDeleteCount;
    private TextView tvInstallToday;
    private TextView tvDeleteToday;
    private TextView tvBrokerState;
    private TextView tvHandshake;
    private TextView tvCacheSize;
    private View dotBroker;
    private View dotHandshake;
    private ProgressBar cacheBar;
    private LinearLayout recentList;
    private Sparkline sparkInstall;
    private Sparkline sparkDelete;
    private SegmentedControl segMode;
    private PrismSwitch swDelete;
    private PrismSwitch swFloating;
    private View hero;

    private Runnable openLogs;
    private boolean binding;
    private String state = "UNAVAILABLE";
    private JSONObject config;
    private String pendingPlan;
    private String installLabel = "安装包";

    DashboardPage(Activity a, View root) {
        this.a = a;
        this.root = root;
        bind();
    }

    void setOnOpenLogs(Runnable r) {
        openLogs = r;
    }

    private void bind() {
        tvChip = root.findViewById(R.id.tvStatusChip);
        tvTitle = root.findViewById(R.id.tvStatusTitle);
        tvHint = root.findViewById(R.id.tvStatusHint);
        tvUid = root.findViewById(R.id.tvStatusUid);
        tvVersion = root.findViewById(R.id.tvStatusVersion);
        tvAuth = root.findViewById(R.id.tvStatusAuth);
        tvInstallCount = root.findViewById(R.id.tvInstallCount);
        tvDeleteCount = root.findViewById(R.id.tvDeleteCount);
        tvInstallToday = root.findViewById(R.id.tvInstallToday);
        tvDeleteToday = root.findViewById(R.id.tvDeleteToday);
        tvBrokerState = root.findViewById(R.id.tvBrokerState);
        tvHandshake = root.findViewById(R.id.tvHandshake);
        tvCacheSize = root.findViewById(R.id.tvCacheSize);
        dotBroker = root.findViewById(R.id.dotBroker);
        dotHandshake = root.findViewById(R.id.dotHandshake);
        cacheBar = root.findViewById(R.id.cacheBar);
        recentList = root.findViewById(R.id.recentList);
        sparkInstall = root.findViewById(R.id.sparkInstall);
        sparkDelete = root.findViewById(R.id.sparkDelete);
        segMode = root.findViewById(R.id.segMode);
        swDelete = root.findViewById(R.id.swDeleteAfter);
        swFloating = root.findViewById(R.id.swFloating);
        hero = root.findViewById(R.id.btnHero);

        Prism.padTopForStatusBar(root.findViewById(R.id.dashContent));

        // 卡片与按钮的按压手感
        Prism.press(hero, this::onHero);
        Prism.press(root.findViewById(R.id.btnInstall), this::pickFile);
        Prism.press(root.findViewById(R.id.btnUninstall), () ->
                a.startActivity(new Intent(a, AppPickerActivity.class)));
        Prism.press(root.findViewById(R.id.btnLaunch), this::launchLast);
        Prism.press(root.findViewById(R.id.btnDashRefresh), this::refresh);
        Prism.press(root.findViewById(R.id.btnViewAll), () -> {
            if (openLogs != null) {
                openLogs.run();
            }
        });

        segMode.setSegments(
                a.getString(R.string.mode_silent),
                a.getString(R.string.mode_confirm),
                a.getString(R.string.mode_system));
        segMode.setListener(index -> {
            if (binding) {
                return;
            }
            JSONObject patch = new JSONObject();
            try {
                patch.put("confirmBeforeInstall", index == 1);
                patch.put("autoOpenSystemInstaller", index == 2);
                if (index == 2) {
                    patch.put("fallbackToSystemInstaller", true);
                }
            } catch (Throwable ignored) {
            }
            PageBridge.saveConfig(a, patch);
        });

        swDelete.setOnCheckedChangeListener((b, checked) -> {
            if (binding) {
                return;
            }
            JSONObject patch = new JSONObject();
            try {
                patch.put("deleteApkAfterInstall", checked);
            } catch (Throwable ignored) {
            }
            PageBridge.saveConfig(a, patch);
            if (checked) {
                toast("已开启：安装成功后由特权代理以 shell 身份删除原安装包");
            }
        });

        swFloating.setOnCheckedChangeListener((b, checked) -> {
            if (binding) {
                return;
            }
            if (checked && !FloatingIndicator.canDraw(a)) {
                FloatingIndicator.ensurePermissionAsync(a, () -> {
                    if (!FloatingIndicator.canDraw(a) && !a.isFinishing()) {
                        new AlertDialog.Builder(a)
                                .setTitle(R.string.overlay_perm_title)
                                .setMessage(R.string.overlay_perm_msg)
                                .setPositiveButton(R.string.overlay_perm_grant,
                                        (d, w) -> FloatingIndicator.openPermissionSettings(a))
                                .setNegativeButton(R.string.overlay_perm_skip, null)
                                .show();
                    }
                });
            }
            JSONObject patch = new JSONObject();
            try {
                patch.put("floatingIndicator", checked);
            } catch (Throwable ignored) {
            }
            PageBridge.saveConfig(a, patch);
        });

        requestNotificationPermission();
    }

    void onResume() {
        refresh();
    }

    // ------------------------------------------------------------ 数据刷新

    void refresh() {
        PageBridge.call(a, "status", "{}", this::bindStatus);
        PageBridge.call(a, "logList", "{\"limit\":200}", this::bindLogs);
        Thread t = new Thread(() -> {
            JSONObject broker;
            try {
                broker = new JSONObject(BrokerBridge.call("brokerState", "{}"));
            } catch (Throwable e) {
                broker = new JSONObject();
            }
            final JSONObject f = broker;
            main.post(() -> {
                if (!a.isFinishing()) {
                    bindBroker(f);
                }
            });
        }, "dash-broker");
        t.setDaemon(true);
        t.start();
        Thread cache = new Thread(() -> {
            long size = cacheSize();
            main.post(() -> {
                if (!a.isFinishing()) {
                    bindCache(size);
                }
            });
        }, "dash-cache");
        cache.setDaemon(true);
        cache.start();
    }

    private void bindStatus(JSONObject r) {
        if (!r.optBoolean("ok")) {
            state = "UNKNOWN";
            chip("需要处理", false);
            tvTitle.setText(R.string.status_unknown_title);
            tvHint.setText(a.getString(R.string.status_unknown_hint,
                    errorText(r)));
            return;
        }
        JSONObject d = r.optJSONObject("data");
        if (d == null) {
            return;
        }
        state = d.optString("state", "UNAVAILABLE");
        boolean ready = "AUTHORIZED".equals(state);
        chip(ready ? "运行中" : "需要处理", ready);
        JSONObject g = d.optJSONObject("guidance");
        tvTitle.setText(g == null ? state : g.optString("title"));
        tvHint.setText(g == null ? "" : g.optString("hint"));
        config = d.optJSONObject("config");
        bindConfig(config);
    }

    private void bindConfig(JSONObject cfg) {
        if (cfg == null) {
            return;
        }
        binding = true;
        swDelete.setChecked(cfg.optBoolean("deleteApkAfterInstall", false), false);
        swFloating.setChecked(cfg.optBoolean("floatingIndicator", true), false);
        int mode = cfg.optBoolean("autoOpenSystemInstaller", false) ? 2
                : cfg.optBoolean("confirmBeforeInstall", false) ? 1 : 0;
        segMode.setSelection(mode, false);
        binding = false;
    }

    private void bindBroker(JSONObject b) {
        boolean available = b.optBoolean("available", false);
        boolean reachable = b.optBoolean("reachable", false);
        int uid = b.optInt("serverUid", -1);
        int version = b.optInt("serverVersion", 0);
        tvUid.setText(uid > 0 ? "uid " + uid : "—");
        tvVersion.setText(version > 0 ? "v" + version : "—");
        tvBrokerState.setText(available ? "运行中" : "未运行");
        tvHandshake.setText(reachable ? "可连接" : "不可达");
        dot(dotBroker, available);
        dot(dotHandshake, reachable);
        tvAuth.setText(available ? "无线调试" : "待激活");
        ((android.widget.ImageView) root.findViewById(R.id.ivHeroIcon))
                .setImageResource("AUTHORIZED".equals(state)
                        ? R.drawable.ic_action_refresh : R.drawable.ic_action_shield);
    }

    private void bindLogs(JSONObject r) {
        JSONObject d = r.optJSONObject("data");
        JSONArray entries = d == null ? null : d.optJSONArray("entries");
        if (entries == null) {
            entries = new JSONArray();
        }
        long midnight = midnight();
        int installs = 0;
        int deletes = 0;
        int todayInstall = 0;
        int todayDelete = 0;
        float[] installSeries = new float[14];
        float[] deleteSeries = new float[14];
        List<JSONObject> recent = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null || !e.optBoolean("ok")) {
                continue;
            }
            String op = e.optString("op", "install");
            long ts = e.optLong("ts", 0);
            int day = ts > 0 ? (int) ((midnight - ts) / 86_400_000L) : -1;
            if ("uninstall".equals(op)) {
                deletes++;
                if (ts >= midnight) {
                    todayDelete++;
                }
                if (day >= 0 && day < 14) {
                    deleteSeries[13 - day] += 1f;
                }
            } else if ("install".equals(op)) {
                installs++;
                if (ts >= midnight) {
                    todayInstall++;
                }
                if (day >= 0 && day < 14) {
                    installSeries[13 - day] += 1f;
                }
                if (recent.size() < 3) {
                    recent.add(e);
                }
            }
        }
        tvInstallCount.setText(String.valueOf(installs));
        tvDeleteCount.setText(String.valueOf(deletes));
        tvInstallToday.setText(a.getString(R.string.dash_today) + " " + todayInstall);
        tvDeleteToday.setText(a.getString(R.string.dash_today) + " " + todayDelete);
        sparkInstall.setData(installSeries);
        sparkDelete.setData(deleteSeries);
        renderRecent(recent);
    }

    private void bindCache(long size) {
        tvCacheSize.setText(human(size));
        // 20 MB 视作「缓存偏多」的满格，仅用于给用户一个量级感
        int percent = (int) Math.min(100, size * 100L / (20L * 1024 * 1024));
        cacheBar.setProgress(percent);
    }

    private void chip(String text, boolean ok) {
        tvChip.setText(text);
        tvChip.setTextColor(a.getColor(ok
                ? R.color.pb_on_success_container : R.color.pb_on_warning_container));
        tvChip.setBackgroundResource(ok ? R.drawable.pb_chip_ok : R.drawable.pb_chip_warn);
    }

    private void dot(View v, boolean ok) {
        v.setBackgroundResource(ok ? R.drawable.pb_dot_ok : R.drawable.pb_dot_fail);
    }

    private void renderRecent(List<JSONObject> entries) {
        recentList.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText(R.string.dash_recent_empty);
            Prism.text(empty, 13f, false, R.color.text_muted);
            empty.setPadding(0, Prism.dp(a, 8), 0, Prism.dp(a, 4));
            recentList.addView(empty);
            return;
        }
        for (JSONObject e : entries) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Prism.dp(a, 8), 0, Prism.dp(a, 8));

            View dot = new View(a);
            dot.setBackgroundResource(e.optBoolean("ok")
                    ? R.drawable.pb_dot_ok : R.drawable.pb_dot_fail);
            row.addView(dot, new LinearLayout.LayoutParams(
                    Prism.dp(a, 8), Prism.dp(a, 8)));

            LinearLayout column = new LinearLayout(a);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = Prism.dp(a, 10);

            String name = e.optString("appName", e.optString("label", "安装包"));
            TextView title = new TextView(a);
            title.setText(name);
            Prism.text(title, 15f, true, R.color.text_primary);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(title);

            TextView meta = new TextView(a);
            meta.setText(e.optString("time", "") + "  ·  " + e.optString("source", ""));
            Prism.text(meta, 12f, false, R.color.text_secondary);
            meta.setMaxLines(1);
            column.addView(meta);
            row.addView(column, clp);

            TextView chip = new TextView(a);
            chip.setText(e.optBoolean("ok") ? "成功" : "失败");
            Prism.text(chip, 11f, true,
                    e.optBoolean("ok") ? R.color.pb_success : R.color.pb_error);
            chip.setPadding(Prism.dp(a, 8), Prism.dp(a, 2), Prism.dp(a, 8), Prism.dp(a, 2));
            chip.setBackgroundResource(R.drawable.pb_chip_idle);
            row.addView(chip);
            recentList.addView(row);
        }
    }

    // ------------------------------------------------------------ 安装 / 卸载

    private void onHero() {
        boolean ready = "AUTHORIZED".equals(state);
        if (ready) {
            refresh();
        } else {
            a.startActivity(new Intent(a, PairingActivity.class));
        }
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        a.startActivityForResult(i, REQ_PICK);
    }

    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null) {
            return false;
        }
        List<String> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                uris.add(keepPermission(clip.getItemAt(k).getUri()));
            }
        } else if (data.getData() != null) {
            uris.add(keepPermission(data.getData()));
        }
        if (!uris.isEmpty()) {
            startInstall(uris);
        }
        return true;
    }

    private String keepPermission(Uri u) {
        try {
            a.getContentResolver().takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        return u.toString();
    }

    private void startInstall(List<String> uris) {
        installLabel = displayName(Uri.parse(uris.get(0)));
        // 先采集来源事实：删除链路要用它们定位原文件
        for (String u : uris) {
            try {
                SourceFacts.capture(a, Uri.parse(u), null, null);
            } catch (Throwable ignored) {
            }
        }
        JSONObject args = new JSONObject();
        try {
            args.put("uris", new JSONArray(uris));
            args.put("options", buildOptions(!swConfirmChecked()));
        } catch (Throwable e) {
            toast("参数构造失败: " + e);
            return;
        }
        PageBridge.call(a, "prepareInstall", args.toString(), r -> {
            if (!r.optBoolean("ok")) {
                toast(errorText(r));
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject plan = d == null ? null : d.optJSONObject("plan");
            JSONObject summary = d == null ? null : d.optJSONObject("summary");
            if (plan == null) {
                toast("未能生成安装计划");
                return;
            }
            pendingPlan = plan.toString();
            if (summary != null && summary.optBoolean("requiresConfirmation")) {
                confirmInstall(summary);
                return;
            }
            commitInstall();
        });
    }

    private boolean swConfirmChecked() {
        return config != null && config.optBoolean("confirmBeforeInstall", false);
    }

    private void confirmInstall(JSONObject summary) {
        StringBuilder msg = new StringBuilder();
        String pkg = summary.optString("package", "");
        if (!pkg.isEmpty() && !"null".equals(pkg)) {
            msg.append("包名: ").append(pkg).append('\n');
        }
        String version = summary.optString("version", "");
        if (!version.isEmpty() && !"null".equals(version)) {
            msg.append("版本: ").append(version).append('\n');
        }
        msg.append("大小: ").append(summary.optString("totalSizeHuman", "?")).append('\n');
        msg.append("分片数: ").append(summary.optInt("parts")).append('\n');
        new AlertDialog.Builder(a)
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
        JSONObject args = new JSONObject();
        try {
            args.put("plan", new JSONObject(pendingPlan));
            args.put("options", buildOptions(true));
            args.put("context", new JSONObject()
                    .put("label", installLabel).put("source", "ui"));
        } catch (Throwable e) {
            toast("提交参数失败: " + e);
            return;
        }
        PageBridge.call(a, "commitInstall", args.toString(), r -> {
            if (!r.optBoolean("ok")) {
                toast(errorText(r));
                Notifier.finish(a, installLabel, false, false, errorText(r), "");
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject result = d == null ? null : d.optJSONObject("result");
            JSONObject outcome = result == null ? null : result.optJSONObject("outcome");
            boolean ok = outcome != null && outcome.optBoolean("ok");
            String pkg = outcome == null ? "" : outcome.optString("package", "");
            if (ok && pkg != null && !pkg.isEmpty() && !"null".equals(pkg)) {
                a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                        .edit().putString(KEY_LAST_PACKAGE, pkg).apply();
            }
            Notifier.finish(a, installLabel, false, ok,
                    outcome == null ? "" : outcome.optString("raw", ""), ok ? pkg : "");
            toast(ok ? "安装成功" : "安装失败");
            refresh();
        });
    }

    private void launchLast() {
        String pkg = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(KEY_LAST_PACKAGE, null);
        if (pkg == null || pkg.isEmpty()) {
            toast(a.getString(R.string.no_launcher_package));
            return;
        }
        try {
            JSONObject args = new JSONObject().put("package", pkg);
            PageBridge.call(a, "launch", args.toString(), r ->
                    toast(r.optBoolean("ok") ? "已启动应用" : errorText(r)));
        } catch (Throwable ignored) {
        }
    }

    private JSONObject buildOptions(boolean skipConfirm) throws Exception {
        JSONObject o = new JSONObject();
        o.put("replace", true);
        o.put("grantRuntimePermissions", true);
        o.put("allowDowngrade", config != null && config.optBoolean("allowDowngrade", false));
        o.put("deleteSourceAfter", false);
        o.put("skipConfirm", skipConfirm);
        return o;
    }

    // ------------------------------------------------------------ 工具

    private long midnight() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private long cacheSize() {
        long total = 0;
        total += dirSize(StagedFiles.srcDir(a));
        total += dirSize(StagedFiles.shellDir(a));
        return total;
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private String displayName(Uri u) {
        try (android.database.Cursor c = a.getContentResolver()
                .query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
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

    private String errorText(JSONObject r) {
        JSONObject e = r.optJSONObject("error");
        return e == null ? "未知错误" : e.optString("message", "未知错误");
    }

    private void toast(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1002);
        }
    }
}
