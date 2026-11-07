package io.github.lianxia233.privbroker;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


/**
 * 无业务逻辑的 I/O 胶水层。Rust 核心通过 JNI 以 JSON 调用这里的方法。
 *
 * 这里只做 I/O：探测特权代理连接状态、经自主通道执行命令、搬运字节、
 * 持久化配置与安装日志。任何「该做什么」的判断都在 Rust 侧。
 */
public final class BrokerBridge {
    private static final String TAG = "BrokerBridge";
    private static final String PREFS = "privilege_broker_config";
    private static final String KEY_CONFIG = "config_json";
    public static final int REQ_CODE = 0x5A11;

    private static final String DEFAULT_CONFIG =
            "{\"confirmBeforeInstall\":false,\"deleteApkAfterInstall\":false,\"allowList\":[],"
                    + "\"callerAuthEnabled\":true,\"allowedCallers\":[],\"deniedCallers\":[],"
                    + "\"fallbackToSystemInstaller\":true,\"autoOpenSystemInstaller\":false,"
                    + "\"floatingIndicator\":true}";

    public interface ProgressListener {
        void onProgress(String stage, int index, int total);
    }

    private static volatile ProgressListener progressListener;

    private BrokerBridge() {
    }

    public static void setProgressListener(ProgressListener l) {
        progressListener = l;
    }

    public static String call(String method, String argsJson) {
        try {
            JSONObject a = (argsJson == null || argsJson.isEmpty())
                    ? new JSONObject() : new JSONObject(argsJson);
            switch (method) {
                case "brokerState":
                    return brokerState().toString();
                case "requestAuthorization":
                    return requestAuthorization().toString();
                case "exec":
                    return exec(a).toString();
                case "stat":
                    return stat(a).toString();
                case "archiveEntries":
                    return archiveEntries(a).toString();
                case "readBytes":
                    return readBytes(a).toString();
                case "deleteSource":
                    return deleteSource(a).toString();
                case "configLoad":
                    return configLoad().toString();
                case "configSave":
                    return configSave(a).toString();
                case "progress": {
                    ProgressListener l = progressListener;
                    if (l != null) {
                        l.onProgress(a.optString("stage"), a.optInt("index"), a.optInt("total"));
                    }
                    return "{}";
                }
                case "now":
                    return new JSONObject().put("millis", System.currentTimeMillis()).toString();
                case "logAppend":
                    return InstallLogStore.append(App.context(), a).toString();
                case "logList":
                    return new JSONObject()
                            .put("entries", InstallLogStore.list(App.context(), a.optInt("limit", 200)))
                            .toString();
                case "logClear":
                    return InstallLogStore.clear(App.context()).toString();
                case "installedApps":
                    return installedApps().toString();
                case "appLabel":
                    return appLabel(a).toString();
                case "callerLabel":
                    return callerLabel(a).toString();
                case "stageForShell":
                    return stageForShell(a).toString();
                case "cleanupStaged":
                    return cleanupStaged().toString();
                default:
                    return error("unknown method: " + method);
            }
        } catch (Throwable t) {
            Log.e(TAG, "call(" + method + ") failed", t);
            return error(method + ": " + t.toString());
        }
    }

    private static String error(String msg) {
        try {
            return new JSONObject().put("error", msg).toString();
        } catch (Exception e) {
            return "{\"error\":\"unknown\"}";
        }
    }

    // ------------------------------------------------------------ 特权代理状态

