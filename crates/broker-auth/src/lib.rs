//! 挑战响应认证。
//!
//! 设计目标：令牌不反复出现在链路上。客户端在 Hello 阶段提交令牌后，
//! 服务端下发一次性随机数，客户端用「令牌 + 随机数 + 客户端标识 + 时间」
//! 计算证明；服务端独立重算并做常量时间比较。随机数只能消费一次，
//! 过期挑战与错序消息都会被拒绝。

use std::collections::HashMap;

use broker_protocol::error::ErrorCode;
use sha2::{Digest, Sha256};

/// 证明计算所用的域分隔前缀，避免与其它哈希用途混淆。
const PROOF_DOMAIN: &[u8] = b"privilege-broker/v2/challenge-proof";

/// 计算挑战证明。返回小写 hex 的 SHA-256 摘要。
pub fn compute_proof(token: &str, server_nonce: u64, client_id: &str, issued_at_ms: u64) -> String {
    let mut h = Sha256::new();
    h.update(PROOF_DOMAIN);
    h.update((token.len() as u64).to_be_bytes());
    h.update(token.as_bytes());
    h.update(server_nonce.to_be_bytes());
    h.update((client_id.len() as u64).to_be_bytes());
    h.update(client_id.as_bytes());
    h.update(issued_at_ms.to_be_bytes());
    hex::encode(h.finalize())
}

/// 常量时间比较，长度差异同样参与累加。
pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    let mut diff = a.len() ^ b.len();
    let n = a.len().max(b.len());
    for i in 0..n {
        diff |= (a.get(i).copied().unwrap_or(0) ^ b.get(i).copied().unwrap_or(0)) as usize;
    }
    diff == 0
}

#[derive(Debug, Clone)]
struct PendingChallenge {
    nonce: u64,
    issued_at_ms: u64,
}

/// 服务端挑战状态表。按客户端隔离，防止跨客户端借用挑战。
pub struct ChallengeTable {
    pending: HashMap<String, PendingChallenge>,
    ttl_ms: u64,
}

impl ChallengeTable {
    pub fn new(ttl_ms: u64) -> Self {
        Self { pending: HashMap::new(), ttl_ms }
    }

    /// 为客户端生成一个挑战随机数。相同客户端的旧挑战会被覆盖。
    pub fn issue(&mut self, client_id: &str, nonce: u64, now_ms: u64) -> u64 {
        self.pending.insert(
            client_id.to_string(),
            PendingChallenge { nonce, issued_at_ms: now_ms },
        );
        nonce
    }

    /// 校验客户端证明。成功即消费挑战，二次提交必然失败。
    pub fn verify(
        &mut self,
        client_id: &str,
        token: &str,
        server_nonce: u64,
        proof_hex: &str,
        issued_at_ms: u64,
        now_ms: u64,
    ) -> Result<(), ErrorCode> {
        let pending = self.pending.remove(client_id).ok_or(ErrorCode::Unauthenticated)?;
        if pending.nonce != server_nonce {
            return Err(ErrorCode::Unauthenticated);
        }
        if now_ms.saturating_sub(pending.issued_at_ms) > self.ttl_ms {
            return Err(ErrorCode::SessionExpired);
        }
        let expected = compute_proof(token, server_nonce, client_id, issued_at_ms);
        let actual = hex::decode(proof_hex).map_err(|_| ErrorCode::Unauthenticated)?;
        let expected_bytes = hex::decode(&expected).map_err(|_| ErrorCode::Internal)?;
        if !constant_time_eq(&actual, &expected_bytes) {
            return Err(ErrorCode::Unauthenticated);
        }
        Ok(())
    }

    /// 清理过期挑战，返回被清理的客户端数。
    pub fn purge(&mut self, now_ms: u64) -> usize {
        let before = self.pending.len();
        self.pending.retain(|_, p| now_ms.saturating_sub(p.issued_at_ms) <= self.ttl_ms);
        before - self.pending.len()
    }

    pub fn pending_count(&self) -> usize {
        self.pending.len()
    }
}

