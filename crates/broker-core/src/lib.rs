//! Tokio Broker 服务主体。
//!
//! 状态机：Hello → Challenge → ChallengeProof → AuthAck → 会话内请求。
//! 所有响应都带明确错误码；认证、会话、撤销、多客户端隔离在协议层已实现，
//! 本层负责把它们与真实进程执行、连接生命周期串起来。

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use broker_android::PlatformState;
use broker_auth::{ChallengeTable, NonceGenerator};
use broker_exec::extract;
use broker_ipc::{read_frame, write_frame, Duplex, Endpoint, Listener};
use broker_process::ProcessManager;
use broker_protocol::auth::{AuthConfig, Authenticator};
use broker_protocol::error::ErrorCode;
use broker_protocol::message::{
    Body, Envelope, ResponseStatus, StreamKind, PROTOCOL_VERSION,
};
use broker_protocol::session::SessionTable;
use tokio::sync::Mutex;
use tokio::time::{interval, Duration};

/// 服务配置。
#[derive(Debug, Clone)]
pub struct BrokerConfig {
    pub token: String,
    pub allowed_clients: Vec<String>,
    pub session_ttl_ms: u64,
    pub challenge_ttl_ms: u64,
    pub nonce_seed: u64,
    pub require_shell_uid: bool,
}

impl Default for BrokerConfig {
    fn default() -> Self {
        Self {
            token: String::new(),
            allowed_clients: vec!["installer".into()],
            session_ttl_ms: 60_000,
            challenge_ttl_ms: 10_000,
            nonce_seed: 0x9E37_79B9_7F4A_7C15,
            require_shell_uid: true,
        }
    }
}

struct CoreState {
    auth: Authenticator,
    challenges: ChallengeTable,
    sessions: SessionTable,
    nonces: NonceGenerator,
    /// 会话已握手的客户端集合，未握手不得发送业务请求。
    authenticated: HashSet<(String, u64)>,
    /// 取消组 -> 句柄集合；句柄 -> 取消组。
    groups: HashMap<u64, HashSet<u64>>,
    handle_group: HashMap<u64, u64>,
}

/// Broker 实例，可跨连接共享。
pub struct Broker {
    cfg: BrokerConfig,
    state: Mutex<CoreState>,
    processes: Arc<ProcessManager>,
    platform: PlatformState,
}

impl Broker {
    pub fn new(cfg: BrokerConfig, platform: PlatformState) -> Self {
        let clients: Vec<&str> = cfg.allowed_clients.iter().map(|s| s.as_str()).collect();
        let auth = Authenticator::new(AuthConfig::new(cfg.token.clone(), &clients));
        let state = CoreState {
            auth,
            challenges: ChallengeTable::new(cfg.challenge_ttl_ms),
            sessions: SessionTable::new(cfg.session_ttl_ms),
            nonces: NonceGenerator::new(cfg.nonce_seed),
            authenticated: HashSet::new(),
            groups: HashMap::new(),
            handle_group: HashMap::new(),
        };
        let require_shell = cfg.require_shell_uid && platform.ready;
        let uid = platform.uid;
        Self {
            cfg,
            state: Mutex::new(state),
            processes: Arc::new(ProcessManager::new(uid, require_shell)),
            platform,
        }
    }

    pub fn platform(&self) -> &PlatformState {
        &self.platform
    }

    pub fn processes(&self) -> &Arc<ProcessManager> {
        &self.processes
    }

    /// 处理一条请求，返回零到多条响应帧。错误统一编码为 Response，不吞异常。
    pub async fn handle(&self, env: Envelope, now_ms: u64) -> Vec<Envelope> {
        match self.dispatch(&env, now_ms).await {
            Ok(frames) => frames,
            Err(code) => vec![env.reply(
                ResponseStatus::Rejected,
                code,
                code.as_str(),
                serde_json::json!({}),
                now_ms,
                env.deadline_ms,
            )],
        }
    }

    async fn dispatch(&self, env: &Envelope, now: u64) -> Result<Vec<Envelope>, ErrorCode> {
        match &env.body {
            Body::Hello { .. } => self.on_hello(env, now).await,
            Body::ChallengeProof { server_nonce, proof } => {
                self.on_proof(env, *server_nonce, proof, now).await
            }
            Body::StatusQuery {} => self.on_status(env, now).await,
            Body::Exec { .. } => self.on_exec(env, now).await,
            Body::Cancel { handle } => self.on_cancel(env, *handle, now).await,
            Body::Revoke { target_session } => self.on_revoke(env, *target_session, now).await,
            _ => Err(ErrorCode::InvalidRequest),
        }
    }