    /** 探测自主特权代理的连接与握手状态，字段沿用 Rust 侧契约。 */
    private static JSONObject brokerState() {
        JSONObject o = new JSONObject();
        try {
            BrokerClient client = BrokerClient.connect(App.context());
            o.put("available", true);
            o.put("permission", 1);
            o.put("serverVersion", client.protocolVersion());
            o.put("serverUid", client.serverUid());
            client.close();
        } catch (Throwable t) {
            Log.w(TAG, "brokerState", t);
            try {
                o.put("available", false);
                o.put("permission", -1);
                o.put("serverVersion", 0);
                o.put("serverUid", -1);
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /** 尝试完成一次握手；未启动时返回 false，由 UI 展示启动引导。 */
    private static JSONObject requestAuthorization() throws Exception {
        boolean granted = false;
        try {
            BrokerClient client = BrokerClient.connect(App.context());
            granted = client.serverUid() > 0;
            client.close();
        } catch (Throwable t) {
            Log.w(TAG, "requestAuthorization", t);
        }
        return new JSONObject().put("granted", granted);
    }

    // ------------------------------------------------------------ 特权执行

    /**
     * 通过自主通道以 shell 身份执行命令。
     *
     * stdin 一律先落地为特权进程可读的绝对路径：独立进程没有 ContentResolver，
     * 也不应把整包字节塞进单帧。落地文件由 cleanupStaged 生命周期统一回收，
     * 与「安装成功后删除安装包」互不影响。
     */
    private static JSONObject exec(JSONObject a) throws Exception {
        JSONArray argv = a.getJSONArray("argv");
        String[] cmd = new String[argv.length()];
        for (int i = 0; i < argv.length(); i++) {
            cmd[i] = argv.getString(i);
        }
        JSONObject stdinSpec = a.optJSONObject("stdin");
        JSONObject stdin = new JSONObject().put("type", "none");
        if (stdinSpec != null && !"none".equals(stdinSpec.optString("type", "none"))) {
            stdin.put("type", "path").put("path", materializeStdin(stdinSpec));
        }
        BrokerClient client = BrokerClient.connect(App.context());
        try {
            return client.exec(cmd, stdin, a.optLong("timeoutMs", 120_000L));
        } finally {
            client.close();
        }
    }

    /** 把 content:// / 归档条目的字节流落地到特权进程可读目录，返回绝对路径。 */
    private static String materializeStdin(JSONObject spec) throws Exception {
        File dir = StagedFiles.shellDir(App.context());
        File dst = new File(dir, "stdin_" + java.util.UUID.randomUUID().toString().substring(0, 8));
        try (OutputStream os = new FileOutputStream(dst)) {
            pipeStdin(spec, os);
        }
        if (dst.length() <= 0) {
            throw new IllegalStateException("stdin 落地文件为空");
        }
        dst.setReadable(true, false);
        return dst.getAbsolutePath();
    }

    // ------------------------------------------------------------ 字节搬运

    private static ContentResolver resolver() {
        Context c = App.context();
        return c.getContentResolver();
    }

    private static String stdinUri(JSONObject spec) {
        return spec.optString("uri");
    }

    /** 把 stdin 来源的字节流写入命令进程的 stdin，全程不落盘。 */
    private static void pipeStdin(JSONObject spec, OutputStream os) throws Exception {
        String type = spec.optString("type");
        if (type.isEmpty() || "none".equals(type)) {
            return;
        }
        Uri uri = Uri.parse(stdinUri(spec));
        if ("uri".equals(type)) {
            try (InputStream in = resolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IllegalStateException("无法打开 URI: " + uri);
                }
                copy(in, os);
            }
        } else if ("archiveEntry".equals(type)) {
            String wanted = spec.optString("entry");
            try (ZipInputStream zis = new ZipInputStream(resolver().openInputStream(uri))) {
                ZipEntry e;
                boolean found = false;
                while ((e = zis.getNextEntry()) != null) {
                    if (wanted.equals(e.getName())) {
                        copy(zis, os);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new IllegalStateException("归档条目不存在: " + wanted);
                }
            }
        } else {
            throw new IllegalArgumentException("未知 stdin 类型: " + type);
        }
    }

    private static void copy(InputStream in, OutputStream os) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
        os.flush();
    }

    private static JSONObject stat(JSONObject a) throws Exception {
        Uri uri = Uri.parse(a.getString("uri"));
        String name = "";
        long size = -1;
        try (Cursor c = resolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) {
                    name = c.getString(ni);
                }
                if (si >= 0 && !c.isNull(si)) {
                    size = c.getLong(si);
                }
            }
        } catch (Throwable ignored) {
        }
        if (size < 0) {
            try (ParcelFileDescriptor pfd = resolver().openFileDescriptor(uri, "r")) {
                if (pfd != null) {
                    size = pfd.getStatSize();
                }
            } catch (Throwable ignored) {
            }
        }
        if (name.isEmpty()) {
            String seg = uri.getLastPathSegment();
            name = seg == null ? "package.apk" : seg;
        }
        JSONObject r = new JSONObject();
        r.put("uri", uri.toString());
        r.put("displayName", name);
        r.put("size", Math.max(0, size));
        r.put("kind", ""); // 类型判定由 Rust 侧完成
        return r;
    }

