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

/// 挑战随机数的上界：53 位。
///
/// 原因：nonce 以 JSON 整数在链路上往返，而 JSON 数字在客户端（Android
/// org.json 的 JSONTokener）会被解析成 long 或 double —— 可精确表示的整数
/// 区间只到 2^53（double 尾数位）。超过该上限时，客户端拿到的值不再等于
/// 服务端登记的值（精度丢失或饱和成 Long.MAX_VALUE），后续用这个值计算
/// 证明并按值精确匹配槽位必然落空，表现为「Hello 通过、Proof 被判
/// UNAUTHENTICATED」。把 nonce 限制在 53 位以内，双端数值才完全一致。
/// 2^53 的空间配合 30s 重放窗口，碰撞概率可以忽略。
const NONCE_MAX: u64 = (1u64 << 53) - 1;

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

/// 每个客户端同时允许挂起的挑战数量上限。
///
/// 客户端每次桥接调用都会新建连接并独立握手（状态查询、安装各步骤、焦点
/// 快照都可能在同一个客户端标识下并发），因此不能假定「同一客户端同一时刻
/// 只有一个握手」。槽位太少时后到的 Hello 会顶掉先到的挑战，先到的证明就会
/// 被当成无效挑战而返回 UNAUTHENTICATED。
const MAX_PENDING_PER_CLIENT: usize = 8;

#[derive(Debug, Clone)]
struct PendingChallenge {
    nonce: u64,
    issued_at_ms: u64,
}

/// 服务端挑战状态表。按客户端隔离，防止跨客户端借用挑战；同一客户端可同时
/// 持有多个待验证挑战，按 nonce 精确匹配与消费。
pub struct ChallengeTable {
    pending: HashMap<String, Vec<PendingChallenge>>,
    ttl_ms: u64,
}

impl ChallengeTable {
    pub fn new(ttl_ms: u64) -> Self {
        Self { pending: HashMap::new(), ttl_ms }
    }

    /// 为客户端登记一个挑战随机数。返回值与入参一致，便于调用方直接回传。
    ///
    /// 先清掉该客户端已过期的槽位，再在容量上限内追加；只有超出上限时才
    /// 淘汰最早的槽位，避免无限增长。
    pub fn issue(&mut self, client_id: &str, nonce: u64, now_ms: u64) -> u64 {
        let slots = self.pending.entry(client_id.to_string()).or_default();
        slots.retain(|p| now_ms.saturating_sub(p.issued_at_ms) <= self.ttl_ms);
        slots.push(PendingChallenge { nonce, issued_at_ms: now_ms });
        while slots.len() > MAX_PENDING_PER_CLIENT {
            slots.remove(0);
        }
        nonce
    }

    /// 校验客户端证明。成功即消费对应挑战，二次提交必然失败。
    ///
    /// 按 (client_id, nonce) 精确匹配：错误 nonce 不会消费该客户端的其它
    /// 合法挑战，也不会影响其它客户端。
    ///
    /// 返回码按失败原因区分，便于现场日志直接定位：槽位未命中（没有待验证
    /// 挑战，或 nonce 与服务端登记值不同）为 [`ErrorCode::ChallengeUnknown`]；
    /// 槽位命中但证明不成立为 [`ErrorCode::ProofMismatch`]；挑战超期为
    /// [`ErrorCode::SessionExpired`]。
    pub fn verify(
        &mut self,
        client_id: &str,
        token: &str,
        server_nonce: u64,
        proof_hex: &str,
        issued_at_ms: u64,
        now_ms: u64,
    ) -> Result<(), ErrorCode> {
        let (pending, slot_left_empty) = {
            let slots = self
                .pending
                .get_mut(client_id)
                .ok_or(ErrorCode::ChallengeUnknown)?;
            let pos = slots
                .iter()
                .position(|p| p.nonce == server_nonce)
                .ok_or(ErrorCode::ChallengeUnknown)?;
            let pending = slots.remove(pos);
            (pending, slots.is_empty())
        };
        if slot_left_empty {
            self.pending.remove(client_id);
        }
        if now_ms.saturating_sub(pending.issued_at_ms) > self.ttl_ms {
            return Err(ErrorCode::SessionExpired);
        }
        let expected = compute_proof(token, server_nonce, client_id, issued_at_ms);
        let actual = hex::decode(proof_hex).map_err(|_| ErrorCode::ProofMismatch)?;
        let expected_bytes = hex::decode(&expected).map_err(|_| ErrorCode::Internal)?;
        if !constant_time_eq(&actual, &expected_bytes) {
            return Err(ErrorCode::ProofMismatch);
        }
        Ok(())
    }

