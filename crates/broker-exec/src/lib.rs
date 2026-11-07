//! 命令执行规格与结果模型。
//!
//! 只做纯数据转换与校验，不触碰进程、socket 或文件系统；
//! 因此可在 host 侧完整单测。实际进程创建在 `broker-process`。

use broker_protocol::error::ErrorCode;
use broker_protocol::message::{Body, Envelope, StdinSpec};
use serde::{Deserialize, Serialize};

/// stdin 解析后的具体来源。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum StdinData {
    None,
    /// 直接内存字节。
    Bytes(Vec<u8>),
    /// 由特权进程自行打开的绝对路径（大文件避免整包进帧）。
    Path(String),
}

/// 一条可执行的命令规格。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecSpec {
    pub argv: Vec<String>,
    pub stdin: StdinData,
    pub env: Vec<(String, String)>,
    pub timeout_ms: u64,
    pub cancel_group: u64,
}

/// 一条命令的执行结果。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExecOutcome {
    pub code: i32,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    pub timed_out: bool,
    pub cancelled: bool,
}

impl ExecOutcome {
    pub fn to_json(&self) -> serde_json::Value {
        serde_json::json!({
            "code": self.code,
            "stdout": String::from_utf8_lossy(&self.stdout),
            "stderr": String::from_utf8_lossy(&self.stderr),
            "timedOut": self.timed_out,
            "cancelled": self.cancelled,
        })
    }

    /// 从平台桥接返回的 JSON（`code/stdout/stderr`）还原，兼容旧契约。
    pub fn from_platform_json(v: &serde_json::Value) -> Self {
        let code = v.get("code").and_then(serde_json::Value::as_i64).unwrap_or(-1) as i32;
        let text = |k: &str| v.get(k).and_then(serde_json::Value::as_str).unwrap_or("").as_bytes().to_vec();
        Self {
            code,
            stdout: text("stdout"),
            stderr: text("stderr"),
            timed_out: v.get("timedOut").and_then(serde_json::Value::as_bool).unwrap_or(false),
            cancelled: v.get("cancelled").and_then(serde_json::Value::as_bool).unwrap_or(false),
        }
    }
}

/// 从协议信封提取执行规格，并做完整校验。
pub fn extract(envelope: &Envelope) -> Result<ExecSpec, ErrorCode> {
    envelope.validate_exec()?;
    match &envelope.body {
        Body::Exec { argv, stdin, env, timeout_ms, cancel_group } => {
            let stdin = parse_stdin(stdin)?;
            for (k, _) in env {
                if k.is_empty() || k.contains('=') || k.contains('\0') {
                    return Err(ErrorCode::InvalidRequest);
                }
            }
            Ok(ExecSpec {
                argv: argv.clone(),
                stdin,
                env: env.clone(),
                timeout_ms: *timeout_ms,
                cancel_group: *cancel_group,
            })
        }
        _ => Err(ErrorCode::InvalidRequest),
    }
}

/// stdin 规格解析。`Bytes` 做严格 hex 校验；`Path` 只接受绝对路径，
/// 拒绝 NUL 与换行，避免把路径拼成其它用途；`Uri`/`ArchiveEntry` 由
/// 客户端侧先落地为可读文件再以 `Path` 传入，特权进程不理解 content://。
pub fn parse_stdin(spec: &StdinSpec) -> Result<StdinData, ErrorCode> {
    match spec {
        StdinSpec::None => Ok(StdinData::None),
        StdinSpec::Bytes { hex } => {
            let bytes = hex::decode(hex).map_err(|_| ErrorCode::InvalidRequest)?;
            if bytes.len() > 16 * 1024 * 1024 {
                return Err(ErrorCode::FrameTooLarge);
            }
            Ok(StdinData::Bytes(bytes))
        }
        StdinSpec::Path { path } => {
            if !path.starts_with('/') || path.contains('\0') || path.contains('\n') || path.contains('\r') {
                return Err(ErrorCode::InvalidRequest);
            }
            Ok(StdinData::Path(path.clone()))
        }
        StdinSpec::Uri { .. } | StdinSpec::ArchiveEntry { .. } => {
            // 特权进程没有 ContentResolver，URI 必须由客户端预先落地。
            Err(ErrorCode::InvalidRequest)
        }
    }
}

