package io.github.lianxia233.privbroker;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
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
        List<String> out = new ArrayList<>();
        for (String s : sources) {
            File dst = null;
            try {
                String name = safeName(AppNames.displayName(c, s));
                dst = new File(dir, UUID.randomUUID().toString().substring(0, 8) + "_" + name);
                try (InputStream in = open(c, Uri.parse(s));
                     OutputStream os = new FileOutputStream(dst)) {
                    copy(in, os);
                }
                if (dst.length() <= 0) {
                    throw new IllegalStateException("安装包为空");
                }
                out.add(stagedUri(c, dst.getName()).toString());
            } catch (Throwable t) {
                if (dst != null) {
                    try {
                        dst.delete();
                    } catch (Throwable ignored) {
                    }
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
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
        os.flush();
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
