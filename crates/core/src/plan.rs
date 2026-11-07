use crate::model::{InstallOptions, UninstallOptions};

fn s(v: &str) -> String {
    v.to_string()
}

/// `pm install-create` 参数。所有参数由 Rust 生成，不接受自由文本拼接。
pub fn install_create_args(opt: &InstallOptions, total_size: u64) -> Vec<String> {
    let mut a = vec![s("pm"), s("install-create")];
    if opt.replace {
        a.push(s("-r"));
    }
    if opt.grant_runtime_permissions {
        a.push(s("-g"));
    }
    if opt.allow_downgrade {
        a.push(s("-d"));
    }
    if let Some(u) = opt.user_id {
        if u >= 0 {
            a.push(s("--user"));
            a.push(u.to_string());
        }
    }
    if let Some(ref pkg) = opt.installer_package {
        if crate::util::is_valid_package_name(pkg) {
            a.push(s("--installer-package-name"));
            a.push(pkg.clone());
        }
    }
    if total_size > 0 {
        a.push(s("-S"));
        a.push(total_size.to_string());
    }
    a
}

/// `pm install-write -S <size> <session> <split-name> -`
/// 末尾 `-` 表示从 stdin 读取字节流，避免把 APK 落到磁盘。
pub fn install_write_args(session: i64, split_name: &str, size: u64) -> Vec<String> {
    let mut a = vec![s("pm"), s("install-write")];
    if size > 0 {
        a.push(s("-S"));
        a.push(size.to_string());
    }
    a.push(session.to_string());
    a.push(split_name.to_string());
    a.push(s("-"));
    a
}

/// stdin 流式写入失败时的兜底：`pm install-write -S <size> <session> <name> <path>`。
/// AOSP `runInstallWrite` 的第 4 个参数是文件路径（`-` 才是 stdin），
/// shell 身份可直接读取该路径，完全绕开跨进程 stdin 管道。
pub fn install_write_path_args(
    session: i64,
    split_name: &str,
    size: u64,
    path: &str,
) -> Vec<String> {
    let mut a = vec![s("pm"), s("install-write")];
    if size > 0 {
        a.push(s("-S"));
        a.push(size.to_string());
    }
    a.push(session.to_string());
    a.push(split_name.to_string());
    a.push(path.to_string());
    a
}

pub fn install_commit_args(session: i64) -> Vec<String> {
    vec![s("pm"), s("install-commit"), session.to_string()]
}

pub fn install_abandon_args(session: i64) -> Vec<String> {
    vec![s("pm"), s("install-abandon"), session.to_string()]
}

pub fn uninstall_args(pkg: &str, opt: &UninstallOptions) -> Vec<String> {
    let mut a = vec![s("pm"), s("uninstall")];
    if opt.keep_data {
        a.push(s("-k"));
    }
    if let Some(u) = opt.user_id {
        if u >= 0 {
            a.push(s("--user"));
            a.push(u.to_string());
        }
    }
    a.push(pkg.to_string());
    a
}

pub fn resolve_activity_args(pkg: &str) -> Vec<String> {
    vec![
        s("cmd"), s("package"), s("resolve-activity"), s("--brief"),
        s("-c"), s("android.intent.category.LAUNCHER"), s(pkg),
    ]
}

pub fn am_start_args(component: &str) -> Vec<String> {
    vec![s("am"), s("start"), s("-n"), s(component)]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_args_defaults() {
        let a = install_create_args(&InstallOptions::default(), 1234);
        assert_eq!(a, vec!["pm", "install-create", "-r", "-g", "-S", "1234"]);
    }

    #[test]
    fn create_args_full() {
        let opt = InstallOptions {
            allow_downgrade: true,
            user_id: Some(0),
            installer_package: Some("io.github.lianxia233.privbroker".into()),
            ..Default::default()
        };
        let a = install_create_args(&opt, 0);
        assert_eq!(
            a,
            vec![
                "pm", "install-create", "-r", "-g", "-d", "--user", "0",
                "--installer-package-name", "io.github.lianxia233.privbroker"
            ]
        );
    }

    #[test]
    fn invalid_installer_is_dropped() {
        let opt = InstallOptions {
            installer_package: Some("bad; rm -rf /".into()),
            ..Default::default()
        };
        let a = install_create_args(&opt, 10);
        assert!(!a.iter().any(|x| x.contains("rm")));
    }

    #[test]
    fn write_args_use_stdin() {
        assert_eq!(
            install_write_args(42, "base.apk", 99),
            vec!["pm", "install-write", "-S", "99", "42", "base.apk", "-"]
        );
    }

    #[test]
    fn write_path_args_use_file_path() {
        assert_eq!(
            install_write_path_args(42, "base.apk", 99, "/sdcard/stage/x.apk"),
            vec!["pm", "install-write", "-S", "99", "42", "base.apk", "/sdcard/stage/x.apk"]
        );
    }

    #[test]
    fn uninstall_args_keep_data() {
        assert_eq!(
            uninstall_args("a.b.c", &UninstallOptions { keep_data: true, user_id: Some(10) }),
            vec!["pm", "uninstall", "-k", "--user", "10", "a.b.c"]
        );
    }
}