    async fn on_hello(&self, env: &Envelope, now: u64) -> Result<Vec<Envelope>, ErrorCode> {
        let mut st = self.state.lock().await;
        st.auth.verify(env, now)?;
        let nonce = st.nonces.next(now);
        st.challenges.issue(&env.client_id, nonce, now);
        Ok(vec![Envelope::new(
            env.request_id,
            0,
            env.client_id.clone(),
            nonce,
            now,
            env.deadline_ms,
            Body::Challenge { server_nonce: nonce },
        )])
    }

    async fn on_proof(
        &self,
        env: &Envelope,
        server_nonce: u64,
        proof: &str,
        now: u64,
    ) -> Result<Vec<Envelope>, ErrorCode> {
        let mut st = self.state.lock().await;
        st.auth.verify(env, now)?;
        st.challenges.verify(&env.client_id, &self.cfg.token, server_nonce, proof, env.issued_at_ms, now)?;
        let uid = self.platform.uid;
        let backend = self.platform.backend;
        let sid = st.sessions.open(&env.client_id, uid, backend, now);
        st.authenticated.insert((env.client_id.clone(), sid));
        Ok(vec![Envelope::new(
            env.request_id,
            sid,
            env.client_id.clone(),
            st.nonces.next(now),
            now,
            env.deadline_ms,
            Body::AuthAck {
                granted: true,
                uid,
                backend,
                server_version: PROTOCOL_VERSION,
            },
        )])
    }

    /// 业务请求的公共校验：认证 + 会话有效 + 客户端一致 + 续期。
    async fn authorize(&self, env: &Envelope, now: u64) -> Result<u64, ErrorCode> {
        let mut st = self.state.lock().await;
        st.auth.verify(env, now)?;
        st.sessions.get(env.session_id, &env.client_id, now)?;
        if !st.authenticated.contains(&(env.client_id.clone(), env.session_id)) {
            return Err(ErrorCode::Unauthenticated);
        }
        st.sessions.touch(env.session_id, &env.client_id, now)?;
        Ok(env.session_id)
    }

    async fn on_status(&self, env: &Envelope, now: u64) -> Result<Vec<Envelope>, ErrorCode> {
        self.authorize(env, now).await?;
        let mut st = self.state.lock().await;
        let nonce = st.nonces.next(now);
        let sessions = st.sessions.live_count(now) as u64;
        Ok(vec![Envelope::new(
            env.request_id,
            env.session_id,
            env.client_id.clone(),
            nonce,
            now,
            env.deadline_ms,
            Body::StatusReply {
                backend: self.platform.backend,
                uid: self.platform.uid,
                selinux: self.platform.selinux.clone(),
                sessions,
            },
        )])
    }

    async fn on_exec(&self, env: &Envelope, now: u64) -> Result<Vec<Envelope>, ErrorCode> {
        let sid = self.authorize(env, now).await?;
        let spec = extract(env)?;
        let cancel_group = spec.cancel_group;
        let (handle, done) = self.processes.execute_cancellable(spec).await?;
        {
            let mut st = self.state.lock().await;
            st.sessions.attach_handle(sid, &env.client_id, handle, now)?;
            if cancel_group != 0 {
                st.groups.entry(cancel_group).or_default().insert(handle);
                st.handle_group.insert(handle, cancel_group);
            }
        }
        let outcome = done.await.map_err(|_| ErrorCode::ExecWaitFailed)?;
        {
            let mut st = self.state.lock().await;
            st.sessions.detach_handle(sid, handle);
            if let Some(g) = st.handle_group.remove(&handle) {
                if let Some(set) = st.groups.get_mut(&g) {
                    set.remove(&handle);
                    if set.is_empty() { st.groups.remove(&g); }
                }
            }
        }
        self.processes.detach(handle).await;

        let mut frames = Vec::new();
        frames.push(Envelope::new(env.request_id, sid, env.client_id.clone(), env.nonce.wrapping_add(1), now, env.deadline_ms, Body::ExecStarted { handle }));
        // 大输出按 32KB 原始字节切片，hex 后仍在单帧上限内。
        let chunk = |stream: StreamKind, data: &[u8], idx: u64| {
            Envelope::new(env.request_id, sid, env.client_id.clone(), env.nonce.wrapping_add(2 + idx), now, env.deadline_ms,
                Body::ExecChunk { handle, stream, hex: hex_encode(data) })
        };
        let mut idx = 0u64;
        for part in outcome.stdout.chunks(32 * 1024) {
            frames.push(chunk(StreamKind::Stdout, part, idx));
            idx += 1;
        }
        for part in outcome.stderr.chunks(32 * 1024) {
            frames.push(chunk(StreamKind::Stderr, part, idx));
            idx += 1;
        }
        frames.push(Envelope::new(env.request_id, sid, env.client_id.clone(), env.nonce.wrapping_add(2 + idx), now, env.deadline_ms, Body::ExecFinished { handle, code: outcome.code }));
        frames.push(env.reply(
            ResponseStatus::Completed,
            ErrorCode::Ok,
            "执行完成",
            outcome.to_json(),
            now,
            env.deadline_ms,
        ));
        Ok(frames)
    }

