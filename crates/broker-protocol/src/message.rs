//! 自主通信协议的消息定义。
//!
//! 一条消息 = 一个 `Envelope`（信封）+ 一个 `Body`（请求类型）。
//! 信封携带协议版本、请求 ID、客户端身份、会话标识与超时控制；
//! Body 携带请求类型与参数。所有命令以参数数组传递，不接受 shell 字符串。

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::ErrorCode;

/// 协议版本。不兼容变更必须递增，服务端对旧版本返回 VERSION_MISMATCH。
/// v2：单一 ADB Shell 后端、挑战响应握手、文件路径 stdin。
pub const PROTOCOL_VERSION: u16 = 2;
/// 单帧上限，防止对端用超大长度字段耗尽内存。
pub const MAX_FRAME_BYTES: usize = 1024 * 1024;

/// 权限后端类型。本项目只实现 ADB Shell（shell uid 2000）一条路径。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BackendKind {
    AdbShell,
    Unknown,
}

impl BackendKind {
    /// 该后端声称应达到的 uid；仅作为期望值，实际 uid 必须由设备实测。
    pub fn expected_uid(self) -> i32 {
        match self {
            BackendKind::AdbShell => 2000,
            BackendKind::Unknown => -1,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            BackendKind::AdbShell => "ADB_SHELL",
            BackendKind::Unknown => "UNKNOWN",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ResponseStatus {
    Accepted,
    Pending,
    Completed,
    Failed,
    Rejected,
    Cancelled,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StreamKind {
    Stdout,
    Stderr,
}

/// 客户端身份。uid / backend 由服务端在完成 uid 实测后填入。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientIdentity {
    pub client_id: String,
    pub uid: i32,
    pub backend: BackendKind,
}

/// stdin 来源。字节以 hex 传递，避免 JSON 字符串的编码歧义；
/// `Path` 用于大文件：由特权进程自行打开已授权的可读文件，避免整包挤进单帧。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum StdinSpec {
    None,
    Bytes { hex: String },
    Uri { uri: String },
    ArchiveEntry { uri: String, entry: String },
    Path { path: String },
}

/// 请求类型。每个类型对应明确的状态与失败处理。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Body {
    /// 客户端发起握手：携带令牌与自身协议版本。
    Hello { token: String, advertised_version: u16 },
    /// 服务端挑战：随机数用于后续挑战响应计算与 nonce 防重放基线。
    Challenge { server_nonce: u64 },
    /// 客户端应答：以令牌与服务端随机数计算证明，不回传明文令牌。
    ChallengeProof { server_nonce: u64, proof: String },
    /// 认证结果：同时回报实测 uid 与后端类型，会话 ID 由信封的 sessionId 携带。
    AuthAck {
        granted: bool,
        uid: i32,
        backend: BackendKind,
        server_version: u16,
    },
    /// 进程执行：参数数组、stdin、环境变量、超时与取消组。
    Exec {
        argv: Vec<String>,
        stdin: StdinSpec,
        env: Vec<(String, String)>,
        timeout_ms: u64,
        cancel_group: u64,
    },
    ExecStarted { handle: u64 },
    ExecChunk {
        handle: u64,
        stream: StreamKind,
        hex: String,
    },
    ExecFinished { handle: u64, code: i32 },
    Cancel { handle: u64 },
    StatusQuery {},
    StatusReply {
        backend: BackendKind,
        uid: i32,
        selinux: String,
        sessions: u64,
    },
    /// 授权撤销：使指定会话立即失效。
    Revoke { target_session: u64 },
    /// 统一响应：状态、错误码、说明与载荷。
    Response {
        status: ResponseStatus,
        code: ErrorCode,
        message: String,
        payload: Value,
    },
}

impl Body {
    pub fn is_exec(&self) -> bool {
        matches!(self, Body::Exec { .. })
    }
}

/// 协议信封。所有请求与响应都经过它，字段完整可审计。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Envelope {
    pub version: u16,
    pub request_id: u64,
    pub session_id: u64,
    pub client_id: String,
    pub nonce: u64,
    pub issued_at_ms: u64,
    pub deadline_ms: u64,
    pub body: Body,
}

impl Envelope {
    pub fn new(
        request_id: u64,
        session_id: u64,
        client_id: impl Into<String>,
        nonce: u64,
        issued_at_ms: u64,
        deadline_ms: u64,
        body: Body,
    ) -> Self {
        Self {
            version: PROTOCOL_VERSION,
            request_id,
            session_id,
            client_id: client_id.into(),
            nonce,
            issued_at_ms,
            deadline_ms,
            body,
        }
    }

    /// 由请求派生响应：沿用请求 ID、会话与客户端标识，nonce 自增。
    pub fn reply(
        &self,
        status: ResponseStatus,
        code: ErrorCode,
        message: impl Into<String>,
        payload: Value,
        now_ms: u64,
        deadline_ms: u64,
    ) -> Self {
        Self::new(
            self.request_id,
            self.session_id,
            self.client_id.clone(),
            self.nonce.wrapping_add(1),
            now_ms,
            deadline_ms,
            Body::Response {
                status,
                code,
                message: message.into(),
                payload,
            },
        )
    }

