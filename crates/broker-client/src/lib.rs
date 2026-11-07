//! Broker 客户端 SDK。
//!
//! 封装握手、状态查询、命令执行、取消与撤销。Android 侧 Java 胶水层
//! 通过同一套帧协议与之通信；本 crate 同时供 host 集成测试使用。

use broker_auth::compute_proof;
use broker_ipc::{connect, read_frame, write_frame, Duplex, Endpoint};
use broker_protocol::error::ErrorCode;
use broker_protocol::message::{
    BackendKind, Body, Envelope, StdinSpec, PROTOCOL_VERSION,
};

/// 客户端错误。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClientError {
    Transport(String),
    Protocol(ErrorCode),
    NotAuthenticated,
    Unexpected(String),
}

impl std::fmt::Display for ClientError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ClientError::Transport(s) => write!(f, "传输失败: {s}"),
            ClientError::Protocol(c) => write!(f, "协议错误: {}", c.as_str()),
            ClientError::NotAuthenticated => write!(f, "尚未完成握手"),
            ClientError::Unexpected(s) => write!(f, "非预期响应: {s}"),
        }
    }
}

impl std::error::Error for ClientError {}

#[derive(Debug, Clone)]
pub struct ClientConfig {
    pub client_id: String,
    pub token: String,
    pub deadline_ms: u64,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self { client_id: "installer".into(), token: String::new(), deadline_ms: 30_000 }
    }
}

/// 已连接的客户端。
pub struct BrokerClient {
    cfg: ClientConfig,
    stream: Duplex,
    session_id: u64,
    nonce: u64,
    server_uid: i32,
    backend: BackendKind,
}

impl BrokerClient {
    /// 连接并完成挑战响应握手。
    pub async fn connect(endpoint: &Endpoint, cfg: ClientConfig) -> Result<Self, ClientError> {
        let mut stream = connect(endpoint).await.map_err(|e| ClientError::Transport(e.to_string()))?;
        let now = now_ms();
        let mut nonce = 1u64;

        let hello = Envelope::new(nonce, 0, cfg.client_id.clone(), nonce, now, now + cfg.deadline_ms,
            Body::Hello { token: cfg.token.clone(), advertised_version: PROTOCOL_VERSION });
        write_frame(&mut stream, &hello).await.map_err(proto_err)?;
        let challenge = read_frame(&mut stream).await.map_err(proto_err)?;
        let server_nonce = match challenge.body {
            Body::Challenge { server_nonce } => server_nonce,
            Body::Response { code, .. } => return Err(ClientError::Protocol(code)),
            _ => return Err(ClientError::Unexpected("握手首帧不是 Challenge".into())),
        };

        nonce += 1;
        let t = now_ms();
        let proof = compute_proof(&cfg.token, server_nonce, &cfg.client_id, t);
        let cp = Envelope::new(nonce, 0, cfg.client_id.clone(), nonce, t, t + cfg.deadline_ms,
            Body::ChallengeProof { server_nonce, proof });
        write_frame(&mut stream, &cp).await.map_err(proto_err)?;
        let ack = read_frame(&mut stream).await.map_err(proto_err)?;
        match ack.body {
            Body::AuthAck { granted: true, uid, backend, .. } => {
                Ok(Self {
                    cfg,
                    stream,
                    session_id: ack.session_id,
                    nonce: nonce + 1,
                    server_uid: uid,
                    backend,
                })
            }
            Body::AuthAck { granted: false, .. } => Err(ClientError::NotAuthenticated),
            Body::Response { code, .. } => Err(ClientError::Protocol(code)),
            _ => Err(ClientError::Unexpected("握手末帧不是 AuthAck".into())),
        }
    }

    pub fn session_id(&self) -> u64 { self.session_id }
    pub fn server_uid(&self) -> i32 { self.server_uid }
    pub fn backend(&self) -> BackendKind { self.backend }

    fn envelope(&mut self, body: Body) -> Envelope {
        let now = now_ms();
        self.nonce = self.nonce.wrapping_add(1).max(1);
        Envelope::new(self.nonce, self.session_id, self.cfg.client_id.clone(), self.nonce, now, now + self.cfg.deadline_ms, body)
    }

    /// 发送一条请求并读取到最终 Response，中途的流式帧被消费。
    async fn request(&mut self, body: Body) -> Result<Envelope, ClientError> {
        let req = self.envelope(body);
        let id = req.request_id;
        write_frame(&mut self.stream, &req).await.map_err(proto_err)?;
        loop {
            let rep = read_frame(&mut self.stream).await.map_err(proto_err)?;
            if rep.request_id != id {
                continue; // 忽略其它请求的残留帧
            }
            match rep.body {
                Body::Response { code, .. } if !code.is_ok() => return Err(ClientError::Protocol(code)),
                Body::Response { .. } => return Ok(rep),
                Body::ExecStarted { .. } | Body::ExecChunk { .. } | Body::ExecFinished { .. } => continue,
                Body::StatusReply { .. } => return Ok(rep),
                _ => return Err(ClientError::Unexpected("未知响应类型".into())),
            }
        }
    }

    /// 查询服务端状态。
    pub async fn status(&mut self) -> Result<(i32, BackendKind, String, u64), ClientError> {
        let rep = self.request(Body::StatusQuery {}).await?;
        match rep.body {
            Body::StatusReply { backend, uid, selinux, sessions } => Ok((uid, backend, selinux, sessions)),
            _ => Err(ClientError::Unexpected("状态响应类型错误".into())),
        }
    }