    async fn on_cancel(&self, env: &Envelope, handle: u64, now: u64) -> Result<Vec<Envelope>, ErrorCode> {
        self.authorize(env, now).await?;
        let mut targets = vec![handle];
        {
            let st = self.state.lock().await;
            if let Some(g) = st.handle_group.get(&handle) {
                if let Some(set) = st.groups.get(g) {
                    for h in set { if *h != handle { targets.push(*h); } }
                }
            }
        }
        let n = self.processes.cancel_all(&targets).await;
        Ok(vec![env.reply(
            ResponseStatus::Completed,
            ErrorCode::Ok,
            "已请求取消",
            serde_json::json!({ "cancelled": n }),
            now,
            env.deadline_ms,
        )])
    }

    async fn on_revoke(&self, env: &Envelope, target: u64, now: u64) -> Result<Vec<Envelope>, ErrorCode> {
        self.authorize(env, now).await?;
        let mut st = self.state.lock().await;
        // 只能撤销自己的会话：协议层的 get 已强制客户端一致。
        st.sessions.get(target, &env.client_id, now)?;
        let handles = st.sessions.revoke(target)?;
        st.authenticated.remove(&(env.client_id.clone(), target));
        drop(st);
        let n = self.processes.cancel_all(&handles).await;
        Ok(vec![env.reply(
            ResponseStatus::Completed,
            ErrorCode::Ok,
            "会话已撤销",
            serde_json::json!({ "revoked": target, "terminated": n }),
            now,
            env.deadline_ms,
        )])
    }

    /// 后台清理：过期会话与挑战，回收残留进程句柄。
    pub async fn maintenance(&self, now: u64) {
        let mut st = self.state.lock().await;
        st.challenges.purge(now);
        let handles = st.sessions.purge(now);
        // 先按记录中的客户端标识算出仍然有效的握手项，再整体替换，
        // 避免同时可变借用 authenticated 与不可变借用 sessions。
        let alive: HashSet<(String, u64)> = st.authenticated.iter()
            .filter(|(cid, sid)| st.sessions.get(*sid, cid, now).is_ok())
            .cloned()
            .collect();
        st.authenticated = alive;
        drop(st);
        self.processes.cancel_all(&handles).await;
    }

    /// 单连接服务循环：读帧 → 处理 → 写回全部响应帧。
    pub async fn serve_connection(self: Arc<Self>, mut stream: Duplex) {
        loop {
            let env = match read_frame(&mut stream).await {
                Ok(e) => e,
                Err(_) => break, // 断连或非法帧：关闭连接，不吞掉为成功
            };
            let now = now_ms();
            for rep in self.handle(env, now).await {
                if write_frame(&mut stream, &rep).await.is_err() {
                    return;
                }
            }
        }
    }

    /// 监听并接受连接，直到 shutdown。每个连接独立任务，互不阻塞。
    pub async fn serve(self: Arc<Self>, endpoint: Endpoint, mut shutdown: tokio::sync::oneshot::Receiver<()>) -> Result<(), std::io::Error> {
        let listener = Listener::bind(endpoint).await?;
        let mut ticker = interval(Duration::from_millis(5_000));
        loop {
            tokio::select! {
                _ = &mut shutdown => break,
                _ = ticker.tick() => {
                    self.maintenance(now_ms()).await;
                }
                r = listener.accept() => match r {
                    Ok(stream) => {
                        let b = self.clone();
                        tokio::spawn(async move { b.serve_connection(stream).await; });
                    }
                    Err(_) => continue,
                }
            }
        }
        Ok(())
    }
}

fn hex_encode(data: &[u8]) -> String {
    const D: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(data.len() * 2);
    for &b in data {
        s.push(D[(b >> 4) as usize] as char);
        s.push(D[(b & 0x0f) as usize] as char);
    }
    s
}

