//! 身份认证与授权。
//!
//! 机制：客户端白名单 + 启动阶段下发的共享令牌（常量时间比较）+ 每客户端
//! nonce 去重 + 请求时间窗口 + 授权撤销。非法客户端在认证阶段即被拒绝。
//! 令牌由后端启动流程通过用户显式授权动作下发，不在协议内传输明文口令。

use std::collections::{HashMap, HashSet, VecDeque};

use crate::error::ErrorCode;
use crate::message::{BackendKind, Body, ClientIdentity, Envelope, PROTOCOL_VERSION};

pub struct AuthConfig {
    /// 启动阶段生成并下发的共享令牌。
    pub token: String,
    /// 允许连接的客户端标识集合；不在此集合内直接拒绝。
    pub allowed_clients: HashSet<String>,
    /// 请求时间戳允许的最大偏移，同时作为 nonce 记录的有效期。
    pub clock_skew_ms: u64,
    /// 每个客户端最多保留的 nonce 记录条数。
    pub nonce_capacity: usize,
}

impl AuthConfig {
    pub fn new(token: impl Into<String>, clients: &[&str]) -> Self {
        Self {
            token: token.into(),
            allowed_clients: clients.iter().map(|s| (*s).to_string()).collect(),
            clock_skew_ms: 30_000,
            nonce_capacity: 512,
        }
    }
}

pub struct Authenticator {
    cfg: AuthConfig,
    /// client_id -> (nonce, issued_at_ms) 的近期记录。
    seen: HashMap<String, VecDeque<(u64, u64)>>,
    revoked_clients: HashSet<String>,
    revoked_tokens: HashSet<String>,
}

impl Authenticator {
    pub fn new(cfg: AuthConfig) -> Self {
        Self {
            cfg,
            seen: HashMap::new(),
            revoked_clients: HashSet::new(),
            revoked_tokens: HashSet::new(),
        }
    }

    pub fn is_client_allowed(&self, client_id: &str) -> bool {
        self.cfg.allowed_clients.contains(client_id)
            && !self.revoked_clients.contains(client_id)
    }

    /// 撤销某个客户端的接入资格，已建立的会话由 SessionTable 另行撤销。
    pub fn revoke_client(&mut self, client_id: &str) {
        self.revoked_clients.insert(client_id.to_string());
    }

    /// 使某个令牌立即失效，用于怀疑泄露时轮换。
    pub fn revoke_token(&mut self, token: &str) {
        self.revoked_tokens.insert(token.to_string());
    }

    /// 校验一条消息。任何失败都返回明确错误码，不吞异常、不降级放行。
    pub fn verify(&mut self, env: &Envelope, now_ms: u64) -> Result<ClientIdentity, ErrorCode> {
        if env.version != PROTOCOL_VERSION {
            return Err(ErrorCode::VersionMismatch);
        }
        if !self.is_client_allowed(&env.client_id) {
            return Err(ErrorCode::ClientRejected);
        }
        // 先判定消息本身是否已过旧（防重放窗口），再判定处理是否超过截止时间。
        // 顺序固定，保证同一条消息总是返回同一个错误码。
        if now_ms.abs_diff(env.issued_at_ms) > self.cfg.clock_skew_ms {
            return Err(ErrorCode::SessionExpired);
        }
        if now_ms > env.deadline_ms {
            return Err(ErrorCode::Timeout);
        }

        // nonce 去重：同一客户端重复 nonce 视为重放。
        self.prune_nonces(&env.client_id, now_ms);
        let bucket = self.seen.entry(env.client_id.clone()).or_default();
        if bucket.iter().any(|(n, _)| *n == env.nonce) {
            return Err(ErrorCode::ReplayDetected);
        }

        // Hello 额外校验令牌与协议版本声明。
        if let Body::Hello {
            token,
            advertised_version,
        } = &env.body
        {
            if *advertised_version != PROTOCOL_VERSION {
                return Err(ErrorCode::VersionMismatch);
            }
            if self.revoked_tokens.contains(token)
                || !constant_time_eq(token.as_bytes(), self.cfg.token.as_bytes())
            {
                return Err(ErrorCode::Unauthenticated);
            }
        }

        bucket.push_back((env.nonce, env.issued_at_ms));
        while bucket.len() > self.cfg.nonce_capacity {
            bucket.pop_front();
        }

        Ok(ClientIdentity {
            client_id: env.client_id.clone(),
            uid: -1,
            backend: BackendKind::Unknown,
        })
    }

    fn prune_nonces(&mut self, client_id: &str, now_ms: u64) {
        let cutoff = now_ms.saturating_sub(self.cfg.clock_skew_ms);
        if let Some(bucket) = self.seen.get_mut(client_id) {
            while let Some((_, issued)) = bucket.front() {
                if *issued < cutoff {
                    bucket.pop_front();
                } else {
                    break;
                }
            }
        }
    }
}

