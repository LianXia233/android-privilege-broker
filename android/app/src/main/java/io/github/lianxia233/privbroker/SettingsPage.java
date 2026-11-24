package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 设置页（FlClash 分组卡片语言）：外观、安装行为、授权、维护。
 *
 * <p>外观三项纯本地偏好（{@link UiPrefs}）；其余开关写 Rust 配置。授权区
 * 保留「白名单包名」的直接增删，并把「按应用授权」交给
 * {@link AllowAppsActivity}，避免把几百个应用铺在设置页里。</p>
 */
final class SettingsPage {
    private final Activity a;
    private final View root;

    private SegmentedControl segTheme;
    private SegmentedControl segAnim;
    private SegmentedControl segDock;
    private PrismSwitch swConfirm;
    private PrismSwitch swDelete;
    private PrismSwitch swDowngrade;
    private PrismSwitch swFallback;
    private PrismSwitch swAutoOpen;
    private PrismSwitch swCallerAuth;
    private PrismSwitch swFloating;
    private TextView tvAuthWarn;
    private TextView tvAllowedCount;
    private LinearLayout list;
    private EditText etPackage;

    private JSONObject config;
    private final Set<String> allowed = new HashSet<>();
    private boolean suppress;

    SettingsPage(Activity a, View root) {
        this.a = a;
        this.root = root;
        bind();
    }