impl ExecSpec {
    pub fn program(&self) -> &str {
        &self.argv[0]
    }
    pub fn args(&self) -> &[String] {
        &self.argv[1..]
    }
    /// 命令是否属于包管理会话链路（用于白名单审计，不用于放行判定）。
    pub fn is_package_manager(&self) -> bool {
        matches!(self.program(), "pm" | "cmd" | "appops")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use broker_protocol::message::PROTOCOL_VERSION;

    fn exec_env(argv: Vec<String>, stdin: StdinSpec, timeout_ms: u64) -> Envelope {
        Envelope::new(1, 9, "client-a", 1, 1_000, 5_000, Body::Exec {
            argv,
            stdin,
            env: vec![],
            timeout_ms,
            cancel_group: 0,
        })
    }

    #[test]
    fn extract_valid_exec_spec() {
        let e = exec_env(vec!["pm".into(), "install-create".into(), "-r".into()], StdinSpec::None, 30_000);
        let s = extract(&e).unwrap();
        assert_eq!(s.program(), "pm");
        assert_eq!(s.args(), &["install-create", "-r"]);
        assert_eq!(s.timeout_ms, 30_000);
        assert!(s.is_package_manager());
        assert_eq!(s.stdin, StdinData::None);
        assert_eq!(e.version, PROTOCOL_VERSION);
    }

    #[test]
    fn hex_stdin_is_decoded_strictly() {
        let e = exec_env(vec!["pm".into()], StdinSpec::Bytes { hex: "0a0bFF".into() }, 1000);
        assert_eq!(extract(&e).unwrap().stdin, StdinData::Bytes(vec![10, 11, 255]));
        let bad = exec_env(vec!["pm".into()], StdinSpec::Bytes { hex: "zz".into() }, 1000);
        assert_eq!(extract(&bad), Err(ErrorCode::InvalidRequest));
    }

    #[test]
    fn path_stdin_requires_absolute_path() {
        let ok = exec_env(vec!["pm".into()], StdinSpec::Path { path: "/data/local/tmp/b.apk".into() }, 1000);
        assert_eq!(extract(&ok).unwrap().stdin, StdinData::Path("/data/local/tmp/b.apk".into()));
        for p in ["relative.apk", "/a\0b", "/a\nb"] {
            let e = exec_env(vec!["pm".into()], StdinSpec::Path { path: p.into() }, 1000);
            assert_eq!(extract(&e), Err(ErrorCode::InvalidRequest), "路径 {p:?} 应被拒绝");
        }
    }

    #[test]
    fn uri_stdin_must_be_resolved_by_client_first() {
        let e = exec_env(vec!["pm".into()], StdinSpec::Uri { uri: "content://x".into() }, 1000);
        assert_eq!(extract(&e), Err(ErrorCode::InvalidRequest));
    }

    #[test]
    fn invalid_env_key_is_rejected() {
        let mut e = exec_env(vec!["pm".into()], StdinSpec::None, 1000);
        if let Body::Exec { env, .. } = &mut e.body {
            env.push(("A=B".into(), "v".into()));
        }
        assert_eq!(extract(&e), Err(ErrorCode::InvalidRequest));
    }

    #[test]
    fn empty_and_zero_timeout_rejected() {
        assert_eq!(extract(&exec_env(vec![], StdinSpec::None, 1000)), Err(ErrorCode::InvalidRequest));
        assert_eq!(extract(&exec_env(vec!["pm".into()], StdinSpec::None, 0)), Err(ErrorCode::InvalidRequest));
    }

    #[test]
    fn outcome_json_roundtrip_with_platform_contract() {
        let out = ExecOutcome { code: 0, stdout: b"Success".to_vec(), stderr: vec![], timed_out: false, cancelled: false };
        let j = out.to_json();
        assert_eq!(j["code"], 0);
        assert_eq!(j["stdout"], "Success");
        let back = ExecOutcome::from_platform_json(&serde_json::json!({
            "code": -1, "stdout": "", "stderr": "broken pipe"
        }));
        assert_eq!(back.code, -1);
        assert_eq!(back.stderr, b"broken pipe");
    }
}
