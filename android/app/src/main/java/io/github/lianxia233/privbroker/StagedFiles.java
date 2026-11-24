package io.github.lianxia233.privbroker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 把外部调用方传入的 content:// 安装包复制到应用私有缓存。
 *
 * 必须这样做：调用方通过 FLAG_GRANT_READ_URI_PERMISSION 授予的读权限只在其
 * Intent 对应的 Activity 存活期间有效。静默安装跑在进程级后台线程，入口
 * Activity 会立刻 finish；若后台线程再去读原始 URI，授权往往已被回收，
 * 表现为 install-write 阶段抛 SecurityException（过去被伪装成 exit -1）。
 *
 * 复制发生在入口 Activity 的 onCreate，此时授权必然有效。之后整条链路
 * 只读本应用自己的 StagedProvider，不依赖任何临时授权。
 */
final class StagedFiles {
    static final String AUTHORITY_SUFFIX = ".staged";
    private static final String SRC_DIR = "staged";
    private static final String SHELL_DIR = "shellstage";
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    /**
     * 文件路径兜底用的中转根目录。
     *
     * 外部缓存（/storage/emulated/0/Android/data/<pkg>/cache）属于 FUSE 域
     * u:object_r:fuse:s0：shell(2000) 能读，但 system_server 读不到，`pm install-write`
     * 传文件路径时会在 system_server 侧报 avc denied、exit 255。
     * /data/local/tmp 的模式是 0771（owner/group shell），system_server 可读，
     * 应用无法写入，只能由特权代理（shell 身份）在这里中转。
     */
    static final String TMP_ROOT = "/data/local/tmp/prvbrk-stage";
    private static final String TMP_PREFS = "privilege_broker_stage";
    private static final String KEY_TMP_DIRS = "tmp_dirs";

    private StagedFiles() {
    }

    static String authority(Context c) {
        return c.getPackageName() + AUTHORITY_SUFFIX;
    }

    static File srcDir(Context c) {
        File d = new File(c.getCacheDir(), SRC_DIR);
        d.mkdirs();
        return d;
    }

    /** 兜底写入目录：优先外部缓存（shell 身份通常可读），否则退回内部缓存。 */
    static File shellDir(Context c) {
        File base = c.getExternalCacheDir();
        if (base == null) {
            base = c.getCacheDir();
        }
        File d = new File(base, SHELL_DIR);
        d.mkdirs();
        return d;
    }

    static Uri stagedUri(Context c, String fileName) {
        return Uri.parse("content://" + authority(c) + "/staged/" + fileName);
    }

    /** 逐个复制；单个失败时保留原始 URI，让后续链路如实报错而不是静默丢包。 */
    static List<String> stage(Context c, List<String> sources) {
        File dir = srcDir(c);
        purgeExpired(dir);
        // 只有开启「成功后删除安装包」时才值得顺手算内容哈希：大安装包
        // 多算一次 SHA-256 要多花一两秒，没开删除的用户不该为此买单。
        boolean wantHash = BrokerBridge.deleteAfterEnabled();
        List<String> out = new ArrayList<>();
        for (String s : sources) {
            File dst = null;
            try {
                String name = safeName(AppNames.displayName(c, s));
                dst = new File(dir, UUID.randomUUID().toString().substring(0, 8) + "_" + name);
                MessageDigest md = wantHash ? MessageDigest.getInstance("SHA-256") : null;
                try (InputStream in = open(c, Uri.parse(s));
                     OutputStream os = new FileOutputStream(dst)) {
                    copy(in, os, md);
                }
                if (dst.length() <= 0) {
                    throw new IllegalStateException("安装包为空");
                }
                // 此刻读授权仍然有效：把「这个安装包到底是磁盘上哪个文件」记下来，
                // 安装成功后的后台线程才有依据让特权代理删除它。
                SourceFacts.capture(c, Uri.parse(s), dst,
                        md == null ? null : hex(md.digest()));
                out.add(stagedUri(c, dst.getName()).toString());
            } catch (Throwable t) {
                if (dst != null) {
                    try {
                        dst.delete();
                    } catch (Throwable ignored) {
                    }
                }
                // 暂存失败也要尽力采集事实：至少还有路径可用于后续删除。
                try {
                    SourceFacts.capture(c, Uri.parse(s), null, null);
                } catch (Throwable ignored) {
                }
                out.add(s);
            }
        }
        return out;
    }