    /// 执行一条命令，返回平台桥接契约 JSON（code/stdout/stderr/timedOut/cancelled）。
    pub async fn exec(
        &mut self,
        argv: Vec<String>,
        stdin: StdinSpec,
        timeout_ms: u64,
        cancel_group: u64,
    ) -> Result<serde_json::Value, ClientError> {
        let rep = self.request(Body::Exec { argv, stdin, env: vec![], timeout_ms, cancel_group }).await?;
        match rep.body {
            Body::Response { payload, .. } => Ok(payload),
            _ => Err(ClientError::Unexpected("执行响应类型错误".into())),
        }
    }

    /// 取消一个运行中的句柄。
    pub async fn cancel(&mut self, handle: u64) -> Result<serde_json::Value, ClientError> {
        let rep = self.request(Body::Cancel { handle }).await?;
        match rep.body {
            Body::Response { payload, .. } => Ok(payload),
            _ => Err(ClientError::Unexpected("取消响应类型错误".into())),
        }
    }

    /// 撤销当前会话。
    pub async fn revoke(&mut self) -> Result<serde_json::Value, ClientError> {
        let sid = self.session_id;
        let rep = self.request(Body::Revoke { target_session: sid }).await?;
        match rep.body {
            Body::Response { payload, .. } => Ok(payload),
            _ => Err(ClientError::Unexpected("撤销响应类型错误".into())),
        }
    }
}

fn proto_err(e: ErrorCode) -> ClientError { ClientError::Protocol(e) }

pub fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use broker_android::PlatformState;
    use broker_core::{Broker, BrokerConfig};
    use std::sync::Arc;

    const TOKEN: &str = "0123456789abcdef0123456789abcdef";

    /// 起一个真实 TCP 服务端，返回客户端连接（host 端到端）。
    async fn start_pair() -> BrokerClient {
        let cfg = BrokerConfig { token: TOKEN.into(), require_shell_uid: false, ..Default::default() };
        let platform = PlatformState { uid: 2000, backend: BackendKind::AdbShell, selinux: "enforcing".into(), ready: true };
        let broker = Arc::new(Broker::new(cfg, platform));
        let listener = broker_ipc::Listener::bind(Endpoint::Tcp("127.0.0.1:0".parse().unwrap())).await.unwrap();
        let addr = listener.local_addr().unwrap().unwrap();
        tokio::spawn(async move {
            // 测试为短生命周期，accept 循环随 runtime 退出自然结束。
            loop {
                match listener.accept().await {
                    Ok(s) => {
                        let b = broker.clone();
                        tokio::spawn(async move { b.serve_connection(s).await; });
                    }
                    Err(_) => break,
                }
            }
        });
        BrokerClient::connect(
            &Endpoint::Tcp(addr),
            ClientConfig { client_id: "installer".into(), token: TOKEN.into(), deadline_ms: 30_000 },
        ).await.unwrap()
    }

    #[tokio::test]
    async fn end_to_end_handshake_status_exec() {
        let mut client = start_pair().await;
        assert!(client.session_id() > 0);
        assert_eq!(client.server_uid(), 2000);
        assert_eq!(client.backend(), BackendKind::AdbShell);

        let (uid, backend, _selinux, sessions) = client.status().await.unwrap();
        assert_eq!(uid, 2000);
        assert_eq!(backend, BackendKind::AdbShell);
        assert!(sessions >= 1);

        let out = client.exec(
            vec!["/bin/sh".into(), "-c".into(), "printf E2E_OK".into()],
            StdinSpec::None, 5000, 0,
        ).await.unwrap();
        assert_eq!(out["code"], 0);
        assert_eq!(out["stdout"], "E2E_OK");
    }

    #[tokio::test]
    async fn end_to_end_revoke_blocks_further_requests() {
        let mut client = start_pair().await;
        client.revoke().await.unwrap();
        assert_eq!(client.status().await, Err(ClientError::Protocol(ErrorCode::SessionRevoked)));
    }

    #[tokio::test]
    async fn wrong_token_is_rejected_on_connect() {
        let cfg = BrokerConfig { token: TOKEN.into(), require_shell_uid: false, ..Default::default() };
        let platform = PlatformState { uid: 2000, backend: BackendKind::AdbShell, selinux: "unknown".into(), ready: true };
        let broker = Arc::new(Broker::new(cfg, platform));
        let ep = Endpoint::Tcp("127.0.0.1:0".parse().unwrap());
        let listener = broker_ipc::Listener::bind(ep).await.unwrap();
        let addr = listener.local_addr().unwrap().unwrap();
        tokio::spawn(async move {
            if let Ok(s) = listener.accept().await {
                broker.serve_connection(s).await;
            }
        });
        let r = BrokerClient::connect(
            &Endpoint::Tcp(addr),
            ClientConfig { client_id: "installer".into(), token: "bad-token".into(), deadline_ms: 30_000 },
        ).await;
        assert_eq!(r.err().map(|e| e.to_string().contains("UNAUTHENTICATED")), Some(true));
    }

    #[test]
    fn client_error_display_is_stable() {
        assert_eq!(ClientError::NotAuthenticated.to_string(), "尚未完成握手");
        assert!(ClientError::Protocol(ErrorCode::Timeout).to_string().contains("TIMEOUT"));
    }
}