/// 墙钟毫秒。协议时间窗口依赖它，服务端与客户端都以同一时钟源为准。
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use broker_auth::compute_proof;
    use broker_protocol::message::StdinSpec;
    use broker_protocol::BackendKind;

    const TOKEN: &str = "0123456789abcdef0123456789abcdef";

    fn broker() -> Arc<Broker> {
        let cfg = BrokerConfig { token: TOKEN.into(), require_shell_uid: false, ..Default::default() };
        let platform = PlatformState { uid: 2000, backend: BackendKind::AdbShell, selinux: "enforcing".into(), ready: true };
        Arc::new(Broker::new(cfg, platform))
    }

    fn msg(id: u64, sid: u64, nonce: u64, now: u64, body: Body) -> Envelope {
        Envelope::new(id, sid, "installer", nonce, now, now + 30_000, body)
    }

    /// 完成握手，返回会话 id 与下一可用 nonce。
    async fn handshake(b: &Arc<Broker>, now: u64) -> (u64, u64) {
        let hello = msg(1, 0, 1, now, Body::Hello { token: TOKEN.into(), advertised_version: PROTOCOL_VERSION });
        let frames = b.handle(hello, now).await;
        let server_nonce = match &frames[0].body {
            Body::Challenge { server_nonce } => *server_nonce,
            _ => panic!("应为 Challenge"),
        };
        let proof = compute_proof(TOKEN, server_nonce, "installer", now + 1);
        let cp = msg(2, 0, 2, now + 1, Body::ChallengeProof { server_nonce, proof });
        let frames = b.handle(cp, now + 2).await;
        match &frames[0].body {
            Body::AuthAck { granted, uid, backend, .. } => {
                assert!(*granted);
                assert_eq!(*uid, 2000);
                assert_eq!(*backend, BackendKind::AdbShell);
            }
            _ => panic!("应为 AuthAck: {:?}", frames[0].body),
        }
        (frames[0].session_id, 3)
    }

    #[tokio::test]
    async fn full_handshake_then_status() {
        let b = broker();
        let (sid, n) = handshake(&b, 1_000).await;
        let rep = b.handle(msg(3, sid, n, 1_010, Body::StatusQuery {}), 1_010).await;
        match &rep[0].body {
            Body::StatusReply { uid, backend, sessions, .. } => {
                assert_eq!(*uid, 2000);
                assert_eq!(*backend, BackendKind::AdbShell);
                assert_eq!(*sessions, 1);
            }
            _ => panic!("应为 StatusReply: {:?}", rep[0].body),
        }
    }

    #[tokio::test]
    async fn exec_runs_real_command_and_returns_output() {
        let b = broker();
        let (sid, n) = handshake(&b, 2_000).await;
        let e = msg(4, sid, n, 2_010, Body::Exec {
            argv: vec!["/bin/sh".into(), "-c".into(), "printf HELLO".into()],
            stdin: StdinSpec::None,
            env: vec![],
            timeout_ms: 5000,
            cancel_group: 0,
        });
        let frames = b.handle(e, 2_010).await;
        let last = frames.last().unwrap();
        match &last.body {
            Body::Response { status, payload, .. } => {
                assert_eq!(*status, ResponseStatus::Completed);
                assert_eq!(payload["stdout"], "HELLO");
                assert_eq!(payload["code"], 0);
            }
            _ => panic!("末帧应为 Response"),
        }
        assert!(frames.iter().any(|f| matches!(f.body, Body::ExecStarted { .. })));
        assert!(frames.iter().any(|f| matches!(f.body, Body::ExecFinished { .. })));
        assert!(frames.iter().any(|f| matches!(f.body, Body::ExecChunk { .. })));
    }

    #[tokio::test]
    async fn unauthenticated_client_is_rejected() {
        let b = broker();
        let hello = Envelope::new(1, 0, "intruder", 1, 1_000, 31_000, Body::Hello { token: TOKEN.into(), advertised_version: PROTOCOL_VERSION });
        let rep = b.handle(hello, 1_000).await;
        match &rep[0].body {
            Body::Response { code, .. } => assert_eq!(*code, ErrorCode::ClientRejected),
            _ => panic!("应被拒绝"),
        }
    }

    #[tokio::test]
    async fn wrong_token_is_rejected() {
        let b = broker();
        let hello = msg(1, 0, 1, 1_000, Body::Hello { token: "wrong-token".into(), advertised_version: PROTOCOL_VERSION });
        let rep = b.handle(hello, 1_000).await;
        match &rep[0].body {
            Body::Response { code, .. } => assert_eq!(*code, ErrorCode::Unauthenticated),
            _ => panic!("应被拒绝"),
        }
    }

    #[tokio::test]
    async fn business_request_without_session_is_rejected() {
        // 会话表是握手时唯一创建会话的地方；伪造 sid 在会话层即被拒绝，
        // 不会落到 authenticated 的纵深检查。
        let b = broker();
        let e = msg(1, 1, 1, 1_000, Body::StatusQuery {});
        let rep = b.handle(e, 1_000).await;
        match &rep[0].body {
            Body::Response { code, .. } => assert_eq!(*code, ErrorCode::SessionUnknown),
            _ => panic!("应被拒绝"),
        }
    }

    #[tokio::test]
    async fn replayed_nonce_is_rejected() {
        let b = broker();
        let (sid, n) = handshake(&b, 3_000).await;
        let q = msg(5, sid, n, 3_010, Body::StatusQuery {});
        let _ = b.handle(q.clone(), 3_010).await;
        let rep = b.handle(q, 3_011).await;
        match &rep[0].body {
            Body::Response { code, .. } => assert_eq!(*code, ErrorCode::ReplayDetected),
            _ => panic!("重放应被拒绝"),
        }
    }

    #[tokio::test]
    async fn cross_client_session_borrow_is_denied() {
        let b = broker();
        let (sid, _) = handshake(&b, 4_000).await;
        let e = Envelope::new(9, sid, "intruder", 9, 4_010, 34_010, Body::StatusQuery {});
        let rep = b.handle(e, 4_010).await;
        match &rep[0].body {
            Body::Response { code, .. } => assert_eq!(*code, ErrorCode::ClientRejected),
            _ => panic!("应被拒绝"),
        }
    }

    #[tokio::test]
    async fn revoke_invalidates_session_and_terminates_handles() {
        let b = broker();
        let (sid, n) = handshake(&b, 5_000).await;
        let rep = b.handle(msg(6, sid, n, 5_010, Body::Revoke { target_session: sid }), 5_010).await;
        match &rep[0].body {
            Body::Response { status, .. } => assert_eq!(*status, ResponseStatus::Completed),
            _ => panic!("应完成"),
        }
        let later = b.handle(msg(7, sid, n + 1, 5_020, Body::StatusQuery {}), 5_020).await;
        match &later[0].body {
            Body::Response { code, .. } => assert_eq!(*code, ErrorCode::SessionRevoked),
            _ => panic!("撤销后应失效"),
        }
    }

    #[tokio::test]
    async fn invalid_exec_spec_is_rejected_without_running() {
        let b = broker();
        let (sid, n) = handshake(&b, 6_000).await;
        let e = msg(8, sid, n, 6_010, Body::Exec {
            argv: vec![],
            stdin: StdinSpec::None,
            env: vec![],
            timeout_ms: 1000,
            cancel_group: 0,
        });
        let rep = b.handle(e, 6_010).await;
        match &rep[0].body {
            Body::Response { code, .. } => assert_eq!(*code, ErrorCode::InvalidRequest),
            _ => panic!("应被拒绝"),
        }
    }

    #[tokio::test]
    async fn maintenance_purges_expired_sessions() {
        let cfg = BrokerConfig { token: TOKEN.into(), session_ttl_ms: 10, require_shell_uid: false, ..Default::default() };
        let platform = PlatformState { uid: 2000, backend: BackendKind::AdbShell, selinux: "unknown".into(), ready: true };
        let b = Arc::new(Broker::new(cfg, platform));
        let (sid, n) = handshake(&b, 7_000).await;
        b.maintenance(7_100).await;
        let rep = b.handle(msg(9, sid, n, 7_100, Body::StatusQuery {}), 7_100).await;
        match &rep[0].body {
            // purge 已把过期会话从表中删除，因此报 SessionUnknown；
            // 握手记录同步清除，访问同样被拒绝，二者都表示不可用。
            Body::Response { code, .. } => assert!(
                *code == ErrorCode::SessionUnknown || *code == ErrorCode::SessionExpired,
                "过期会话应失效，实际 {code:?}"
            ),
            _ => panic!("过期会话应失效"),
        }
    }

    #[test]
    fn hex_encode_is_exact() {
        assert_eq!(hex_encode(&[0x00, 0x0f, 0xff, 0xa1]), "000fffa1");
        assert_eq!(hex_encode(&[]), "");
    }
}