/// 常量时间比较：长度差异也参与累加，避免通过长度提前返回形成时序差异。
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    let mut diff = a.len() ^ b.len();
    let n = a.len().max(b.len());
    for i in 0..n {
        let x = a.get(i).copied().unwrap_or(0);
        let y = b.get(i).copied().unwrap_or(0);
        diff |= (x ^ y) as usize;
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::message::StdinSpec;

    const TOKEN: &str = "0123456789abcdef0123456789abcdef";

    fn hello(nonce: u64, issued: u64, token: &str) -> Envelope {
        Envelope::new(
            1,
            0,
            "client-a",
            nonce,
            issued,
            issued + 10_000,
            Body::Hello {
                token: token.to_string(),
                advertised_version: PROTOCOL_VERSION,
            },
        )
    }

    fn exec(nonce: u64, issued: u64) -> Envelope {
        Envelope::new(
            2,
            5,
            "client-a",
            nonce,
            issued,
            issued + 10_000,
            Body::Exec {
                argv: vec!["pm".into(), "install-create".into()],
                stdin: StdinSpec::None,
                env: vec![],
                timeout_ms: 5000,
                cancel_group: 1,
            },
        )
    }

    #[test]
    fn valid_hello_is_authenticated() {
        let mut a = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        let id = a.verify(&hello(1, 1000, TOKEN), 1000).unwrap();
        assert_eq!(id.client_id, "client-a");
        assert_eq!(id.uid, -1, "uid 必须由服务端实测后填入");
    }

    #[test]
    fn wrong_token_is_rejected() {
        let mut a = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        assert_eq!(
            a.verify(&hello(1, 1000, "wrong-token"), 1000),
            Err(ErrorCode::Unauthenticated)
        );
    }

    #[test]
    fn unknown_client_is_rejected_before_token_check() {
        let mut a = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        let mut e = hello(1, 1000, TOKEN);
        e.client_id = "intruder".into();
        assert_eq!(a.verify(&e, 1000), Err(ErrorCode::ClientRejected));
    }

    #[test]
    fn replayed_nonce_is_rejected() {
        let mut a = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        assert!(a.verify(&hello(7, 1000, TOKEN), 1000).is_ok());
        // 同一 nonce 换一种请求类型再次发送
        assert_eq!(a.verify(&exec(7, 1000), 1001), Err(ErrorCode::ReplayDetected));
    }

    #[test]
    fn stale_timestamp_and_expired_deadline_are_rejected() {
        let mut a = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        let stale = hello(1, 1000, TOKEN);
        assert_eq!(a.verify(&stale, 1000 + 30_001), Err(ErrorCode::SessionExpired));

        let mut b = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        let mut expired = hello(2, 1000, TOKEN);
        expired.deadline_ms = 900;
        assert_eq!(b.verify(&expired, 1000), Err(ErrorCode::Timeout));
    }

    #[test]
    fn version_mismatch_is_rejected() {
        let mut a = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        let mut e = hello(1, 1000, TOKEN);
        e.version = PROTOCOL_VERSION + 1;
        assert_eq!(a.verify(&e, 1000), Err(ErrorCode::VersionMismatch));

        let mut b = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        let mut e2 = hello(2, 1000, TOKEN);
        if let Body::Hello { advertised_version, .. } = &mut e2.body {
            *advertised_version = 99;
        }
        assert_eq!(b.verify(&e2, 1000), Err(ErrorCode::VersionMismatch));
    }

    #[test]
    fn revocation_takes_effect_immediately() {
        let mut a = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        assert!(a.verify(&hello(1, 1000, TOKEN), 1000).is_ok());
        a.revoke_client("client-a");
        assert_eq!(a.verify(&exec(2, 1001), 1001), Err(ErrorCode::ClientRejected));

        let mut b = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        b.revoke_token(TOKEN);
        assert_eq!(b.verify(&hello(1, 1000, TOKEN), 1000), Err(ErrorCode::Unauthenticated));
    }

    #[test]
    fn nonce_bucket_is_bounded() {
        let mut cfg = AuthConfig::new(TOKEN, &["client-a"]);
        cfg.nonce_capacity = 4;
        let mut a = Authenticator::new(cfg);
        for n in 1..=10 {
            assert!(a.verify(&exec(n, 1000 + n), 1000 + n).is_ok());
        }
        assert!(a.seen.get("client-a").unwrap().len() <= 4);
    }

    #[test]
    fn constant_time_compare_behaves() {
        assert!(constant_time_eq(b"secret", b"secret"));
        assert!(!constant_time_eq(b"secret", b"secreT"));
        assert!(!constant_time_eq(b"secret", b"secret-longer"));
    }
}