    private void bind() {
        segTheme = root.findViewById(R.id.segTheme);
        segAnim = root.findViewById(R.id.segAnim);
        segDock = root.findViewById(R.id.segDock);
        swConfirm = root.findViewById(R.id.swConfirm);
        swDelete = root.findViewById(R.id.swDeleteAfterSettings);
        swDowngrade = root.findViewById(R.id.swDowngrade);
        swFallback = root.findViewById(R.id.swFallback);
        swAutoOpen = root.findViewById(R.id.swAutoOpen);
        swCallerAuth = root.findViewById(R.id.swCallerAuth);
        swFloating = root.findViewById(R.id.swFloatingSettings);
        tvAuthWarn = root.findViewById(R.id.tvAuthWarn);
        tvAllowedCount = root.findViewById(R.id.tvAllowedCount);
        list = root.findViewById(R.id.list);
        etPackage = root.findViewById(R.id.etPackage);

        Prism.padTopForStatusBar(root.findViewById(R.id.settingsContent));

        TextView themeSummary = root.findViewById(R.id.tvThemeSummary);
        if (themeSummary != null) {
            themeSummary.setText(UiPrefs.seed(a).toUpperCase(java.util.Locale.ROOT)
                    + " · " + UiPrefs.colorStyle(a));
        }
        Prism.press(root.findViewById(R.id.rowTheme),
                () -> a.startActivity(new Intent(a, ThemeActivity.class)));

        TextView footer = root.findViewById(R.id.settingsFooter);
        footer.setText(a.getString(R.string.footer) + " · "
                + a.getString(R.string.about_version, BuildInfo.versionName(a)));

        // ---- 外观
        segTheme.setSegments(a.getString(R.string.theme_system),
                a.getString(R.string.theme_light), a.getString(R.string.theme_dark));
        segAnim.setSegments(a.getString(R.string.anim_slide),
                a.getString(R.string.anim_fade));
        segDock.setSegments(a.getString(R.string.dock_floating),
                a.getString(R.string.dock_docked));

        String theme = UiPrefs.themeMode(a);
        segTheme.setSelection("light".equals(theme) ? 1 : "dark".equals(theme) ? 2 : 0, false);
        segTheme.setListener(index -> {
            String mode = index == 1 ? "light" : index == 2 ? "dark" : "system";
            UiPrefs.setThemeMode(a, mode);
            UiPrefs.bumpTheme(a); a.recreate();
        });
        segAnim.setSelection(UiPrefs.tabAnimation(a), false);
        segAnim.setListener(index -> UiPrefs.setTabAnimation(a, index));
        segDock.setSelection(UiPrefs.dockMode(a), false);
        segDock.setListener(index -> UiPrefs.setDockMode(a, index));

        // ---- 安装行为
        swConfirm.setOnCheckedChangeListener((b, v) -> saveIfReady("confirmBeforeInstall", v));
        swDelete.setOnCheckedChangeListener((b, v) -> saveIfReady("deleteApkAfterInstall", v));
        swDowngrade.setOnCheckedChangeListener((b, v) -> saveIfReady("allowDowngrade", v));
        swFallback.setOnCheckedChangeListener((b, v) -> saveIfReady("fallbackToSystemInstaller", v));
        swCallerAuth.setOnCheckedChangeListener((b, v) -> {
            if (suppress) {
                return;
            }
            tvAuthWarn.setVisibility(v ? View.GONE : View.VISIBLE);
            saveIfReady("callerAuthEnabled", v);
        });
        swAutoOpen.setOnCheckedChangeListener((b, v) -> {
            if (suppress) {
                return;
            }
            if (v) {
                new AlertDialog.Builder(a)
                        .setTitle(R.string.auto_open_title)
                        .setMessage(R.string.auto_open_warn)
                        .setPositiveButton(R.string.auto_open_keep, null)
                        .setNegativeButton(R.string.auto_open_cancel, (d, w) -> {
                            suppress = true;
                            b.setChecked(false);
                            suppress = false;
                            saveIfReady("autoOpenSystemInstaller", false);
                        })
                        .show();
            }
            saveIfReady("autoOpenSystemInstaller", v);
        });
        swFloating.setOnCheckedChangeListener((b, v) -> {
            if (suppress) {
                return;
            }
            if (v && !FloatingIndicator.canDraw(a)) {
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
            saveIfReady("floatingIndicator", v);
        });

        // ---- 授权
        Prism.press(root.findViewById(R.id.rowManageAuth), () ->
                a.startActivity(new Intent(a, AllowAppsActivity.class)));
        Prism.press(root.findViewById(R.id.btnAdd), this::addPackage);
        Prism.press(root.findViewById(R.id.btnClear), () ->
                call("allowListClear", new JSONObject()));
        Prism.press(root.findViewById(R.id.btnRevokeAll), () ->
                call("callerClear", new JSONObject()));
        etPackage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int i, int c, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int i, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    void onResume() {
        load();
    }

    private void load() {
        PageBridge.call(a, "status", "{}", r -> {
            JSONObject d = r.optJSONObject("data");
            JSONObject cfg = d == null ? null : d.optJSONObject("config");
            if (cfg != null) {
                applyConfig(cfg);
            }
        });
    }

    private void applyConfig(JSONObject cfg) {
        config = cfg;
        allowed.clear();
        JSONArray callers = cfg.optJSONArray("allowedCallers");
        if (callers != null) {
            for (int i = 0; i < callers.length(); i++) {
                allowed.add(callers.optString(i));
            }
        }
        suppress = true;
        swConfirm.setChecked(cfg.optBoolean("confirmBeforeInstall", false), false);
        swDelete.setChecked(cfg.optBoolean("deleteApkAfterInstall", false), false);
        swDowngrade.setChecked(cfg.optBoolean("allowDowngrade", false), false);
        swFallback.setChecked(cfg.optBoolean("fallbackToSystemInstaller", true), false);
        swAutoOpen.setChecked(cfg.optBoolean("autoOpenSystemInstaller", false), false);
        swCallerAuth.setChecked(cfg.optBoolean("callerAuthEnabled", true), false);
        swFloating.setChecked(cfg.optBoolean("floatingIndicator", true), false);
        suppress = false;
        tvAuthWarn.setVisibility(swCallerAuth.isChecked() ? View.GONE : View.VISIBLE);
        tvAllowedCount.setText(a.getString(R.string.auth_allowed_count,
                allowed.size(), allowed.size()));
        renderAllowList(cfg.optJSONArray("allowList"));
    }

    private void saveIfReady(String key, boolean value) {
        if (suppress) {
            return;
        }
        try {
            PageBridge.saveConfig(a, new JSONObject().put(key, value));
        } catch (Throwable ignored) {
        }
    }

    private void call(String method, JSONObject args) {
        PageBridge.call(a, method, args.toString(), r -> {
            if (!r.optBoolean("ok")) {
                JSONObject e = r.optJSONObject("error");
                Toast.makeText(a, e == null ? a.getString(R.string.auth_toggle_failed)
                                : e.optString("message", a.getString(R.string.auth_toggle_failed)),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject d = r.optJSONObject("data");
            JSONObject cfg = d == null ? null : d.optJSONObject("config");
            if (cfg != null) {
                applyConfig(cfg);
            } else {
                load();
            }
        });
    }

    private void addPackage() {
        String pkg = etPackage.getText().toString().trim();
        if (pkg.isEmpty()) {
            return;
        }
        try {
            call("allowListAdd", new JSONObject().put("package", pkg));
            etPackage.setText("");
        } catch (Throwable ignored) {
        }
    }

    /** 白名单行：包名 + 右侧移除按钮，风格与卡片一致。 */
    private void renderAllowList(JSONArray packages) {
        list.removeAllViews();
        if (packages == null || packages.length() == 0) {
            TextView empty = new TextView(a);
            empty.setText(R.string.allow_empty);
            Prism.text(empty, 13f, false, R.attr.pbTextMuted);
            empty.setPadding(Prism.dp(a, 4), Prism.dp(a, 10),
                    Prism.dp(a, 4), Prism.dp(a, 4));
            list.addView(empty);
            return;
        }
        for (int i = 0; i < packages.length(); i++) {
            String pkg = packages.optString(i);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Prism.dp(a, 8), 0, Prism.dp(a, 8));

            TextView name = new TextView(a);
            name.setText(pkg);
            Prism.text(name, 13f, false, R.attr.pbTextSecondary);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            ImageView remove = new ImageView(a);
            remove.setImageResource(R.drawable.ic_action_trash);
            remove.setColorFilter(Prism.color(a, R.attr.pbTextMuted));
            int size = Prism.dp(a, 32);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(size, size);
            rlp.leftMargin = Prism.dp(a, 8);
            remove.setPadding(Prism.dp(a, 7), Prism.dp(a, 7),
                    Prism.dp(a, 7), Prism.dp(a, 7));
            remove.setBackground(Prism.ripple(a, 999f));
            remove.setOnClickListener(v -> {
                try {
                    call("allowListRemove", new JSONObject().put("package", pkg));
                } catch (Throwable ignored) {
                }
            });
            row.addView(remove, rlp);
            list.addView(row);
        }
    }
}
