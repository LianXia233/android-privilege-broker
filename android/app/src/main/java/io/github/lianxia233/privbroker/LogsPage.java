package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记录页：安装 / 卸载 / 激活历史的卡片列表。
 *
 * <p>顶部用分段控件按操作类型筛选；点击整条展开完整详情，长按复制，
 * 右上角导出与清空。数据与旧版日志页完全同源（Rust {@code logList}）。</p>
 */
final class LogsPage {
    private final Activity a;
    private final View root;
    private final List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();

    private ListView listView;
    private TextView empty;
    private SegmentedControl filter;
    private LogAdapter adapter;
    private int kind; // 0 全部 / 1 安装 / 2 卸载 / 3 激活

    LogsPage(Activity a, View root) {
        this.a = a;
        this.root = root;
        bind();
    }

    private void bind() {
        listView = root.findViewById(R.id.logList);
        empty = root.findViewById(R.id.logEmpty);
        filter = root.findViewById(R.id.segLogs);
        Prism.padTopForStatusBar(root.findViewById(R.id.logsHeader));

        filter.setSegments(a.getString(R.string.logs_all),
                a.getString(R.string.logs_install),
                a.getString(R.string.logs_uninstall),
                a.getString(R.string.logs_activate));
        filter.setSelection(0, false);
        filter.setListener(index -> {
            kind = index;
            applyFilter();
        });

        adapter = new LogAdapter();
        listView.setAdapter(adapter);
        listView.setEmptyView(empty);
        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= shown.size()) {
                return;
            }
            JSONObject e = shown.get(position);
            String key = e.optString("id", String.valueOf(position));
            if (!expanded.remove(key)) {
                expanded.add(key);
            }
            adapter.notifyDataSetChanged();
        });
        listView.setOnItemLongClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= shown.size()) {
                return false;
            }
            String full = fullDetail(shown.get(position));
            if (full.isEmpty()) {
                return false;
            }
            ClipboardManager cm = (ClipboardManager)
                    a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        a.getString(R.string.log_title), full));
                Toast.makeText(a, R.string.log_detail_copied, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        Prism.press(root.findViewById(R.id.btnLogClear), this::clear);
        Prism.press(root.findViewById(R.id.btnLogShare), this::share);
    }

    void onResume() {
        reload();
    }

    void reload() {
        PageBridge.call(a, "logList", "{\"limit\":200}", r -> {
            JSONObject d = r.optJSONObject("data");
            JSONArray arr = d == null ? null : d.optJSONArray("entries");
            all.clear();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        all.add(o);
                    }
                }
            }
            expanded.clear();
            applyFilter();
            empty.setText(all.isEmpty()
                    ? a.getString(R.string.logs_empty) : a.getString(R.string.logs_hint));
        });
    }

    private void applyFilter() {
        shown.clear();
        for (JSONObject e : all) {
            String op = e.optString("op", "install");
            boolean match = switch (kind) {
                case 1 -> "install".equals(op);
                case 2 -> "uninstall".equals(op);
                case 3 -> ActivationLog.OP_ACTIVATE.equals(op);
                default -> true;
            };
            if (match) {
                shown.add(e);
            }
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void clear() {
        PageBridge.call(a, "logClear", "{}", r -> {
            Toast.makeText(a, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
            reload();
        });
    }

    private void share() {
        PageBridge.call(a, "logExport", "{}", r -> {
            JSONObject d = r.optJSONObject("data");
            String text = d == null ? "" : d.optString("text", "");
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, a.getString(R.string.logs_share_title));
                i.putExtra(Intent.EXTRA_TEXT, text);
                a.startActivity(Intent.createChooser(i, a.getString(R.string.logs_share_title)));
            } catch (Throwable t) {
                Toast.makeText(a, R.string.logs_share_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String fullDetail(JSONObject e) {
        StringBuilder sb = new StringBuilder();
        if (!e.optBoolean("ok")) {
            String code = e.optString("code", "-");
            sb.append(a.getString(R.string.log_error_code,
                    AppNames.errorText(a, code, "")));
        }
        String raw = e.optString("detail", "").trim();
        if (!raw.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(raw);
        }
        return sb.toString();
    }

    private static String clipDetail(String raw) {
        final int cap = 1200;
        if (raw.length() <= cap) {
            return raw;
        }
        int head = cap * 2 / 3;
        int tail = cap - head;
        return raw.substring(0, head) + "\n…\n" + raw.substring(raw.length() - tail);
    }

    /** 展开态追加的结构化字段：保证任何一条记录展开后都有新增内容。 */
    private String structured(JSONObject e) {
        StringBuilder sb = new StringBuilder();
        sb.append("记录 ID   ").append(e.optString("id", "-")).append('\n');
        sb.append("操作      ").append(e.optString("op", "install")).append('\n');
        sb.append("来源      ").append(e.optString("source", "-")).append('\n');
        String pkg = e.optString("package", "");
        if (!pkg.isEmpty() && !"null".equals(pkg)) {
            sb.append("包名      ").append(pkg).append('\n');
        }
        String caller = e.optString("caller", "");
        if (!caller.isEmpty() && !"null".equals(caller)) {
            sb.append("调用方    ").append(caller).append('\n');
        }
        String time = e.optString("time", "");
        if (!time.isEmpty()) {
            sb.append("时间      ").append(time).append('\n');
        }
        long duration = e.optLong("durationMs", 0);
        if (duration > 0) {
            sb.append("耗时      ").append(duration).append(" ms\n");
        }
        sb.append("结果      ").append(e.optBoolean("ok") ? "成功" : "失败（"
                + e.optString("code", "-") + "）");
        return sb.toString();
    }

    private final class LogAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(a).inflate(R.layout.item_log, parent, false);
            }
            JSONObject e = shown.get(position);
            boolean ok = e.optBoolean("ok");
            String opValue = e.optString("op");
            int opRes = "uninstall".equals(opValue) ? R.string.log_op_uninstall
                    : ActivationLog.OP_ACTIVATE.equals(opValue) ? R.string.log_op_activate
                    : R.string.log_op_install;
            String source = a.getString("silent".equals(e.optString("source"))
                    ? R.string.log_source_silent
                    : ActivationLog.SOURCE_WIRELESS.equals(e.optString("source"))
                    ? R.string.log_source_wireless : R.string.log_source_ui);

            TextView title = v.findViewById(R.id.itemTitle);
            TextView meta = v.findViewById(R.id.itemMeta);
            TextView detail = v.findViewById(R.id.itemDetail);
            TextView toggle = v.findViewById(R.id.itemToggle);
            TextView badge = v.findViewById(R.id.itemBadge);
            View dot = v.findViewById(R.id.itemDot);

            String appName = e.optString("appName", "");
            if (appName.isEmpty() || "null".equals(appName)) {
                appName = e.optString("label", "");
            }
            title.setText(appName.isEmpty() ? a.getString(R.string.log_unknown) : appName);

            StringBuilder m = new StringBuilder();
            String time = e.optString("time", "");
            if (!time.isEmpty()) {
                m.append(time).append("  ·  ");
            }
            m.append(a.getString(opRes)).append("  ·  ").append(source);
            String pkg = e.optString("package", "");
            if (!pkg.isEmpty() && !"null".equals(pkg)) {
                m.append("  ·  ").append(pkg);
            }
            String caller = e.optString("caller", "");
            if (!caller.isEmpty() && !"null".equals(caller)) {
                m.append("  ·  ").append(a.getString(R.string.log_caller, caller));
            }
            meta.setText(m.toString());

            String full = fullDetail(e);
            String key = e.optString("id", String.valueOf(position));
            boolean open = expanded.contains(key);
            // 展开时始终追加结构化字段：短记录（成功的安装通常只有一两行）
            // 仅去掉省略号是看不出变化的，用户会以为「点了没用」。
            String structured = structured(e);
            if (!open) {
                if (full.isEmpty()) {
                    detail.setVisibility(View.GONE);
                    toggle.setVisibility(View.GONE);
                } else {
                    detail.setVisibility(View.VISIBLE);
                    detail.setText(clipDetail(full));
                    detail.setMaxLines(2);
                    detail.setEllipsize(TextUtils.TruncateAt.END);
                    toggle.setText(R.string.log_expand);
                    toggle.setVisibility(View.VISIBLE);
                }
            } else {
                detail.setVisibility(View.VISIBLE);
                detail.setText(full.isEmpty() ? structured : full + "\n\n" + structured);
                detail.setMaxLines(Integer.MAX_VALUE);
                detail.setEllipsize(null);
                toggle.setText(a.getString(R.string.log_collapse) + "  ·  "
                        + a.getString(R.string.log_detail_full_hint));
                toggle.setVisibility(View.VISIBLE);
            }

            badge.setText(a.getString(ok ? R.string.log_success : R.string.log_fail));
            badge.setTextColor(Prism.color(a, ok ? R.attr.pbSuccess : R.attr.pbError));
            badge.setBackgroundResource(ok ? R.drawable.pb_chip_ok : R.drawable.pb_chip_warn);
            dot.setBackgroundResource(ok ? R.drawable.pb_dot_ok : R.drawable.pb_dot_fail);
            return v;
        }
    }
}