/// 生成一次性随机数的确定性混合器。
///
/// 服务端进程本身不依赖系统随机源的可得性：以启动种子、单调计数与墙钟时间
/// 混合出 64 位 nonce，保证同一进程内不重复、不同进程间也不同。
pub struct NonceGenerator {
    seed: u64,
    counter: u64,
}

impl NonceGenerator {
    pub fn new(seed: u64) -> Self {
        Self { seed, counter: 0 }
    }

    pub fn next(&mut self, now_ms: u64) -> u64 {
        self.counter = self.counter.wrapping_add(1).max(1);
        let mut h = Sha256::new();
        h.update(self.seed.to_be_bytes());
        h.update(self.counter.to_be_bytes());
        h.update(now_ms.to_be_bytes());
        let d = h.finalize();
        u64::from_be_bytes([d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proof_is_deterministic_and_token_bound() {
        let a = compute_proof("token", 9, "client-a", 1000);
        let b = compute_proof("token", 9, "client-a", 1000);
        assert_eq!(a, b);
        assert_ne!(a, compute_proof("other", 9, "client-a", 1000));
        assert_ne!(a, compute_proof("token", 10, "client-a", 1000));
        assert_ne!(a, compute_proof("token", 9, "client-b", 1000));
        assert_ne!(a, compute_proof("token", 9, "client-a", 1001));
        assert_eq!(a.len(), 64);
    }

    #[test]
    fn constant_time_eq_handles_length_and_content() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"abcd"));
        assert!(constant_time_eq(b"", b""));
    }

    #[test]
    fn challenge_can_only_be_consumed_once() {
        let mut t = ChallengeTable::new(30_000);
        t.issue("client-a", 777, 1_000);
        let proof = compute_proof("token", 777, "client-a", 1_100);
        assert!(t.verify("client-a", "token", 777, &proof, 1_100, 1_200).is_ok());
        assert_eq!(
            t.verify("client-a", "token", 777, &proof, 1_100, 1_200),
            Err(ErrorCode::Unauthenticated)
        );
    }

    #[test]
    fn wrong_nonce_token_or_proof_is_rejected() {
        let mut t = ChallengeTable::new(30_000);
        t.issue("c", 5, 0);
        let good = compute_proof("token", 5, "c", 10);
        assert_eq!(t.verify("c", "token", 6, &good, 10, 20), Err(ErrorCode::Unauthenticated));

        t.issue("c", 5, 0);
        assert_eq!(t.verify("c", "bad", 5, &good, 10, 20), Err(ErrorCode::Unauthenticated));

        t.issue("c", 5, 0);
        assert_eq!(t.verify("c", "token", 5, "00", 10, 20), Err(ErrorCode::Unauthenticated));

        t.issue("c", 5, 0);
        assert_eq!(t.verify("c", "token", 5, "zz", 10, 20), Err(ErrorCode::Unauthenticated));
    }

    #[test]
    fn stale_challenge_expires() {
        let mut t = ChallengeTable::new(100);
        t.issue("c", 1, 0);
        let proof = compute_proof("token", 1, "c", 10);
        assert_eq!(t.verify("c", "token", 1, &proof, 10, 500), Err(ErrorCode::SessionExpired));
    }

    #[test]
    fn challenges_are_isolated_per_client() {
        let mut t = ChallengeTable::new(30_000);
        t.issue("client-a", 1, 0);
        let proof = compute_proof("token", 1, "client-b", 10);
        assert_eq!(t.verify("client-b", "token", 1, &proof, 10, 20), Err(ErrorCode::Unauthenticated));
        assert_eq!(t.pending_count(), 1, "A 的挑战不应被 B 的失败消费");
    }

    #[test]
    fn purge_drops_expired_only() {
        let mut t = ChallengeTable::new(100);
        t.issue("old", 1, 0);
        t.issue("fresh", 2, 900);
        assert_eq!(t.purge(1_000), 1);
        assert_eq!(t.pending_count(), 1);
    }

    #[test]
    fn nonce_generator_never_repeats() {
        let mut g = NonceGenerator::new(42);
        let mut seen = std::collections::HashSet::new();
        for i in 0..500 {
            assert!(seen.insert(g.next(1_000 + i)));
        }
        let mut other = NonceGenerator::new(43);
        assert_ne!(g.next(2_000), other.next(2_000));
    }
}
