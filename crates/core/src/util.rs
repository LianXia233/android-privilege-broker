use crate::error::{CoreError, Result};

pub fn hex_decode(s: &str) -> Result<Vec<u8>> {
    let b = s.as_bytes();
    if b.len() % 2 != 0 {
        return Err(CoreError::Bridge("hex 长度非偶数".into()));
    }
    let mut out = Vec::with_capacity(b.len() / 2);
    let mut i = 0;
    while i < b.len() {
        let hi = hex_val(b[i]).ok_or_else(|| CoreError::Bridge("非法 hex 字符".into()))?;
        let lo = hex_val(b[i + 1]).ok_or_else(|| CoreError::Bridge("非法 hex 字符".into()))?;
        out.push((hi << 4) | lo);
        i += 2;
    }
    Ok(out)
}

fn hex_val(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

/// Android 包名规则：至少两段，段以字母开头，仅含 [A-Za-z0-9_]。
/// 用于防止把用户输入直接拼进 shell 命令造成参数注入。
pub fn is_valid_package_name(s: &str) -> bool {
    if s.is_empty() || s.len() > 255 {
        return false;
    }
    let segs: Vec<&str> = s.split('.').collect();
    if segs.len() < 2 {
        return false;
    }
    segs.iter().all(|seg| {
        !seg.is_empty()
            && seg.as_bytes()[0].is_ascii_alphabetic()
            && seg.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
    })
}

/// 支持的真实扩展名（长在前，避免 `.apk` 抢先匹配 `.apks`）。
const INSTALL_EXTS: &[&str] = &[".apkmx", ".apks", ".xapk", ".apkm", ".zip", ".akp", ".apk"];
/// 社交软件常见的非数字重命名后缀。
const RENAME_EXTS: &[&str] = &["bin", "download", "tmp", "bak", "temp", "save"];

fn has_install_ext(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    INSTALL_EXTS.iter().any(|e| low.ends_with(e))
}

/// 剥离社交软件转发时追加的备份后缀。
///
/// 微信 / QQ 等把 `xxx.apk` 改成 `xxx.apk.1`（也有 `.2`、多位数字、
/// `.bin` / `.download` 等）以绕过系统安装器识别，但文件本体仍是 APK。
/// 多次转发会叠加成 `xxx.apk.1.1`，因此先把连续的「点 + 纯数字（≤3 位）」
/// 或已知重命名后缀全部剥掉，再校验剩下的名字是否仍以某个安装包扩展名
/// 结尾；不是就回退原名，避免把 `1.apk` / `note.txt` 之类误判。
pub fn normalize_name(raw: &str) -> String {
    let base = raw
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or("")
        .trim()
        .to_string();
    let mut s = base.clone();
    for _ in 0..4 {
        let Some(dot) = s.rfind('.') else { break };
        if dot == 0 || dot + 1 >= s.len() {
            break;
        }
        let tail = s[dot + 1..].to_ascii_lowercase();
        let numeric = !tail.is_empty()
            && tail.len() <= 3
            && tail.bytes().all(|b| b.is_ascii_digit());
        let renamed = RENAME_EXTS.contains(&tail.as_str());
        if !numeric && !renamed {
            break;
        }
        s = s[..dot].to_string();
    }
    if has_install_ext(&s) {
        s
    } else {
        base
    }
}

/// 把 SAF 提供的显示名推导为输入类型。
///
/// 归档类（apks / xapk / apkm / zip）都按 zip 处理：内部只挑 `.apk` 条目，
/// `base.apk` 优先，因此不同发行渠道的打包格式可以共用一条解析路径。
/// 判定前先剥掉社交软件追加的 `.1` / `.bin` 等后缀，`xxx.apk.1` 因此
/// 仍按单个 APK 处理，`xxx.apks.1` 仍按归档处理。
pub fn kind_of(display_name: &str) -> &'static str {
    let n = normalize_name(display_name).to_ascii_lowercase();
    if n.ends_with(".apks") {
        "apks"
    } else if n.ends_with(".xapk") || n.ends_with(".akp") {
        "xapk"
    } else if n.ends_with(".apkm") || n.ends_with(".apkmx") {
        "apkm"
    } else if n.ends_with(".apk") {
        "apk"
    } else if n.ends_with(".zip") {
        "zip"
    } else {
        "unknown"
    }
}

/// 是否为可安装的输入类型（供 UI 侧做早失败提示，不做策略判断）。
pub fn is_installable_kind(kind: &str) -> bool {
    matches!(kind, "apk" | "apks" | "xapk" | "apkm" | "zip")
}

/// 生成合法的 split 名，避免路径分隔符与冲突。
pub fn normalize_split_name(raw: &str, index: usize, used: &[String]) -> String {
    let leaf = raw.rsplit(['/', '\\']).next().unwrap_or("").trim();
    let mut name: String = if leaf.is_empty() {
        format!("split_{index}.apk")
    } else {
        leaf.chars()
            .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-' { c } else { '_' })
            .collect()
    };
    if name.len() > 96 {
        name.truncate(96);
    }
    if used.contains(&name) {
        let base = name.clone();
        let mut n = 2;
        while used.contains(&name) {
            name = format!("{base}.{n}");
            n += 1;
        }
    }
    name
}

