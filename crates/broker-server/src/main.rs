//! 独立特权进程入口。
//!
//! 由用户通过无线调试显式启动一次（普通应用无法自行获得 shell 身份）。
//! 启动即实测 uid；非 2000 时拒绝提供执行能力并输出明确错误，不降级放行。

use std::sync::Arc;

use broker_android::{socket_name, PlatformState, StartupConfig};
use broker_core::{Broker, BrokerConfig};
use broker_ipc::Endpoint;

#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let startup = match StartupConfig::parse(&args) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("启动参数错误: {e}");
            eprintln!("用法: privilege-broker --token <TOKEN> [--client <ID,...>] [--endpoint abstract|tcp:HOST:PORT] [--session-ttl-ms <MS>]");
            std::process::exit(2);
        }
    };

    let platform = PlatformState::probe(startup.require_shell_uid);
    eprintln!(
        "特权代理启动: uid={} backend={} selinux={} endpoint={}",
        platform.uid, platform.backend.as_str(), platform.selinux, startup.endpoint
    );
    if !platform.ready {
        eprintln!("错误: 当前进程 uid {} 不是 shell(2000)，拒绝提供执行能力。请通过无线调试启动。", platform.uid);
        std::process::exit(3);
    }

    let endpoint = match startup.endpoint.as_str() {
        "abstract" => Endpoint::Abstract(socket_name(&startup.token)),
        s if s.starts_with("tcp:") => match s[4..].parse() {
            Ok(addr) => Endpoint::Tcp(addr),
            Err(_) => {
                eprintln!("错误: 无法解析 TCP 地址 {}", &s[4..]);
                std::process::exit(2);
            }
        },
        other => {
            eprintln!("错误: 未知端点类型 {other}");
            std::process::exit(2);
        }
    };

    let cfg = BrokerConfig {
        token: startup.token.clone(),
        allowed_clients: startup.allowed_clients.clone(),
        session_ttl_ms: startup.session_ttl_ms,
        ..Default::default()
    };
    let broker = Arc::new(Broker::new(cfg, platform));
    let (_tx, shutdown) = tokio::sync::oneshot::channel::<()>();

    // SIGTERM / SIGINT 时优雅退出；信号不可用时保持常驻直到被系统终止。
    let signal = async {
        #[cfg(unix)]
        {
            use tokio::signal::unix::{signal, SignalKind};
            let mut term = signal(SignalKind::terminate()).expect("注册 SIGTERM 失败");
            let mut int = signal(SignalKind::interrupt()).expect("注册 SIGINT 失败");
            tokio::select! {
                _ = term.recv() => {},
                _ = int.recv() => {},
            }
        }
        #[cfg(not(unix))]
        {
            std::future::pending::<()>().await;
        }
    };

    eprintln!("监听: {}", endpoint.describe());
    tokio::select! {
        r = broker.serve(endpoint, shutdown) => {
            if let Err(e) = r { eprintln!("监听失败: {e}"); std::process::exit(4); }
        }
        _ = signal => { eprintln!("收到退出信号，停止服务"); }
    }
}
