package io.github.lianxia233.privbroker;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 安装日志的本地持久化：JSON Lines 追加写，保留最近若干条。
 *
 * 这里只做存储，不判断任何业务含义；记录内容由 Rust 核心生成。
 * 后台静默安装与应用内安装共用同一份文件。
 */
final class InstallLogStore {
    private static final int MAX_ENTRIES = 500;
    private static final Object LOCK = new Object();
    private static final String DIR = "logs";
    private static final String FILE = "install-log.jsonl";

    private InstallLogStore() {
    }

    private static File file(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, FILE);
    }

    /** 追加一条记录，并补上时间戳、可读时间与唯一 id。 */
    static JSONObject append(Context c, JSONObject entry) throws Exception {
        synchronized (LOCK) {
            long ts = System.currentTimeMillis();
            entry.put("ts", ts);
            entry.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                    .format(new Date(ts)));
            entry.put("id", Long.toHexString(ts) + "-"
                    + Integer.toHexString((entry.toString().hashCode() & 0xFFFF)));

            File f = file(c);
            List<String> lines = readLines(f);
            lines.add(entry.toString());
            if (lines.size() > MAX_ENTRIES) {
                lines = new ArrayList<>(lines.subList(lines.size() - MAX_ENTRIES, lines.size()));
            }
            writeLines(f, lines);
            return new JSONObject().put("ok", true);
        }
    }

    /** 返回最近 limit 条，最新的在最前。 */
    static JSONArray list(Context c, int limit) throws Exception {
        synchronized (LOCK) {
            int cap = Math.max(1, Math.min(limit, 1000));
            List<String> lines = readLines(file(c));
            JSONArray out = new JSONArray();
            for (int i = lines.size() - 1; i >= 0 && out.length() < cap; i--) {
                try {
                    out.put(new JSONObject(lines.get(i)));
                } catch (Exception ignored) {
                    // 跳过损坏的历史行，不影响其余记录
                }
            }
            return out;
        }
    }

    static JSONObject clear(Context c) throws Exception {
        synchronized (LOCK) {
            File f = file(c);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            return new JSONObject().put("ok", true);
        }
    }

    private static List<String> readLines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        if (!f.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    /** 原子替换，避免写入中途崩溃导致文件损坏；统一 LF 换行。 */
    private static void writeLines(File f, List<String> lines) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(tmp, false), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        }
        if (!tmp.renameTo(f)) {
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            if (!tmp.renameTo(f)) {
                throw new IllegalStateException("无法写入安装日志");
            }
        }
    }
}
