package io.github.lianxia233.privbroker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 把「包名 / 文件名」解析成用户看得懂的应用名称（中文优先）。
 * 纯 I/O + 字符串清洗，不做任何业务决策。
 */
final class AppNames {
    private static final String FALLBACK = "安装包";

    private AppNames() {
    }

    /** 已安装应用的真实名称；查不到返回 null。 */
    static String labelForPackage(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.startsWith("@")) {
            return null;
        }
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = info.loadLabel(pm);
            if (label != null) {
                String s = label.toString().trim();
                if (!s.isEmpty() && !s.equals(pkg)) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 目标包当前是否仍处于安装状态。
     *
     * <p>只有明确的 {@link PackageManager.NameNotFoundException} 才算未安装；
     * 其它查询异常按「仍安装」处理，避免把查询失败误判成卸载已完成。</p>
     */
    static boolean isInstalled(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.startsWith("@")) {
            return false;
        }
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 授权弹窗里的调用方名称。 */
    static String labelForCaller(Context c, String caller) {
        if (caller == null || caller.isEmpty() || "@unknown".equals(caller)) {
            return c.getString(R.string.caller_unknown);
        }
        if ("@self".equals(caller)) {
            return c.getString(R.string.app_name);
        }
        String label = labelForPackage(c, caller);
        return label == null ? caller : label;
    }

    /** 内容提供者的显示名，拿不到则用 URI 末段。 */
    static String displayName(Context c, String uriString) {
        String name = "";
        try {
            Uri u = Uri.parse(uriString);
            try (Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    int n = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (n >= 0 && !cur.isNull(n)) {
                        name = cur.getString(n);
                    }
                }
            }
            if (name == null || name.isEmpty()) {
                String seg = u.getLastPathSegment();
                name = seg == null ? "" : seg;
            }
        } catch (Throwable ignored) {
        }
        return name == null ? "" : name;
    }

