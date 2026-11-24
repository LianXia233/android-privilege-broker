package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 逐应用授权管理：列出已安装应用，逐个允许 / 拒绝静默安装调用。
 *
 * <p>与旧版设置页同一套数据源（{@link AppList} + Rust {@code callerSet}），
 * 只是换成了 FlClash 的卡片行与分段筛选。已授权应用固定置顶。</p>
 */
public final class AllowAppsActivity extends Activity {
    private final List<AppList.Row> allApps = new ArrayList<>();
    private final List<AppList.Row> shownApps = new ArrayList<>();
    private final Set<String> allowed = new HashSet<>();

    private EditText etSearch;
    private ListView appList;
    private TextView appEmpty;
    private SegmentedControl segFilter;
    private AppAdapter adapter;
    private AppList.Kind kind = AppList.Kind.ALL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prism.edgeToEdge(this);
        setContentView(R.layout.activity_allow_apps);

        etSearch = findViewById(R.id.etSearch);
        appList = findViewById(R.id.appList);
        appEmpty = findViewById(R.id.appEmpty);
        segFilter = findViewById(R.id.segFilter);

        Prism.padTopForStatusBar(findViewById(R.id.allowRoot));
        Prism.press(findViewById(R.id.btnBack), this::finish);

        segFilter.setSegments(getString(R.string.filter_all),
                getString(R.string.filter_user),
                getString(R.string.filter_system));
        segFilter.setSelection(0, false);
        segFilter.setListener(index -> {
            kind = index == 1 ? AppList.Kind.USER
                    : index == 2 ? AppList.Kind.SYSTEM : AppList.Kind.ALL;
            filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
        });

        adapter = new AppAdapter();
        appList.setAdapter(adapter);
        appList.setEmptyView(appEmpty);

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
        load();
    }

    private void load() {
        PageBridge.call(this, "status", "{}", r -> {
            JSONObject d = r.optJSONObject("data");
            JSONObject cfg = d == null ? null : d.optJSONObject("config");
            if (cfg != null) {
                allowed.clear();
                JSONArray al = cfg.optJSONArray("allowedCallers");
                if (al != null) {
                    for (int i = 0; i < al.length(); i++) {
                        allowed.add(al.optString(i));
                    }
                }
            }
            List<AppList.Row> rows = AppList.load();
            allApps.clear();
            allApps.addAll(rows);
            for (AppList.Row row : allApps) {
                row.allowed = allowed.contains(row.pkg);
            }
            filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
        });
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
        appEmpty.setText(allApps.isEmpty() ? getString(R.string.auth_loading)
                : getString(kind == AppList.Kind.ALL
                ? R.string.auth_no_match : R.string.auth_empty_filter));
    }

    private void toggle(AppList.Row row) {
        boolean next = !row.allowed;
        row.allowed = next;
        if (next) {
            allowed.add(row.pkg);
        } else {
            allowed.remove(row.pkg);
        }
        filter(etSearch.getText() == null ? "" : etSearch.getText().toString());
        try {
            PageBridge.call(this, "callerSet", new JSONObject()
                    .put("caller", row.pkg)
                    .put("allowed", next).toString(), r -> {
                if (!r.optBoolean("ok")) {
                    Toast.makeText(this, R.string.auth_toggle_failed, Toast.LENGTH_SHORT).show();
                    load();
                }
            });
        } catch (Throwable ignored) {
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
                v = LayoutInflater.from(AllowAppsActivity.this)
                        .inflate(R.layout.item_app, parent, false);
            }
            AppList.Row row = shownApps.get(position);
            ((TextView) v.findViewById(R.id.itemAppName)).setText(row.label);
            ((TextView) v.findViewById(R.id.itemAppPkg)).setText(row.pkg);

            TextView tag = v.findViewById(R.id.itemAppTag);
            tag.setText(getString(row.system
                    ? R.string.auth_tag_system : R.string.auth_tag_user));
            tag.setTextColor(getColor(row.system
                    ? R.color.pb_warning : R.color.pb_primary));

            PrismSwitch sw = v.findViewById(R.id.itemAppSwitch);
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(row.allowed, false);
            sw.setOnCheckedChangeListener((b, checked) -> toggle(row));
            v.setOnClickListener(x -> toggle(row));
            return v;
        }
    }
}