    private static JSONObject archiveEntries(JSONObject a) throws Exception {
        Uri uri = Uri.parse(a.getString("uri"));
        JSONArray entries = new JSONArray();
        try (ZipInputStream zis = new ZipInputStream(resolver().openInputStream(uri))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                long size = e.getSize();
                boolean isApk = e.getName().toLowerCase(Locale.ROOT).endsWith(".apk");
                if (size < 0 && isApk) {
                    // local header 未记录大小时流式统计，保证 -S 参数准确
                    long n = 0;
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = zis.read(buf)) > 0) {
                        n += r;
                    }
                    size = n;
                }
                JSONObject item = new JSONObject();
                item.put("name", e.getName());
                item.put("size", Math.max(0, size));
                entries.put(item);
            }
        }
        return new JSONObject().put("entries", entries);
    }

    private static JSONObject readBytes(JSONObject a) throws Exception {
        JSONObject spec = a.getJSONObject("stdin");
        int limit = Math.min(a.optInt("limit", 262144), 1024 * 1024);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream limited = new OutputStream() {
            int written = 0;

            @Override
            public void write(int b) {
                if (written < limit) {
                    bos.write(b);
                    written++;
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                int n = Math.min(len, Math.max(0, limit - written));
                if (n > 0) {
                    bos.write(b, off, n);
                    written += n;
                }
            }
        };
        pipeStdin(spec, limited);
        byte[] data = bos.toByteArray();
        StringBuilder hex = new StringBuilder(data.length * 2);
        final char[] digits = "0123456789abcdef".toCharArray();
        for (byte x : data) {
            int v = x & 0xFF;
            hex.append(digits[v >>> 4]).append(digits[v & 0x0F]);
        }
        return new JSONObject().put("hex", hex.toString());
    }

    /**
     * 删除安装包（「安装成功后删除安装包」开关的唯一执行点）。
     *
     * 顺序：
     *  1. DocumentsContract.deleteDocument —— 只在调用方给了写权限时才成功，
     *     而「打开方式 / 分享」进来的 URI 通常只有读权限；
     *  2. ContentResolver.delete —— 同上，多数情况被 SecurityException 拒绝；
     *  3. 解析真实路径并用 shell 身份 rm —— 这一步才是开关真正生效的原因。
     *     走 argv 数组（不经 shell 拼接），路径不可能被注入成命令。
     *
     * 返回值始终带 {@code path}，方便日志里看清「删的是哪个文件」。
     */
    private static JSONObject deleteSource(JSONObject a) throws Exception {
        Uri uri = Uri.parse(a.getString("uri"));
        boolean deleted = false;
        try {
            deleted = DocumentsContract.deleteDocument(resolver(), uri);
        } catch (Throwable ignored) {
        }
        if (!deleted) {
            try {
                deleted = resolver().delete(uri, null, null) > 0;
            } catch (Throwable ignored) {
            }
        }
        String path = deleted ? "" : filePathOf(uri);
        if (!deleted && path != null && !path.isEmpty()) {
            deleted = shellDelete(path);
        }
        return new JSONObject().put("deleted", deleted).put("path", path == null ? "" : path);
    }

    /** 从 SAF / MediaStore 的 _data 列解析真实路径；拿不到返回 null。 */
    private static String filePathOf(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        try (Cursor c = resolver().query(uri, new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 以 shell 身份 rm -f；只接受绝对路径。 */
    private static boolean shellDelete(String path) {
        if (path == null || !path.startsWith("/")) {
            return false;
        }
        try {
            JSONArray argv = new JSONArray().put("rm").put("-f").put(path);
            JSONObject o = exec(new JSONObject().put("argv", argv));
            return o.optInt("code", -1) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * stdin 流式写入失败时的兜底：把该 split 复制到 shell 可读目录，
     * 返回绝对路径，供 `pm install-write <session> <name> <path>` 使用。
     * 完全绕开跨进程 stdin 管道，避免跨进程管道在部分 ROM 上的兼容差异。
     */
    private static JSONObject stageForShell(JSONObject a) throws Exception {
        JSONObject spec = a.getJSONObject("stdin");
        String name = a.optString("name", "base.apk");
        File dir = StagedFiles.shellDir(App.context());
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            safe = safe + ".apk";
        }
        File dst = new File(dir, "stage_" + safe);
        try (OutputStream os = new FileOutputStream(dst)) {
            pipeStdin(spec, os);
        }
        if (dst.length() <= 0) {
            throw new IllegalStateException("兜底文件为空，无法用文件路径写入");
        }
        // shell(uid 2000) 与 system 需要能读这个文件
        dst.setReadable(true, false);
        return new JSONObject()
                .put("path", dst.getAbsolutePath())
                .put("size", dst.length());
    }

    private static JSONObject cleanupStaged() {
        try {
            StagedFiles.cleanup(App.context());
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    // ------------------------------------------------------------ 应用名称与列表

    /** 系统应用列表：供设置页渲染授权开关。用户应用排在前面，同类按名称排序。 */
    private static JSONObject installedApps() throws Exception {
        Context c = App.context();
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> infos = pm.getInstalledApplications(0);
        final String self = c.getPackageName();
        List<JSONObject> rows = new ArrayList<>();
        for (ApplicationInfo info : infos) {
            if (info.packageName == null || info.packageName.equals(self)) {
                continue;
            }
            String label;
            try {
                CharSequence cs = info.loadLabel(pm);
                label = cs == null ? "" : cs.toString().trim();
            } catch (Throwable ignored) {
                label = "";
            }
            if (label.isEmpty()) {
                label = info.packageName;
            }
            JSONObject o = new JSONObject();
            o.put("package", info.packageName);
            o.put("label", label);
            o.put("system", (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            rows.add(o);
        }
        rows.sort((a, b) -> {
            int sa = a.optBoolean("system") ? 1 : 0;
            int sb = b.optBoolean("system") ? 1 : 0;
            if (sa != sb) {
                return sa - sb;
            }
            return a.optString("label").compareToIgnoreCase(b.optString("label"));
        });
        return new JSONObject().put("apps", new JSONArray(rows));
    }

    /** 包名 → 已安装应用的中文名，查不到返回空串。 */
    private static JSONObject appLabel(JSONObject a) throws Exception {
        String label = AppNames.labelForPackage(App.context(), a.optString("package", ""));
        return new JSONObject().put("label", label == null ? "" : label);
    }

    /** 调用方标识 → 可展示名称（未知来源有专用文案）。 */
    private static JSONObject callerLabel(JSONObject a) throws Exception {
        return new JSONObject().put("label",
                AppNames.labelForCaller(App.context(), a.optString("caller", "")));
    }

    // ------------------------------------------------------------ 配置持久化

    private static SharedPreferences prefs() {
        return App.context().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject configLoad() throws Exception {
        String s = prefs().getString(KEY_CONFIG, null);
        if (s == null) {
            return new JSONObject(DEFAULT_CONFIG);
        }
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject(DEFAULT_CONFIG);
        }
    }

    private static JSONObject configSave(JSONObject a) throws Exception {
        prefs().edit().putString(KEY_CONFIG, a.toString()).apply();
        return new JSONObject().put("ok", true);
    }
}
