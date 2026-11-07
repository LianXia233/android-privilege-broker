package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页：静默安装授权总开关、应用授权列表、安装行为开关。
 *
 * 应用列表由 Rust 核心经平台桥接读取（QUERY_ALL_PACKAGES），授权状态与决策
 * 逻辑全部由 Rust 侧负责，这里只做渲染与调用。列表支持按「全部 / 用户应用 /
 * 系统应用」筛选，已授权应用固定置顶，并用徽章明确区分系统应用。
 */
public final class SettingsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppList.Row> allApps = new ArrayList<>();
    private final List<AppList.Row> shownApps = new ArrayList<>();
    private final Set<String> allowed = new HashSet<>();
    private AppList.Kind kind = AppList.Kind.ALL;

    private LinearLayout list;
    private LinearLayout advancedBody;
    private TextView advancedArrow;
    private TextView tvAuthWarn;
    private TextView tvAllowedCount;
    private TextView appEmpty;
    private EditText etPackage;
    private EditText etSearch;
    private Switch swConfirm;
    private Switch swCallerAuth;
    private Switch swFloating;
    private Switch swFallback;
    private Switch swAutoOpen;
    private ListView appList;
    private Button btnFilterAll;
    private Button btnFilterUser;
    private Button btnFilterSystem;
    private AppAdapter adapter;
    private JSONObject cachedConfig;
    private boolean suppress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        list = findViewById(R.id.list);
        advancedBody = findViewById(R.id.advancedBody);
        advancedArrow = findViewById(R.id.advancedArrow);
        tvAuthWarn = findViewById(R.id.tvAuthWarn);
        tvAllowedCount = findViewById(R.id.tvAllowedCount);
        appEmpty = findViewById(R.id.appEmpty);
        etPackage = findViewById(R.id.etPackage);
        etSearch = findViewById(R.id.etSearch);
        swConfirm = findViewById(R.id.swConfirm);
        swCallerAuth = findViewById(R.id.swCallerAuth);
        swFloating = findViewById(R.id.swFloating);
        swFallback = findViewById(R.id.swFallback);
        swAutoOpen = findViewById(R.id.swAutoOpen);
        appList = findViewById(R.id.appList);
        btnFilterAll = findViewById(R.id.btnFilterAll);
        btnFilterUser = findViewById(R.id.btnFilterUser);
        btnFilterSystem = findViewById(R.id.btnFilterSystem);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        adapter = new AppAdapter();
        appList.setAdapter(adapter);

        btnFilterAll.setOnClickListener(v -> setKind(AppList.Kind.ALL));
        btnFilterUser.setOnClickListener(v -> setKind(AppList.Kind.USER));
        btnFilterSystem.setOnClickListener(v -> setKind(AppList.Kind.SYSTEM));
        updateFilterButtons();

        findViewById(R.id.advancedToggle).setOnClickListener(v -> {
            boolean expand = advancedBody.getVisibility() != View.VISIBLE;
            advancedBody.setVisibility(expand ? View.VISIBLE : View.GONE);
            advancedArrow.setText(expand
                    ? R.string.advanced_collapse : R.string.advanced_expand);
        });

        // 安装行为开关默认折叠：把垂直空间留给应用授权列表，避免列表被压到
        // 只剩一行而无法滚动（1.0.3 的实测问题）。
        final LinearLayout optionsBody = findViewById(R.id.optionsBody);
        final TextView optionsArrow = findViewById(R.id.optionsArrow);
        findViewById(R.id.optionsToggle).setOnClickListener(v -> {
            boolean expand = optionsBody.getVisibility() != View.VISIBLE;
            optionsBody.setVisibility(expand ? View.VISIBLE : View.GONE);
            optionsArrow.setText(expand
                    ? R.string.advanced_collapse : R.string.advanced_expand);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.btnRevokeAll).setOnClickListener(
                v -> call("callerClear", new JSONObject()));
        findViewById(R.id.btnAdd).setOnClickListener(v -> {
            String pkg = etPackage.getText().toString().trim();
            if (!pkg.isEmpty()) {
                try {
                    call("allowListAdd", new JSONObject().put("package", pkg));
                } catch (JSONException ignored) {
                }
                etPackage.setText("");
            }
        });
        findViewById(R.id.btnClear).setOnClickListener(
                v -> call("allowListClear", new JSONObject()));

        swCallerAuth.setOnCheckedChangeListener((b, checked) -> {
            if (suppress) {
                return;
            }
            tvAuthWarn.setVisibility(checked ? View.GONE : View.VISIBLE);
            saveConfig();
        });
        swFloating.setOnCheckedChangeListener((b, checked) -> {
            if (suppress) {
                return;
            }
            if (checked && !FloatingIndicator.canDraw(this)) {
                // 先经自主通道以 shell 身份开启悬浮窗权限；只有自动授权失败才
                // 引导用户手动去系统设置。全程不会启动任何应用内窗口。
                FloatingIndicator.ensurePermissionAsync(this, () -> {
                    if (!FloatingIndicator.canDraw(this) && !isFinishing()) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.overlay_perm_title)
                                .setMessage(R.string.overlay_perm_msg)
                                .setPositiveButton(R.string.overlay_perm_grant,
                                        (d, w) -> FloatingIndicator.openPermissionSettings(this))
                                .setNegativeButton(R.string.overlay_perm_skip, null)
                                .show();
                    }
                });
            }
            saveConfig();
        });
        swFallback.setOnCheckedChangeListener((b, checked) -> {
            if (!suppress) {
                saveConfig();
            }
        });
        swAutoOpen.setOnCheckedChangeListener((b, checked) -> {
            if (suppress) {
                return;
            }
            if (checked) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.auto_open_title)
                        .setMessage(R.string.auto_open_warn)
                        .setPositiveButton(R.string.auto_open_keep, null)
                        .setNegativeButton(R.string.auto_open_cancel, (d, w) -> {
                            suppress = true;
                            b.setChecked(false);
                            suppress = false;
                            saveConfig();
                        })
                        .show();
            }
            saveConfig();
        });
        swConfirm.setOnCheckedChangeListener((b, checked) -> {
            if (!suppress) {
                saveConfig();
            }
        });

        loadAll();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void setKind(AppList.Kind next) {
        kind = next;
        updateFilterButtons();
        filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
    }

    private void updateFilterButtons() {
        styleFilter(btnFilterAll, kind == AppList.Kind.ALL);
        styleFilter(btnFilterUser, kind == AppList.Kind.USER);
        styleFilter(btnFilterSystem, kind == AppList.Kind.SYSTEM);
    }

    private void styleFilter(Button b, boolean active) {
        b.setBackgroundResource(active
                ? R.drawable.bg_filter_active : R.drawable.btn_secondary);
        b.setTextColor(getResources().getColor(active
                ? R.color.on_brand : R.color.text_primary, getTheme()));
    }

    private void loadAll() {
        worker.execute(() -> {
            JSONObject status = dispatch("status", "{}");
            JSONObject d = status.optJSONObject("data");
            JSONObject cfg = d == null ? null : d.optJSONObject("config");
            List<AppList.Row> rows = AppList.load();
            final JSONObject cfgFinal = cfg;
            ui(() -> {
                applyConfig(cfgFinal);
                setApps(rows);
            });
        });
    }

    private void applyConfig(JSONObject cfg) {
        if (cfg == null) {
            return;
        }
        cachedConfig = cfg;
        allowed.clear();
        JSONArray al = cfg.optJSONArray("allowedCallers");
        if (al != null) {
            for (int i = 0; i < al.length(); i++) {
                allowed.add(al.optString(i));
            }
        }
        suppress = true;
        swCallerAuth.setChecked(cfg.optBoolean("callerAuthEnabled", true));
        swFloating.setChecked(cfg.optBoolean("floatingIndicator", true));
        swFallback.setChecked(cfg.optBoolean("fallbackToSystemInstaller", true));
        swAutoOpen.setChecked(cfg.optBoolean("autoOpenSystemInstaller", false));
        swConfirm.setChecked(cfg.optBoolean("confirmBeforeInstall", false));
        suppress = false;
        tvAuthWarn.setVisibility(swCallerAuth.isChecked() ? View.GONE : View.VISIBLE);

        JSONArray wl = cfg.optJSONArray("allowList");
        renderAllowList(wl == null ? new JSONArray() : wl);
        syncAllowedFlags();
    }

    private void setApps(List<AppList.Row> rows) {
        allApps.clear();
        allApps.addAll(rows);
        syncAllowedFlags();
        filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
    }

    private void syncAllowedFlags() {
        for (AppList.Row r : allApps) {
            r.allowed = allowed.contains(r.pkg);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateCount();
    }

    private void filter(String query) {
        shownApps.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (AppList.Row r : allApps) {
            if (!AppList.matches(r, kind)) {
                continue;
            }
            if (q.isEmpty()
                    || r.label.toLowerCase(Locale.ROOT).contains(q)
                    || r.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                shownApps.add(r);
            }
        }
        // 已授权永远置顶；组内用户应用在前、系统应用在后；最后按名称排序。
        // 这样几百个应用里不必翻找常用项，系统应用也能一眼分辨。
        shownApps.sort((a, b) -> {
            if (a.allowed != b.allowed) {
                return a.allowed ? -1 : 1;
            }
            if (a.system != b.system) {
                return a.system ? 1 : -1;
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        adapter.notifyDataSetChanged();
        appEmpty.setVisibility(shownApps.isEmpty() ? View.VISIBLE : View.GONE);
        appEmpty.setText(allApps.isEmpty()
                ? R.string.auth_loading
                : (kind == AppList.Kind.ALL ? R.string.auth_no_match : R.string.auth_empty_filter));
    }

    private void updateCount() {
        int n = 0;
        for (AppList.Row r : allApps) {
            if (r.allowed) {
                n++;
            }
        }
        tvAllowedCount.setText(getString(R.string.auth_allowed_count, n, allApps.size()));
    }

    private void toggle(AppList.Row row) {
        boolean next = !row.allowed;
        row.allowed = next;
        if (next) {
            allowed.add(row.pkg);
        } else {
            allowed.remove(row.pkg);
        }
        // 重新走一遍过滤：已授权项立刻置顶，取消授权则落回原位
        filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
        updateCount();
        try {
            call("callerSet", new JSONObject()
                    .put("caller", row.pkg)
                    .put("allowed", next));
        } catch (JSONException ignored) {
        }
    }

    private void saveConfig() {
        if (cachedConfig == null) {
            return;
        }
        try {
            JSONObject patch = new JSONObject()
                    .put("callerAuthEnabled", swCallerAuth.isChecked())
                    .put("floatingIndicator", swFloating.isChecked())
                    .put("fallbackToSystemInstaller", swFallback.isChecked())
                    .put("autoOpenSystemInstaller", swAutoOpen.isChecked())
                    .put("confirmBeforeInstall", swConfirm.isChecked());
            call("setConfig", new JSONObject().put("config", patch));
        } catch (JSONException ignored) {
        }
    }

    private void renderAllowList(JSONArray al) {
        list.removeAllViews();
        if (al.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.allow_empty);
            empty.setTextSize(13);
            empty.setTextColor(getResources().getColor(R.color.text_muted, getTheme()));
            empty.setPadding(8, 16, 8, 16);
            list.addView(empty);
            return;
        }
        for (int i = 0; i < al.length(); i++) {
            final String pkg = al.optString(i);
            View row = getLayoutInflater().inflate(R.layout.item_allow, list, false);
            ((TextView) row.findViewById(R.id.itemAllowName)).setText(pkg);
            row.findViewById(R.id.itemAllowRemove).setOnClickListener(v -> {
                try {
                    call("allowListRemove", new JSONObject().put("package", pkg));
                } catch (JSONException ignored) {
                }
            });
            list.addView(row);
        }
    }

    private void call(String method, JSONObject args) {
        worker.execute(() -> {
            JSONObject r = dispatch(method, args.toString());
            JSONObject cfg = null;
            if (r.optBoolean("ok")) {
                JSONObject d = r.optJSONObject("data");
                cfg = d == null ? null : d.optJSONObject("config");
            }
            final JSONObject result = cfg;
            ui(() -> {
                if (result != null) {
                    applyConfig(result);
                } else if (!r.optBoolean("ok")) {
                    JSONObject e = r.optJSONObject("error");
                    Toast.makeText(this,
                            e == null ? getString(R.string.auth_toggle_failed)
                                    : e.optString("message", getString(R.string.auth_toggle_failed)),
                            Toast.LENGTH_SHORT).show();
                    loadAll();
                }
            });
        });
    }

    private JSONObject dispatch(String method, String args) {
        try {
            return new JSONObject(RustCore.dispatch(method, args));
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false)
                        .put("error", new JSONObject().put("message", String.valueOf(e)));
            } catch (JSONException ignored) {
                return new JSONObject();
            }
        }
    }

    private void ui(Runnable r) {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(r);
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shownApps.size();
        }

        @Override
        public Object getItem(int position) {
            return shownApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(SettingsActivity.this)
                        .inflate(R.layout.item_app, parent, false);
            }
            final AppList.Row row = shownApps.get(position);
            ((TextView) v.findViewById(R.id.itemAppName)).setText(row.label);
            ((TextView) v.findViewById(R.id.itemAppPkg)).setText(row.pkg);

            TextView tag = v.findViewById(R.id.itemAppTag);
            tag.setText(getString(row.system
                    ? R.string.auth_tag_system : R.string.auth_tag_user));
            tag.setBackgroundResource(row.system
                    ? R.drawable.bg_tag_system : R.drawable.bg_tag_user);
            tag.setTextColor(getResources().getColor(row.system
                    ? R.color.warning : R.color.brand_primary, getTheme()));

            v.findViewById(R.id.itemAppAllowed).setVisibility(
                    row.allowed ? View.VISIBLE : View.GONE);

            Switch sw = v.findViewById(R.id.itemAppSwitch);
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(row.allowed);
            v.setOnClickListener(x -> toggle(row));
            return v;
        }
    }
}