    private static InputStream open(Context c, Uri u) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) {
            throw new IllegalStateException("无法打开 URI: " + u);
        }
        return in;
    }

    static void copy(InputStream in, OutputStream os) throws Exception {
        copy(in, os, null);
    }

    /** 复制字节的同时可选地喂给摘要器，避免为了算哈希再读一遍文件。 */
    static void copy(InputStream in, OutputStream os, MessageDigest md) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (md != null) {
                md.update(buf, 0, n);
            }
            os.write(buf, 0, n);
        }
        os.flush();
    }

    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xFF;
            sb.append(digits[v >>> 4]).append(digits[v & 0x0F]);
        }
        return sb.toString();
    }

    /** 保留真实扩展名：Rust 侧用 displayName 判定 apk / apks / xapk / apkm。 */
    private static String safeName(String raw) {
        // 先剥掉社交软件追加的 .1 / .bin 等后缀，否则 xxx.apk.1 会被当成
        // 未知类型再补一个 .apk，私有缓存里变成 xxx.apk.1.apk。
        String s = FileNames.normalize(raw);
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.length() > 80) {
            s = s.substring(s.length() - 80);
        }
        if (!FileNames.isInstallable(s)) {
            s = s + ".apk";
        }
        if (s.isEmpty() || s.startsWith(".")) {
            s = "pkg" + s;
        }
        return s;
    }

    /**
     * 文件路径兜底用的本地暂存文件。
     *
     * 只落在外部缓存（应用可写、shell 可读），随后由特权代理复制到
     * {@link #TMP_ROOT}，绝不再把这个路径直接交给 `pm install-write`。
     */
    static File transferFile(Context c, String name) {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return new File(shellDir(c), "xfer_" + tag + "_" + safeName(name));
    }

    /** 中转文件名：沿用现有净化规则，避免把奇怪字符带进 shell 参数。 */
    static String transferName(String name) {
        return safeName(name);
    }

    /** 记录已交给特权代理的中转目录，供结束或失败时逐个删除（不整目录清空）。 */
    static synchronized void rememberTmpDir(Context c, String dir) {
        LinkedHashSet<String> dirs = new LinkedHashSet<>(
                tmpPrefs(c).getStringSet(KEY_TMP_DIRS, Collections.<String>emptySet()));
        dirs.add(dir);
        tmpPrefs(c).edit().putStringSet(KEY_TMP_DIRS, dirs).apply();
    }

    /** 取出待清理的中转目录并清空记录；调用方负责按返回值逐个删除。 */
    static synchronized List<String> takeTmpDirs(Context c) {
        List<String> out = new ArrayList<>(
                tmpPrefs(c).getStringSet(KEY_TMP_DIRS, Collections.<String>emptySet()));
        tmpPrefs(c).edit().remove(KEY_TMP_DIRS).apply();
        return out;
    }

    /** 删除失败时把目录放回记录，留给下一次结束/失败清理再试。 */
    static synchronized void restoreTmpDirs(Context c, List<String> dirs) {
        LinkedHashSet<String> all = new LinkedHashSet<>(
                tmpPrefs(c).getStringSet(KEY_TMP_DIRS, Collections.<String>emptySet()));
        all.addAll(dirs);
        tmpPrefs(c).edit().putStringSet(KEY_TMP_DIRS, all).apply();
    }

    private static SharedPreferences tmpPrefs(Context c) {
        return c.getSharedPreferences(TMP_PREFS, Context.MODE_PRIVATE);
    }

    /** 安装结束后清理：shell 临时文件全删，源文件按时间过期清理（不删本次的，降级还要用）。 */
    static void cleanup(Context c) {
        File[] shell = shellDir(c).listFiles();
        if (shell != null) {
            for (File f : shell) {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        }
        purgeExpired(srcDir(c));
    }

    private static void purgeExpired(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File f : fs) {
            if (now - f.lastModified() > MAX_AGE_MS) {
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
