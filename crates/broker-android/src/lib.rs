//! Android 平台状态与启动契约。
//!
//! 只依据公开机制：进程 uid 由系统分配，无线调试下以 shell 身份启动的
//! 进程 uid 为 2000；ADB 权限集合随系统版本变化，因此后端可用性必须以
//! 实测 uid 与真实命令结果判定，不以理论推断代替。

use broker_protocol::message::BackendKind;

/// 实测 uid（host 侧不可用 libc 时返回 -1）。
pub fn current_uid() -> i32 {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        unsafe { libc_getuid() as i32 }
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        -1
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
unsafe fn libc_getuid() -> u32 {
    extern "C" { fn getuid() -> u32; }
    getuid()
}

/// 依据实测 uid 判定后端。只有 2000 认定为 ADB Shell。
pub fn backend_for_uid(uid: i32) -> BackendKind {
    if uid == BackendKind::AdbShell.expected_uid() {
        BackendKind::AdbShell
    } else {
        BackendKind::Unknown
    }
}

/// 平台状态快照。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlatformState {
    pub uid: i32,
    pub backend: BackendKind,
    pub selinux: String,
    pub ready: bool,
}

impl PlatformState {
    pub fn probe(require_shell_uid: bool) -> Self {
        let uid = current_uid();
        let backend = backend_for_uid(uid);
        let ready = !require_shell_uid || backend == BackendKind::AdbShell;
        Self {
            uid,
            backend,
            selinux: selinux_state(),
            ready,
        }
    }
}

/// 读取 SELinux 状态；不可读时如实返回 unknown，不猜测。
pub fn selinux_state() -> String {
    match std::fs::read_to_string("/sys/fs/selinux/enforce") {
        Ok(s) => match s.trim() {
            "1" => "enforcing".into(),
            "0" => "permissive".into(),
            other => format!("unknown({other})"),
        },
        Err(_) => "unknown".into(),
    }
}

/// 抽象 socket 名称由启动令牌派生，避免与其它服务冲突。
pub fn socket_name(token: &str) -> String {
    use sha2::{Digest, Sha256};
    let mut h = Sha256::new();
    h.update(b"privilege-broker/socket/v2");
    h.update(token.as_bytes());
    let d = h.finalize();
    let mut s = String::from("prvbrk.");
    for b in &d[..8] {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

/// 启动参数契约。用户通过无线调试执行时显式传入。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StartupConfig {
    pub token: String,
    pub allowed_clients: Vec<String>,
    pub endpoint: String,
    pub session_ttl_ms: u64,
    pub require_shell_uid: bool,
}

impl Default for StartupConfig {
    fn default() -> Self {
        Self {
            token: String::new(),
            allowed_clients: vec!["installer".into()],
            endpoint: "abstract".into(),
            session_ttl_ms: 60_000,
            require_shell_uid: true,
        }
    }
}

impl StartupConfig {
    /// 解析 `--key value` 形式参数。未知键报错，不静默忽略。
    pub fn parse(args: &[String]) -> Result<Self, String> {
        let mut c = Self::default();
        let mut i = 0;
        while i < args.len() {
            let k = &args[i];
            if !k.starts_with("--") || i + 1 >= args.len() {
                return Err(format!("参数格式错误: {k}"));
            }
            let v = &args[i + 1];
            match k.as_str() {
                "--token" => c.token = v.clone(),
                "--client" => c.allowed_clients = v.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect(),
                "--endpoint" => c.endpoint = v.clone(),
                "--session-ttl-ms" => c.session_ttl_ms = v.parse().map_err(|_| "--session-ttl-ms 需为整数".to_string())?,
                // 不提供关闭 uid 校验的启动开关：服务只在实测 shell(2000) 下运行。
                // 任何绕过都会让其它 uid 的进程获得执行能力，与 ADB-only 约束冲突。
                _ => return Err(format!("未知参数: {k}")),
            }
            i += 2;
        }
        if c.token.len() < 16 {
            return Err("令牌长度不足 16 字符".into());
        }
        if c.allowed_clients.is_empty() {
            return Err("客户端白名单不能为空".into());
        }
        Ok(c)
    }

    /// 生成给用户看的启动命令（无线调试 / adb shell 下执行）。
    pub fn startup_hint(&self) -> String {
        format!(
            "端点: {} · 客户端白名单: {} · 会话 TTL: {}ms · 令牌前缀: {}… · 要求实测 shell uid 2000",
            self.endpoint,
            self.allowed_clients.join(","),
            self.session_ttl_ms,
            &self.token[..self.token.len().min(6)],
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_shell_uid_maps_to_adb_backend() {
        assert_eq!(backend_for_uid(2000), BackendKind::AdbShell);
        assert_eq!(backend_for_uid(0), BackendKind::Unknown, "root 不作为后端");
        assert_eq!(backend_for_uid(10001), BackendKind::Unknown);
        assert_eq!(backend_for_uid(-1), BackendKind::Unknown);
    }

    #[test]
    fn socket_name_is_stable_and_token_bound() {
        let a = socket_name("token-a");
        let b = socket_name("token-a");
        let c = socket_name("token-b");
        assert_eq!(a, b);
        assert_ne!(a, c);
        assert!(a.starts_with("prvbrk."));
        assert!(a.len() <= 106);
    }

    #[test]
    fn startup_parse_requires_token_and_clients() {
        let ok = StartupConfig::parse(&[
            "--token".into(), "0123456789abcdef0123456789abcdef".into(),
            "--client".into(), "installer,ui".into(),
        ]).unwrap();
        assert_eq!(ok.allowed_clients, vec!["installer", "ui"]);
        assert!(ok.require_shell_uid);
        assert!(StartupConfig::parse(&["--token".into(), "short".into()]).is_err());
        assert!(StartupConfig::parse(&["--bogus".into(), "x".into()]).is_err());
        assert!(StartupConfig::parse(&["--token".into()]).is_err());
    }

    #[test]
    fn uid_gate_cannot_be_disabled_from_cli() {
        assert!(StartupConfig::parse(&[
            "--token".into(), "0123456789abcdef0123456789abcdef".into(),
            "--allow-any-uid".into(), "true".into(),
        ]).is_err(), "不得提供绕过 uid 校验的启动参数");
        assert!(StartupConfig::default().require_shell_uid);
    }

    #[test]
    fn selinux_state_is_never_empty() {
        assert!(!selinux_state().is_empty());
    }

    #[test]
    fn platform_probe_reports_real_state() {
        let s = PlatformState::probe(false);
        assert_eq!(s.uid, current_uid());
        assert_eq!(s.backend, backend_for_uid(s.uid));
        assert!(s.ready || !s.ready); // 恒真，确保字段可读
    }
}
