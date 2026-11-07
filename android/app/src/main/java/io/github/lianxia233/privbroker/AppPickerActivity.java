package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 卸载应用选择器：读取系统已安装应用，按「全部 / 用户应用 / 系统应用」筛选，
 * 支持搜索名称或包名，选中后二次确认再执行静默卸载。
 *
 * 系统应用用独立徽章与警示文案标出：卸载预置应用可能失败或影响系统功能，
 * 因此确认框会额外提示，且默认仍交由用户决定，不做自动拦截。
 * 另保留「输入包名」入口，兼容列表里查不到（未安装/多用户）的场景。
 */
public final class AppPickerActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppList.Row> allApps = new ArrayList<>();
    private final List<AppList.Row> shownApps = new ArrayList<>();
    private AppList.Kind kind = AppList.Kind.ALL;

    private EditText etSearch;
    private ListView appList;
    private TextView appEmpty;
    private Button btnFilterAll;
    private Button btnFilterUser;
    private Button btnFilterSystem;
    private PickerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        etSearch = findViewById(R.id.etSearch);
        appList = findViewById(R.id.appList);
        appEmpty = findViewById(R.id.appEmpty);
        btnFilterAll = findViewById(R.id.btnFilterAll);
        btnFilterUser = findViewById(R.id.btnFilterUser);
        btnFilterSystem = findViewById(R.id.btnFilterSystem);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnManual).setOnClickListener(v -> showManualDialog());

        adapter = new PickerAdapter();
        appList.setAdapter(adapter);

        btnFilterAll.setOnClickListener(v -> setKind(AppList.Kind.ALL));
        btnFilterUser.setOnClickListener(v -> setKind(AppList.Kind.USER));
        btnFilterSystem.setOnClickListener(v -> setKind(AppList.Kind.SYSTEM));
        updateFilterButtons();

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

        loadAll();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void loadAll() {
        worker.execute(() -> {
            List<AppList.Row> rows = AppList.load();
            ui(() -> {
                allApps.clear();
                allApps.addAll(rows);
                filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
            });
        });
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
        // 用户应用在前、系统应用在后，同类按名称排序：卸载时先看到自己装的软件
        shownApps.sort((a, b) -> {
            if (a.system != b.system) {
                return a.system ? 1 : -1;
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        adapter.notifyDataSetChanged();
        appEmpty.setVisibility(shownApps.isEmpty() ? View.VISIBLE : View.GONE);
        appEmpty.setText(allApps.isEmpty()
                ? R.string.picker_loading
                : (kind == AppList.Kind.ALL
                    ? R.string.picker_no_match : R.string.auth_empty_filter));
    }

    private void confirm(AppList.Row row) {
        final Switch keep = new Switch(this);
        keep.setText(R.string.dialog_keep_data);
        keep.setTextSize(13);
        keep.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 16, 48, 8);
        box.addView(keep);
        String note = getString(row.system
                ? R.string.picker_confirm_system : R.string.picker_confirm_user);
        String msg = getString(R.string.picker_confirm_msg, row.pkg, note);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.picker_confirm_title, row.label))
                .setMessage(msg)
                .setView(box)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_uninstall_btn,
                        (d, w) -> {
                            Uninstaller.run(this, row.pkg, keep.isChecked());
                            finish();
                        })
                .show();
    }

    /** 兼容入口：列表里查不到时仍可按包名卸载。 */
    private void showManualDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.dialog_uninstall_hint);
        input.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        input.setHintTextColor(getResources().getColor(R.color.text_muted, getTheme()));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(24, 16, 24, 16);
        final Switch keep = new Switch(this);
        keep.setText(R.string.dialog_keep_data);
        keep.setTextSize(13);
        keep.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        box.addView(input);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        box.addView(keep, lp);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_uninstall_title)
                .setView(box)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_uninstall_btn, (d, w) -> {
                    String pkg = input.getText().toString().trim();
                    if (!pkg.isEmpty()) {
                        Uninstaller.run(this, pkg, keep.isChecked());
                        finish();
                    }
                })
                .show();
    }

    private void ui(Runnable r) {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(r);
        }
    }

    private final class PickerAdapter extends BaseAdapter {
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
                v = LayoutInflater.from(AppPickerActivity.this)
                        .inflate(R.layout.item_pick_app, parent, false);
            }
            final AppList.Row row = shownApps.get(position);
            ((TextView) v.findViewById(R.id.pickName)).setText(row.label);
            ((TextView) v.findViewById(R.id.pickPkg)).setText(row.pkg);

            TextView tag = v.findViewById(R.id.pickTag);
            tag.setText(getString(row.system
                    ? R.string.picker_tag_system : R.string.picker_tag_user));
            tag.setBackgroundResource(row.system
                    ? R.drawable.bg_tag_system : R.drawable.bg_tag_user);
            tag.setTextColor(getResources().getColor(row.system
                    ? R.color.warning : R.color.brand_primary, getTheme()));

            v.setOnClickListener(x -> confirm(row));
            return v;
        }
    }
}
