package io.github.lianxia233.privbroker;

import java.util.Locale;

/**
 * 安装包文件名归一化。
 *
 * 微信 / QQ 等社交软件转发 APK 时，为了绕过系统安装器的自动识别，常在文件名
 * 尾部追加一个备份后缀，最常见的是 {@code xxx.apk.1}，也有 {@code .2}、
 * 多位数字、{@code .bin} / {@code .download} / {@code .tmp} 等变体。
 * 文件本体仍是原来的 APK，只是名字被改了。
 *
 * 若不先剥掉这层后缀：
 *  - {@code StagedFiles.safeName} 会把它当成未知类型再补一个 {@code .apk}，
 *    私有缓存里变成 {@code xxx.apk.1.apk}；
 *  - Rust {@code util::kind_of} 按 {@code .1} 结尾返回 unknown，走错分支；
 *  - 通知与日志里显示的名字也带着尾巴。
 *
 * 这里在整条链路的最前端统一归一化，后面所有环节看到的都是干净的
 * {@code xxx.apk} / {@code xxx.apks} / ...。
 */
final class FileNames {
    /** 支持的真实扩展名，按长度降序排列，避免 .apk 抢先匹配 .apks。 */
    private static final String[] INSTALL_EXTS = {
            ".apkmx", ".apks", ".xapk", ".apkm", ".zip", ".akp", ".apk"
    };
    /** 社交软件常见的非数字重命名后缀。 */
    private static final String[] RENAME_EXTS = {
            "bin", "download", "tmp", "bak", "temp", "save"
    };

    private FileNames() {
    }

    /** 取路径最后一段，去掉目录部分。 */
    static String baseName(String raw) {
        String s = raw == null ? "" : raw.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    /**
     * 剥离社交软件追加的备份后缀。多次转发会叠加成 {@code xxx.apk.1.1}，
     * 因此先把连续的「点 + 纯数字」或已知重命名后缀全部剥掉（最多 4 层），
     * 再校验剩下的名字是否仍是安装包格式；不是就回退原名，避免把
     * {@code 1.apk} / {@code note.txt} 之类误判。
     */
    static String normalize(String raw) {
        String base = baseName(raw);
        String s = base;
        for (int guard = 0; guard < 4; guard++) {
            int dot = s.lastIndexOf('.');
            if (dot <= 0 || dot == s.length() - 1) {
                break;
            }
            String tail = s.substring(dot + 1).toLowerCase(Locale.ROOT);
            boolean numeric = !tail.isEmpty() && tail.length() <= 3
                    && tail.chars().allMatch(Character::isDigit);
            boolean renamed = false;
            for (String e : RENAME_EXTS) {
                if (e.equals(tail)) {
                    renamed = true;
                    break;
                }
            }
            if (!numeric && !renamed) {
                break;
            }
            s = s.substring(0, dot);
        }
        return hasInstallExt(s) ? s : base;
    }

    /** 归一化后是否为支持的安装包格式。 */
    static boolean isInstallable(String raw) {
        return hasInstallExt(normalize(raw));
    }

    private static boolean hasInstallExt(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        for (String e : INSTALL_EXTS) {
            if (low.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /** 归一化后再去掉扩展名，用于通知 / 日志里的可读名称。 */
    static String pretty(String raw) {
        String s = normalize(raw);
        String low = s.toLowerCase(Locale.ROOT);
        for (String e : INSTALL_EXTS) {
            if (low.endsWith(e)) {
                return s.substring(0, s.length() - e.length());
            }
        }
        return s;
    }
}