    /** 把 xxx_8.0.40.apk / base.apk 之类的文件名清洗成可读名称。 */
    static String prettyFileName(String raw) {
        if (raw == null) {
            return null;
        }
        // 社交软件转发的 xxx.apk.1 先归一化成 xxx.apk，再去扩展名。
        String s = FileNames.normalize(raw);
        String low = s.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".apkmx", ".apks", ".xapk", ".apkm", ".zip", ".akp", ".apk"}) {
            if (low.endsWith(ext)) {
                s = s.substring(0, s.length() - ext.length());
                break;
            }
        }
        s = s.replace('_', ' ').replace('-', ' ').trim();
        // 去掉尾部版本号： 「 8.0.40」「 v1.2.3」「 1234」
        s = s.replaceAll("(?i)\\s+v?\\d+(\\.\\d+){0,3}\\s*$", "").trim();
        s = s.replaceAll("\\s{2,}", " ");
        if (s.isEmpty() || looksLikePackage(s) || isGeneric(s)) {
            return null;
        }
        if (s.length() > 48) {
            s = s.substring(0, 48).trim();
        }
        return s;
    }

    private static boolean isGeneric(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.equals("base") || l.startsWith("base ") || l.startsWith("split ")
                || l.startsWith("split_config") || l.equals("app") || l.equals("update")
                || l.equals("package") || l.equals("download") || l.matches("\\d+");
    }

    private static boolean looksLikePackage(String s) {
        String t = s.trim();
        if (t.contains(" ")) {
            return false;
        }
        int dots = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '.') {
                dots++;
            }
        }
        return dots >= 2;
    }

    /**
     * 综合解析安装显示名：已安装应用名 > AXML 标签 > 清洗后的文件名 > 兜底。
     */
    static String resolveInstallLabel(Context c, String firstUri, JSONObject summary) {
        String pkg = summary == null ? "" : summary.optString("package", "");
        String axml = summary == null ? "" : summary.optString("appLabel", "");
        if ("null".equals(pkg)) {
            pkg = "";
        }
        if ("null".equals(axml)) {
            axml = "";
        }
        String installed = labelForPackage(c, pkg);
        if (installed != null) {
            return installed;
        }
        if (!axml.isEmpty() && !axml.startsWith("@") && !axml.startsWith("0x")
                && !looksLikePackage(axml)) {
            return axml.length() > 64 ? axml.substring(0, 64) : axml;
        }
        String pretty = prettyFileName(displayName(c, firstUri));
        if (pretty != null) {
            return pretty;
        }
        return FALLBACK;
    }

    /** 通知/日志里展示的中文原因，保留原始码便于排查。 */
    static String errorText(Context c, String code, String raw) {
        String k = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (k.isEmpty() && raw != null) {
            int a = raw.indexOf("INSTALL_FAILED_");
            int b = raw.indexOf("DELETE_FAILED_");
            int p = a >= 0 ? a : b;
            if (p >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = p; i < raw.length(); i++) {
                    char ch = raw.charAt(i);
                    if (Character.isLetterOrDigit(ch) || ch == '_') {
                        sb.append(ch);
                    } else {
                        break;
                    }
                }
                k = sb.toString();
            }
        }
        String zh = zhOf(k);
        if (zh == null) {
            zh = zhOfPrefix(k);
        }
        String detail = raw == null ? "" : raw.trim();
        if (zh == null) {
            return detail.isEmpty() ? (k.isEmpty() ? c.getString(R.string.error_unknown) : k) : detail;
        }
        return detail.isEmpty() ? zh : zh + "（" + truncate(detail, 120) + "）";
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String zhOfPrefix(String k) {
        if (k.startsWith("INSTALL_FAILED_")) {
            return "安装失败";
        }
        if (k.startsWith("DELETE_FAILED_")) {
            return "卸载失败";
        }
        return null;
    }

    private static String zhOf(String k) {
        switch (k) {
            case "BROKER_NOT_READY": return "特权代理未就绪或未授权";
            case "PREPARE_FAILED": return "安装准备失败";
            case "CALLER_DENIED": return "调用方未获静默安装授权";
            case "SESSION": return "创建安装会话失败";
            case "WRITE": return "写入安装包失败";
            case "COMMIT": return "提交安装失败";
            case "INSTALL_FAILED_ALREADY_EXISTS": return "应用已存在，请先卸载或允许覆盖";
            case "INSTALL_FAILED_INVALID_APK": return "安装包无效或已损坏";
            case "INSTALL_FAILED_INVALID_URI": return "安装包地址无效";
            case "INSTALL_FAILED_INSUFFICIENT_STORAGE": return "存储空间不足";
            case "INSTALL_FAILED_DUPLICATE_PACKAGE": return "包名冲突";
            case "INSTALL_FAILED_UPDATE_INCOMPATIBLE": return "签名不一致，无法覆盖安装";
            case "INSTALL_FAILED_NO_SHARED_USER": return "共享用户签名不匹配";
            case "INSTALL_FAILED_MISSING_SHARED_LIBRARY": return "缺少共享库";
            case "INSTALL_FAILED_DEXOPT": return "dex 优化失败";
            case "INSTALL_FAILED_OLDER_SDK": return "应用不支持当前系统版本";
            case "INSTALL_FAILED_NEWER_SDK": return "应用要求的系统版本过高";
            case "INSTALL_FAILED_TEST_ONLY": return "该包为测试包，不允许安装";
            case "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE": return "CPU 架构不兼容";
            case "INSTALL_FAILED_VERSION_DOWNGRADE": return "不允许降级安装";
            case "INSTALL_FAILED_PARSE_FAILED": return "安装包解析失败";
            case "INSTALL_FAILED_ABORTED": return "安装被中止";
            case "INSTALL_FAILED_USER_RESTRICTED": return "系统限制安装来源";
            case "DELETE_FAILED_INTERNAL_ERROR": return "卸载失败（内部错误）";
            case "DELETE_FAILED_DEVICE_POLICY_MANAGER": return "设备管理器阻止卸载";
            case "DELETE_FAILED_USER_RESTRICTED": return "系统限制卸载";
            case "SHELL_ERROR": return "Shell 命令执行失败";
            case "BRIDGE": return "平台桥接调用失败";
            case "ARCHIVE": return "安装包归档无效";
            case "INVALID_ARGUMENT": return "参数无效";
            default: return null;
        }
    }
}
