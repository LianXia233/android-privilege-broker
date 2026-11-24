package io.github.lianxia233.privbroker;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/**
 * 安装包「来源文件」的身份信息：真实路径、inode、大小与可选的 SHA-256。
 *
 * <p>为什么必须在暂存阶段采集：调用方给的 {@code content://} 读授权只在其
 * Intent 对应的 Activity 存活期间有效。删除发生在安装成功之后的后台线程，
 * 那时再想打开 URI 拿 fd 已经晚了（{@code SecurityException}）。因此趁
 * {@link StagedFiles#stage} 正在读字节，一次性把「这个安装包到底是磁盘上的
 * 哪个文件」记下来，交给特权代理（shell 身份）去删。</p>
 *
 * <p>两处来源各自采集：外部「打开方式 / 分享」走暂存复制，能同时拿到大小与
 * 哈希；应用内「选择安装包」持的是可持久化读授权，没有暂存副本，只采集
 * 路径与大小，删除时用大小校验身份。</p>
 */
final class SourceFacts {
    private static final String TAG = "SourceFacts";
    private static final String PREFS = "privilege_broker_sources";
    private static final String KEY_PREFIX = "f_";
    /** 超过一天的记录没有任何意义：安装包要么已经删了，要么用户早已放弃。 */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    /** 记录条数上限，避免异常调用把 SharedPreferences 撑大。 */
    private static final int MAX_ENTRIES = 64;

    private SourceFacts() {
    }

    /**
     * 采集某个来源 URI 的事实并持久化。
     *
     * @param staged 暂存副本（外部入口用），用于取精确大小；应用内直接安装时传 null
     * @param sha256 暂存复制时算出的内容哈希；未开启「成功后删除安装包」或应用内
     *               直接安装时为 null（此时删除只按大小校验身份）
     */
    static void capture(Context c, Uri original, File staged, String sha256) {
        if (c == null || original == null) {
            return;
        }
        try {
            JSONObject facts = resolveNow(c, original);
            String name = AppNames.displayName(c, original.toString());
            if (name != null && !name.isEmpty()) {
                facts.put("name", FileNames.normalize(name));
            }
            if (staged != null) {
                facts.put("size", staged.length());
            }
            if (sha256 != null && !sha256.isEmpty()) {
                facts.put("sha256", sha256.toLowerCase(Locale.ROOT));
            }
            facts.put("ts", System.currentTimeMillis());
            store(c, original.toString(), facts);
        } catch (Throwable t) {
            // 采集失败不影响安装：删除时会退回到运行期解析与 shell 侧查询。
            Log.w(TAG, "capture failed: " + original, t);
        }
    }

