package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 安装历史：展示、点开详情、导出分享与清空。 */
public final class LogActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<JSONObject> rows = new ArrayList<>();
    /** 已展开的记录（按列表位置），点击整条即可展开 / 收起完整详情。 */
    private final Set<Integer> expanded = new HashSet<>();
    private LogAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        empty = findViewById(R.id.logEmpty);
        ListView list = findViewById(R.id.logList);
        adapter = new LogAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        // 点击整条记录展开 / 收起：展开后显示完整未截断的启动日志与失败原因。
        list.setOnItemClickListener((parent, v, position, id) -> {
            if (expanded.contains(position)) {
                expanded.remove(position);
            } else {
                expanded.add(position);
            }
            adapter.notifyDataSetChanged();
        });
        // 长按复制该条记录的完整详情，便于把诊断日志发给开发者。
        list.setOnItemLongClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= rows.size()) {
                return false;
            }
            String full = fullDetail(rows.get(position));
            if (full.isEmpty()) {
                return false;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        getString(R.string.log_title), full));
                Toast.makeText(this, R.string.log_detail_copied, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        findViewById(R.id.btnLogBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnLogClear).setOnClickListener(v -> clear());
        findViewById(R.id.btnLogShare).setOnClickListener(v -> share());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        worker.execute(() -> {
            JSONObject r = dispatch("logList", "{\"limit\":200}");
            List<JSONObject> next = new ArrayList<>();
            JSONObject d = r.optJSONObject("data");
            JSONArray arr = d == null ? null : d.optJSONArray("entries");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        next.add(o);
                    }
                }
            }
            ui(() -> {
                rows.clear();
                rows.addAll(next);
                // 数据集变化后旧的位置不再对应同一条记录，展开态必须重置。
                expanded.clear();
                adapter.notifyDataSetChanged();
                empty.setText(rows.isEmpty()
                        ? getString(R.string.logs_empty) : getString(R.string.logs_hint));
            });
        });
    }

    private void clear() {
        worker.execute(() -> {
            dispatch("logClear", "{}");
            ui(() -> {
                Toast.makeText(this, getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    private void share() {
        worker.execute(() -> {
            JSONObject r = dispatch("logExport", "{}");
            JSONObject d = r.optJSONObject("data");
            final String text = d == null ? "" : d.optString("text", "");
            ui(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.logs_share_title));
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, getString(R.string.logs_share_title)));
                } catch (Throwable t) {
                    Toast.makeText(this, getString(R.string.logs_share_failed),
                            Toast.LENGTH_SHORT).show();
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
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private void ui(Runnable r) {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(r);
        }
    }

    private final class LogAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(LogActivity.this)
                        .inflate(R.layout.item_log, parent, false);
            }
            JSONObject e = rows.get(position);
            boolean ok = e.optBoolean("ok");
            String opValue = e.optString("op");
            int opRes = "uninstall".equals(opValue) ? R.string.log_op_uninstall
                    : ActivationLog.OP_ACTIVATE.equals(opValue) ? R.string.log_op_activate
                    : R.string.log_op_install;
            String op = getString(opRes);
            String src = e.optString("source");
            int srcRes = "silent".equals(src) ? R.string.log_source_silent
                    : ActivationLog.SOURCE_WIRELESS.equals(src) ? R.string.log_source_wireless
                    : R.string.log_source_ui;
            String source = getString(srcRes);

            TextView title = v.findViewById(R.id.itemTitle);
            TextView meta = v.findViewById(R.id.itemMeta);
            TextView detail = v.findViewById(R.id.itemDetail);
            TextView toggle = v.findViewById(R.id.itemToggle);
            TextView badge = v.findViewById(R.id.itemBadge);
            View dot = v.findViewById(R.id.itemDot);

            // 标题优先展示应用真实名称（中文），不再只显示文件名或包名
            String appName = e.optString("appName", "");
            if (appName.isEmpty() || "null".equals(appName)) {
                appName = e.optString("label", "");
            }
            title.setText(appName.isEmpty() ? getString(R.string.log_unknown) : appName);

            StringBuilder m = new StringBuilder();
            String time = e.optString("time", "");
            if (!time.isEmpty()) {
                m.append(time).append("  ·  ");
            }
            m.append(op).append("  ·  ").append(source);
            String pkg = e.optString("package", "");
            if (!pkg.isEmpty() && !"null".equals(pkg)) {
                m.append("  ·  ").append(pkg);
            }
            String caller = e.optString("caller", "");
            if (!caller.isEmpty() && !"null".equals(caller)) {
                m.append("  ·  ").append(getString(R.string.log_caller, caller));
            }
            int port = e.optInt("port", 0);
            if (port > 0) {
                m.append("  ·  端口 ").append(port);
            }
            long ms = e.optLong("durationMs", 0);
            if (ms > 0) {
                m.append("  ·  ").append(ms).append(" ms");
            }
            meta.setText(m.toString());

            String full = fullDetail(e);
            boolean open = expanded.contains(position);
            if (full.isEmpty()) {
                detail.setVisibility(View.GONE);
                toggle.setVisibility(View.GONE);
            } else {
                detail.setVisibility(View.VISIBLE);
                toggle.setVisibility(View.VISIBLE);
                if (open) {
                    // 展开：完整原文，不限行数、不省略。复制走「长按整条」，
                    // 不启用 setTextIsSelectable —— 那会让 TextView 消费触摸
                    // 事件，导致「点击整条收起」失效。
                    detail.setText(full);
                    detail.setMaxLines(Integer.MAX_VALUE);
                    detail.setEllipsize(null);
                    toggle.setText(getString(R.string.log_collapse) + "  ·  "
                            + getString(R.string.log_detail_full_hint));
                } else {
                    // 收起：头尾保留的摘要，末尾失败原因不会被吃掉。
                    detail.setText(clipDetail(full));
                    detail.setMaxLines(3);
                    detail.setEllipsize(TextUtils.TruncateAt.END);
                    toggle.setText(getString(R.string.log_expand));
                }
            }

            badge.setText(getString(ok ? R.string.log_success : R.string.log_fail));
            badge.setTextColor(getResources().getColor(
                    ok ? R.color.success : R.color.danger, getTheme()));
            badge.setBackgroundResource(ok
                    ? R.drawable.bg_badge_ok : R.drawable.bg_badge_warn);
            dot.setBackgroundResource(ok ? R.drawable.bg_dot_ok : R.drawable.bg_dot_fail);
            return v;
        }
    }

    /**
     * 生成一条记录的完整详情原文（不截断）。展开态与长按复制都用它，
     * 保证「执行日志」里能看到完整的启动日志与失败原因，而不是省略号。
     */
    private String fullDetail(JSONObject e) {
        StringBuilder sb = new StringBuilder();
        if (!e.optBoolean("ok")) {
            String code = e.optString("code", "-");
            String zh = AppNames.errorText(LogActivity.this, code, "");
            sb.append(getString(R.string.log_error_code, zh));
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

    /** 详情过长时保留头尾：启动日志的失败原因在末尾，只截前段会把它吃掉。 */
    private static String clipDetail(String raw) {
        final int cap = 1200;
        if (raw.length() <= cap) {
            return raw;
        }
        int head = cap * 2 / 3;
        int tail = cap - head;
        return raw.substring(0, head) + "\n…\n" + raw.substring(raw.length() - tail);
    }
}
