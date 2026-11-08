//! 协议层错误码。全部由本项目定义，不依赖任何外部框架的枚举。

use serde::{Deserialize, Serialize};

/// 机器可读的错误码。客户端与服务端据此做失败处理，不依赖文本匹配。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    Ok,
    VersionMismatch,
    MalformedFrame,
    FrameTooLarge,
    Unauthenticated,
    /// 挑战阶段按 nonce 精确匹配槽位失败：该客户端没有待验证挑战，或
    /// server_nonce 不在任何槽位里（过期、被淘汰、或与登记值不同）。
    /// 与 [`ErrorCode::Unauthenticated`] 区分开，避免把「槽位没命中」
    /// 和「令牌不对」混成同一个错误码而无法定位。
    ChallengeUnknown,
    /// 挑战槽位命中但证明不成立：令牌、client_id 或 issued_at 与服务端
    /// 登记值不一致，或证明不是合法十六进制。
    ProofMismatch,
    ClientRejected,
    ReplayDetected,
    SessionExpired,
    SessionRevoked,
    SessionUnknown,
    InvalidRequest,
    Timeout,
    Cancelled,
    ExecSpawnFailed,
    ExecWaitFailed,
    StdinWriteFailed,
    BackendUnavailable,
    PermissionDenied,
    Internal,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            ErrorCode::Ok => "OK",
            ErrorCode::VersionMismatch => "VERSION_MISMATCH",
            ErrorCode::MalformedFrame => "MALFORMED_FRAME",
            ErrorCode::FrameTooLarge => "FRAME_TOO_LARGE",
            ErrorCode::Unauthenticated => "UNAUTHENTICATED",
            ErrorCode::ChallengeUnknown => "CHALLENGE_UNKNOWN",
            ErrorCode::ProofMismatch => "PROOF_MISMATCH",
            ErrorCode::ClientRejected => "CLIENT_REJECTED",
            ErrorCode::ReplayDetected => "REPLAY_DETECTED",
            ErrorCode::SessionExpired => "SESSION_EXPIRED",
            ErrorCode::SessionRevoked => "SESSION_REVOKED",
            ErrorCode::SessionUnknown => "SESSION_UNKNOWN",
            ErrorCode::InvalidRequest => "INVALID_REQUEST",
            ErrorCode::Timeout => "TIMEOUT",
            ErrorCode::Cancelled => "CANCELLED",
            ErrorCode::ExecSpawnFailed => "EXEC_SPAWN_FAILED",
            ErrorCode::ExecWaitFailed => "EXEC_WAIT_FAILED",
            ErrorCode::StdinWriteFailed => "STDIN_WRITE_FAILED",
            ErrorCode::BackendUnavailable => "BACKEND_UNAVAILABLE",
            ErrorCode::PermissionDenied => "PERMISSION_DENIED",
            ErrorCode::Internal => "INTERNAL",
        }
    }

    pub fn is_ok(self) -> bool {
        matches!(self, ErrorCode::Ok)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_stable_and_distinct() {
        let all = [
            ErrorCode::Ok,
            ErrorCode::VersionMismatch,
            ErrorCode::MalformedFrame,
            ErrorCode::FrameTooLarge,
            ErrorCode::Unauthenticated,
            ErrorCode::ChallengeUnknown,
            ErrorCode::ProofMismatch,
            ErrorCode::ClientRejected,
            ErrorCode::ReplayDetected,
            ErrorCode::SessionExpired,
            ErrorCode::SessionRevoked,
            ErrorCode::SessionUnknown,
            ErrorCode::InvalidRequest,
            ErrorCode::Timeout,
            ErrorCode::Cancelled,
            ErrorCode::ExecSpawnFailed,
            ErrorCode::ExecWaitFailed,
            ErrorCode::StdinWriteFailed,
            ErrorCode::BackendUnavailable,
            ErrorCode::PermissionDenied,
            ErrorCode::Internal,
        ];
        let mut names: Vec<&str> = all.iter().map(|c| c.as_str()).collect();
        names.sort_unstable();
        let before = names.len();
        names.dedup();
        assert_eq!(before, names.len(), "错误码字符串不得重复");
        assert!(ErrorCode::Ok.is_ok());
        assert!(!ErrorCode::Timeout.is_ok());
    }

    #[test]
    fn error_code_roundtrips_through_json() {
        let s = serde_json::to_string(&ErrorCode::ReplayDetected).unwrap();
        assert_eq!(s, "\"REPLAY_DETECTED\"");
        let back: ErrorCode = serde_json::from_str(&s).unwrap();
        assert_eq!(back, ErrorCode::ReplayDetected);
    }
}