    /**
     * 读取记录（不删除）。
     *
     * <p>刻意保留而不是用后即焚：删除失败后用户很可能重试一次（比如先去
     * 关掉占用文件的下载器），此时原始 content:// 的读授权早已失效，
     * 只有这份事实还能定位文件。过期由 {@link #TTL_MS} 与条数上限回收。</p>
     */
    static JSONObject get(Context c, String uri) {
        if (c == null || uri == null || uri.isEmpty()) {
            return null;
        }
        try {
            SharedPreferences p = prefs(c);
            String raw = p.getString(KEY_PREFIX + uri, null);
            return raw == null ? null : new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 现场解析一个 URI 背后的文件。
     *
     * <p>顺序：SAF 文档 ID 直接解码 → 打开 fd 取 inode/大小并用
     * {@code /proc/self/fd} 反查真实路径 → 最后退回 {@code _data} 列。
     * 只有落在 {@code /storage} 下的普通文件才算解析成功，管道、socket、
     * 应用私有目录一律返回空路径，交给上层按其他策略处理。</p>
     */
    static JSONObject resolveNow(Context c, Uri u) {
        JSONObject o = new JSONObject();
        try {
            o.put("uri", u.toString());
            String path = directPath(c, u);
            long ino = -1;
            long dev = -1;
            long size = -1;
            if (c != null) {
                try (ParcelFileDescriptor pfd =
                             c.getContentResolver().openFileDescriptor(u, "r")) {
                    if (pfd != null) {
                        StructStat st = null;
                        try {
                            st = Os.fstat(pfd.getFileDescriptor());
                        } catch (Throwable ignored) {
                        }
                        if (st != null && OsConstants.S_ISREG(st.st_mode)) {
                            ino = st.st_ino;
                            dev = st.st_dev;
                            size = st.st_size;
                            if (path == null) {
                                path = normalize(readlinkFd(pfd));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 读权限已失效或提供方只给管道，都属于正常情况
                }
            }
            o.put("path", path == null ? "" : path);
            o.put("ino", ino);
            o.put("dev", dev);
            o.put("size", size);
        } catch (Throwable t) {
            Log.w(TAG, "resolve failed: " + u, t);
        }
        return o;
    }

    /** SAF / 下载管理器 / MediaStore 的确定性路径解码，不需要打开文件。 */
    private static String directPath(Context c, Uri u) {
        String scheme = u.getScheme();
        if ("file".equals(scheme)) {
            return u.getPath();
        }
        if (!"content".equals(scheme)) {
            return null;
        }
        String authority = u.getAuthority() == null ? "" : u.getAuthority();
        String docId = null;
        try {
            docId = DocumentsContract.getDocumentId(u);
        } catch (Throwable ignored) {
        }
        if (docId != null) {
            if ("com.android.externalstorage.documents".equals(authority)) {
                // primary:Download/foo.apk  |  1A2B-3C4D:Backup/foo.apk
                int colon = docId.indexOf(':');
                if (colon > 0) {
                    String volume = docId.substring(0, colon);
                    String rel = docId.substring(colon + 1);
                    if ("primary".equalsIgnoreCase(volume)) {
                        return "/storage/emulated/" + userId() + "/" + rel;
                    }
                    if (!volume.isEmpty()) {
                        return "/storage/" + volume + "/" + rel;
                    }
                }
            } else if ("com.android.providers.downloads.documents".equals(authority)
                    && docId.startsWith("raw:")) {
                // raw:/storage/emulated/0/Download/foo.apk
                return docId.substring("raw:".length());
            }
        }
        if (c == null) {
            return null;
        }
        try (Cursor cur = c.getContentResolver().query(
                u, new String[]{"_data"}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) {
                return normalize(cur.getString(0));
            }
        } catch (Throwable ignored) {
            // 大多数第三方 FileProvider 不提供 _data 列
        }
        return null;
    }

    /** {@code /proc/self/fd/N} 反查真实路径（API 29+ 才能从 PFD 取到 fd 号）。 */
    private static String readlinkFd(ParcelFileDescriptor pfd) {
        try {
            int fd = pfd.getFd();
            if (fd < 0) {
                return null;
            }
            return Os.readlink("/proc/self/fd/" + fd);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把提供方进程看到的挂载视图还原成 shell 也能访问的 {@code /storage} 路径。
     *
     * <p>不同进程的挂载命名空间不同：system_server 看到 /mnt/user/0/emulated/0，
     * 旧版是 /mnt/runtime/default/emulated/0，直通模式是
     * /mnt/pass_through/0/emulated/0，FUSE 底层是 /mnt/media_rw/<卷> 或
     * /data/media/<用户>。统一映射成 /storage/... 后，特权代理解析出的
     * 路径与我们看到的才是同一个文件。</p>
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim();
        int deleted = p.indexOf(" (deleted)");
        if (deleted > 0) {
            p = p.substring(0, deleted);
        }
        if (p.isEmpty() || p.startsWith("/proc/") || p.startsWith("/dev/")
                || p.startsWith("socket:") || p.startsWith("pipe:")
                || p.startsWith("anon_inode:") || p.startsWith("memfd:")) {
            return null;
        }
        String[] viewPrefixes = {
                "/mnt/user/", "/mnt/pass_through/", "/mnt/installer/", "/mnt/runtime/",
        };
        for (String prefix : viewPrefixes) {
            if (p.startsWith(prefix)) {
                // 去掉 /mnt/<view>/ 后的用户号或视图名，保留 emulated/0/...
                String rest = p.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    p = "/storage/" + rest.substring(slash + 1);
                }
                break;
            }
        }
        if (p.startsWith("/mnt/media_rw/")) {
            p = "/storage/" + p.substring("/mnt/media_rw/".length());
        }
        if (p.startsWith("/data/media/")) {
            // /data/media/0/Download/x → /storage/emulated/0/Download/x
            String rest = p.substring("/data/media/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                p = "/storage/emulated/" + rest.substring(0, slash) + rest.substring(slash);
            }
        }
        if (p.startsWith("/sdcard/")) {
            p = "/storage/emulated/" + userId() + p.substring("/sdcard".length());
        }
        if (!p.startsWith("/storage/")) {
            return null;
        }
        return p;
    }

    private static int userId() {
        try {
            // UserHandle.myUserId() 不在公开 SDK 中，按 uid 换算：uid = userId * 100000
            return android.os.Process.myUid() / 100000;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------ 持久化

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized void store(Context c, String uri, JSONObject facts) {
        try {
            SharedPreferences p = prefs(c);
            SharedPreferences.Editor e = p.edit();
            long now = System.currentTimeMillis();
            int kept = 0;
            for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                String k = entry.getKey();
                if (!k.startsWith(KEY_PREFIX)) {
                    continue;
                }
                if (k.equals(KEY_PREFIX + uri)) {
                    continue;
                }
                long ts = 0;
                Object v = entry.getValue();
                if (v instanceof String) {
                    try {
                        ts = new JSONObject((String) v).optLong("ts", 0);
                    } catch (Throwable ignored) {
                    }
                }
                if (now - ts > TTL_MS || kept >= MAX_ENTRIES) {
                    e.remove(k);
                } else {
                    kept++;
                }
            }
            e.putString(KEY_PREFIX + uri, facts.toString());
            e.apply();
        } catch (Throwable t) {
            Log.w(TAG, "store failed", t);
        }
    }
}