/// 常见 pm install / uninstall 错误码的中文说明，用于通知与日志展示。
/// 未收录的码返回空串，调用方保留原始码。
pub fn error_zh(code: &str) -> &'static str {
    let c = code.trim().to_ascii_uppercase();
    match c.as_str() {
        "SUCCESS" => "操作成功",
        "BROKER_NOT_READY" => "特权代理未就绪或未授权",
        "PREPARE_FAILED" => "安装准备失败",
        "CALLER_DENIED" => "调用方未获静默安装授权",
        "SESSION" => "创建安装会话失败",
        "WRITE" => "写入安装包失败",
        "COMMIT" => "提交安装失败",
        "INSTALL_FAILED_ALREADY_EXISTS" => "应用已存在，请先卸载或允许覆盖",
        "INSTALL_FAILED_INVALID_APK" => "安装包无效或已损坏",
        "INSTALL_FAILED_INVALID_URI" => "安装包地址无效",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" => "存储空间不足",
        "INSTALL_FAILED_DUPLICATE_PACKAGE" => "包名冲突",
        "INSTALL_FAILED_NO_SHARED_USER" => "共享用户签名不匹配",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" => "签名不一致，无法覆盖安装",
        "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" => "共享用户不兼容",
        "INSTALL_FAILED_MISSING_SHARED_LIBRARY" => "缺少共享库",
        "INSTALL_FAILED_DEXOPT" => "dex 优化失败",
        "INSTALL_FAILED_OLDER_SDK" => "应用不支持当前系统版本",
        "INSTALL_FAILED_NEWER_SDK" => "应用要求的系统版本过高",
        "INSTALL_FAILED_TEST_ONLY" => "该包为测试包，不允许安装",
        "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" => "CPU 架构不兼容",
        "INSTALL_FAILED_MISSING_FEATURE" => "设备缺少所需功能",
        "INSTALL_FAILED_VERSION_DOWNGRADE" => "不允许降级安装",
        "INSTALL_FAILED_PERMISSION_MODEL_UPGRADE" => "权限模型升级失败",
        "INSTALL_FAILED_PARSE_FAILED" => "安装包解析失败",
        "INSTALL_FAILED_ABORTED" => "安装被中止",
        "INSTALL_FAILED_USER_RESTRICTED" => "系统限制安装来源",
        "INSTALL_FAILED_BAD_SIGNATURE" => "签名校验失败",
        "DELETE_FAILED_INTERNAL_ERROR" => "卸载失败（内部错误）",
        "DELETE_FAILED_DEVICE_POLICY_MANAGER" => "设备管理器阻止卸载",
        "DELETE_FAILED_USER_RESTRICTED" => "系统限制卸载",
        "DELETE_FAILED_OWNER_BLOCKED" => "被设备所有者阻止",
        "SHELL_ERROR" => "Shell 命令执行失败",
        "BRIDGE" => "平台桥接调用失败",
        "ARCHIVE" => "安装包归档无效",
        "REJECTED" => "操作被拒绝",
        "INVALID_ARGUMENT" => "参数无效",
        _ => {
            if c.starts_with("INSTALL_FAILED_") {
                "安装失败"
            } else if c.starts_with("DELETE_FAILED_") {
                "卸载失败"
            } else {
                ""
            }
        }
    }
}

pub fn human_size(bytes: u64) -> String {
    const U: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut v = bytes as f64;
    let mut i = 0;
    while v >= 1024.0 && i < U.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if i == 0 {
        format!("{} {}", bytes, U[i])
    } else {
        format!("{:.2} {}", v, U[i])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_strips_social_app_backup_suffix() {
        assert_eq!(normalize_name("微信.apk.1"), "微信.apk");
        assert_eq!(normalize_name("app.APK.1"), "app.APK");
        assert_eq!(normalize_name("app.apk.2"), "app.apk");
        assert_eq!(normalize_name("app.apk.12"), "app.apk");
        assert_eq!(normalize_name("app.apk.1.1"), "app.apk");
        assert_eq!(normalize_name("app.apks.1"), "app.apks");
        assert_eq!(normalize_name("app.xapk.1"), "app.xapk");
        assert_eq!(normalize_name("app.apkm.1"), "app.apkm");
        assert_eq!(normalize_name("app.zip.1"), "app.zip");
        assert_eq!(normalize_name("app.apk.bin"), "app.apk");
        assert_eq!(normalize_name("app.apk.download"), "app.apk");
        assert_eq!(normalize_name("/sdcard/Download/app.apk.1"), "app.apk");
    }

    #[test]
    fn normalize_keeps_real_names_intact() {
        assert_eq!(normalize_name("app.apk"), "app.apk");
        assert_eq!(normalize_name("1.apk"), "1.apk");
        assert_eq!(normalize_name("v2.3.apk"), "v2.3.apk");
        assert_eq!(normalize_name("1234"), "1234");
        assert_eq!(normalize_name("note.txt"), "note.txt");
        assert_eq!(normalize_name(""), "");
    }

    #[test]
    fn kind_of_handles_backup_suffix() {
        assert_eq!(kind_of("app.apk.1"), "apk");
        assert_eq!(kind_of("app.APK.1"), "apk");
        assert_eq!(kind_of("app.apks.1"), "apks");
        assert_eq!(kind_of("app.xapk.1"), "xapk");
        assert_eq!(kind_of("app.akp.1"), "xapk");
        assert_eq!(kind_of("app.apkm.1"), "apkm");
        assert_eq!(kind_of("app.apkmx.1"), "apkm");
        assert_eq!(kind_of("app.zip.1"), "zip");
        assert_eq!(kind_of("app.apk"), "apk");
        assert_eq!(kind_of("note.txt"), "unknown");
    }

    #[test]
    fn installable_kind_matches_supported_set() {
        assert!(is_installable_kind("apk"));
        assert!(is_installable_kind("apks"));
        assert!(is_installable_kind("zip"));
        assert!(!is_installable_kind("unknown"));
    }
}