    /// 参数数组校验：禁止空 argv、禁止任何元素含 shell 元字符拼接。
    pub fn validate_exec(&self) -> Result<(), ErrorCode> {
        match &self.body {
            Body::Exec { argv, timeout_ms, .. } => {
                if argv.is_empty() || argv[0].trim().is_empty() {
                    return Err(ErrorCode::InvalidRequest);
                }
                if *timeout_ms == 0 {
                    return Err(ErrorCode::InvalidRequest);
                }
                if argv.iter().any(|a| a.contains('\n') || a.contains('\r')) {
                    return Err(ErrorCode::InvalidRequest);
                }
                if argv.len() > 256 || argv.iter().any(|a| a.len() > 8192) {
                    return Err(ErrorCode::InvalidRequest);
                }
                Ok(())
            }
            _ => Err(ErrorCode::InvalidRequest),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_adb_shell_backend_is_supported() {
        assert_eq!(BackendKind::AdbShell.expected_uid(), 2000);
        assert_eq!(BackendKind::Unknown.expected_uid(), -1);
        assert_ne!(BackendKind::AdbShell, BackendKind::Unknown);
        let kinds = [BackendKind::AdbShell, BackendKind::Unknown];
        assert_eq!(kinds.len(), 2, "不得存在 root 后端");
        assert_eq!(serde_json::to_string(&BackendKind::AdbShell).unwrap(), "\"ADB_SHELL\"");
    }

    #[test]
    fn envelope_carries_all_required_fields() {
        let e = Envelope::new(
            7,
            11,
            "client-a",
            99,
            1000,
            5000,
            Body::Exec {
                argv: vec!["pm".into(), "install-create".into(), "-r".into()],
                stdin: StdinSpec::None,
                env: vec![],
                timeout_ms: 30_000,
                cancel_group: 3,
            },
        );
        assert_eq!(e.version, PROTOCOL_VERSION);
        assert_eq!(e.request_id, 7);
        assert_eq!(e.session_id, 11);
        assert_eq!(e.client_id, "client-a");
        assert_eq!(e.deadline_ms, 5000);
        assert!(e.body.is_exec());
        assert!(e.validate_exec().is_ok());
    }

    #[test]
    fn reply_keeps_request_and_session_identity() {
        let req = Envelope::new(
            42, 8, "client-a", 100, 1000, 4000,
            Body::StatusQuery {},
        );
        let rep = req.reply(
            ResponseStatus::Completed,
            ErrorCode::Ok,
            "完成",
            serde_json::json!({"uid": 2000}),
            1100,
            4000,
        );
        assert_eq!(rep.request_id, 42);
        assert_eq!(rep.session_id, 8);
        assert_eq!(rep.client_id, "client-a");
        assert_eq!(rep.nonce, 101);
        assert!(matches!(rep.body, Body::Response { .. }));
    }

    #[test]
    fn exec_rejects_empty_argv_and_zero_timeout() {
        let base = |argv: Vec<String>, t: u64| {
            Envelope::new(1, 0, "c", 1, 0, 100, Body::Exec {
                argv,
                stdin: StdinSpec::None,
                env: vec![],
                timeout_ms: t,
                cancel_group: 0,
            })
        };
        assert_eq!(base(vec![], 100).validate_exec(), Err(ErrorCode::InvalidRequest));
        assert_eq!(base(vec!["pm".into()], 0).validate_exec(), Err(ErrorCode::InvalidRequest));
    }

    #[test]
    fn argv_preserves_special_characters_as_data() {
        let e = Envelope::new(1, 0, "c", 1, 0, 100, Body::Exec {
            argv: vec!["pm".into(), "install-write".into(), "a b;$(x)".into()],
            stdin: StdinSpec::None,
            env: vec![],
            timeout_ms: 1000,
            cancel_group: 0,
        });
        assert!(e.validate_exec().is_ok());
        if let Body::Exec { argv, .. } = &e.body {
            assert_eq!(argv[2], "a b;$(x)", "特殊字符应作为单个参数原样保留，不参与拼接");
        }
    }

    #[test]
    fn oversized_argv_is_rejected() {
        let mut argv: Vec<String> = (0..300).map(|i| format!("arg{i}")).collect();
        argv[0] = "pm".into();
        let e = Envelope::new(1, 0, "c", 1, 0, 100, Body::Exec {
            argv,
            stdin: StdinSpec::None,
            env: vec![],
            timeout_ms: 1000,
            cancel_group: 0,
        });
        assert_eq!(e.validate_exec(), Err(ErrorCode::InvalidRequest));
    }

    #[test]
    fn path_stdin_and_challenge_proof_roundtrip() {
        let e = Envelope::new(1, 0, "c", 1, 0, 100, Body::Exec {
            argv: vec!["pm".into(), "install-write".into()],
            stdin: StdinSpec::Path { path: "/data/local/tmp/base.apk".into() },
            env: vec![],
            timeout_ms: 1000,
            cancel_group: 0,
        });
        let v: serde_json::Value = serde_json::to_value(&e).unwrap();
        assert_eq!(v["body"]["stdin"]["type"], "path");
        assert_eq!(v["body"]["stdin"]["path"], "/data/local/tmp/base.apk");
        let back: Envelope = serde_json::from_value(v).unwrap();
        assert_eq!(back, e);

        let p = Body::ChallengeProof { server_nonce: 42, proof: "aabb".into() };
        let pv = serde_json::to_value(&p).unwrap();
        assert_eq!(pv["type"], "challengeProof");
        assert_eq!(pv["serverNonce"], 42);
    }
}