    /// 清理过期挑战，返回被清理的挑战条数。
    pub fn purge(&mut self, now_ms: u64) -> usize {
        let mut removed = 0;
        self.pending.retain(|_, slots| {
            let before = slots.len();
            slots.retain(|p| now_ms.saturating_sub(p.issued_at_ms) <= self.ttl_ms);
            removed += before - slots.len();
            !slots.is_empty()
        });
        removed
    }

    pub fn pending_count(&self) -> usize {
        self.pending.values().map(|s| s.len()).sum()
    }
}

/// 生成一次性随机数的确定性混合器。
///
/// 服务端进程本身不依赖系统随机源的可得性：以启动种子、单调计数与墙钟时间
/// 混合出随机数，保证同一进程内不重复、不同进程间也不同。输出截断到
/// [`NONCE_MAX`]（53 位），使客户端用 JSON 数字解析后仍能得到同一个值。
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
        u64::from_be_bytes([d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]]) & NONCE_MAX
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
    fn nonces_fit_json_safe_range() {
        // 下发的 nonce 必须落在 double/JSON 能精确表示的整数区间内，
        // 否则客户端解析后与服务端登记值不一致，握手必然失败。
        let mut g = NonceGenerator::new(0x9E37_79B9_7F4A_7C15);
        let mut seen = std::collections::HashSet::new();
        for i in 0..64u64 {
            let n = g.next(1_700_000_000_000 + i);
            assert!(n <= NONCE_MAX, "nonce 超出 53 位：{n}");
            assert!(n < (1u64 << 53));
            assert!(seen.insert(n), "同一进程内出现重复 nonce：{n}");
        }
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
            Err(ErrorCode::ChallengeUnknown)
        );
    }

    #[test]
    fn wrong_nonce_token_or_proof_is_rejected() {
        let mut t = ChallengeTable::new(30_000);
        t.issue("c", 5, 0);
        let good = compute_proof("token", 5, "c", 10);
        assert_eq!(
            t.verify("c", "token", 6, &good, 10, 20),
            Err(ErrorCode::ChallengeUnknown)
        );

        t.issue("c", 5, 0);
        assert_eq!(
            t.verify("c", "bad", 5, &good, 10, 20),
            Err(ErrorCode::ProofMismatch)
        );

        t.issue("c", 5, 0);
        assert_eq!(
            t.verify("c", "token", 5, "00", 10, 20),
            Err(ErrorCode::ProofMismatch)
        );

        t.issue("c", 5, 0);
        assert_eq!(
            t.verify("c", "token", 5, "zz", 10, 20),
            Err(ErrorCode::ProofMismatch)
        );
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
        assert_eq!(
            t.verify("client-b", "token", 1, &proof, 10, 20),
            Err(ErrorCode::ChallengeUnknown)
        );
        assert_eq!(t.pending_count(), 1, "A 的挑战不应被 B 的失败消费");
    }

    #[test]
    fn interleaved_handshakes_from_same_client_are_independent() {
        // 现场故障的复现：同一客户端（应用每次桥接调用都会新建连接）先后发出
        // 两个 Hello，后到的挑战不能顶掉先到的挑战，否则先到连接的证明会被
        // 判成 UNAUTHENTICATED。
        let mut t = ChallengeTable::new(30_000);
        t.issue("installer", 1_001, 1_000);
        t.issue("installer", 1_002, 1_010);
        assert_eq!(t.pending_count(), 2);

        let proof_a = compute_proof("token", 1_001, "installer", 1_100);
        assert!(t.verify("installer", "token", 1_001, &proof_a, 1_100, 1_200).is_ok());

        let proof_b = compute_proof("token", 1_002, "installer", 1_110);
        assert!(t.verify("installer", "token", 1_002, &proof_b, 1_110, 1_210).is_ok());
        assert_eq!(t.pending_count(), 0);
    }

    #[test]
    fn unknown_nonce_does_not_consume_legitimate_challenge() {
        let mut t = ChallengeTable::new(30_000);
        t.issue("c", 7, 0);
        let good = compute_proof("token", 7, "c", 10);
        assert_eq!(
            t.verify("c", "token", 9, &good, 10, 20),
            Err(ErrorCode::ChallengeUnknown)
        );
        assert!(t.verify("c", "token", 7, &good, 10, 20).is_ok());
    }

    #[test]
    fn pending_challenges_per_client_are_bounded() {
        let mut t = ChallengeTable::new(30_000);
        for n in 1..=20 {
            t.issue("c", n, n);
        }
        assert_eq!(t.pending_count(), MAX_PENDING_PER_CLIENT);
        let oldest = compute_proof("token", 1, "c", 1);
        assert_eq!(
            t.verify("c", "token", 1, &oldest, 1, 20),
            Err(ErrorCode::ChallengeUnknown)
        );
        let newest = compute_proof("token", 20, "c", 20);
        assert!(t.verify("c", "token", 20, &newest, 20, 30).is_ok());
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
