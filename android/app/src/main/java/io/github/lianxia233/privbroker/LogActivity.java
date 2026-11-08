package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 安装历史：展示、导出分享与清空。所有记录由 Rust 核心在安装/卸载结束时生成。 */
public final class LogActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<JSONObject> rows = new ArrayList<>();
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

            StringBuilder detailText = new StringBuilder();
            if (!ok) {
                String code = e.optString("code", "-");
                String zh = AppNames.errorText(LogActivity.this, code, "");
                detailText.append(getString(R.string.log_error_code, zh));
            }
            String raw = e.optString("detail", "").trim();
            if (!raw.isEmpty()) {
                if (detailText.length() > 0) {
                    detailText.append('\n');
                }
                detailText.append(clipDetail(raw));
            }
            if (detailText.length() == 0) {
                detail.setVisibility(View.GONE);
            } else {
                detail.setVisibility(View.VISIBLE);
                detail.setText(detailText.toString());
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
