//! 特权代理运行时。
//!
//! 既可由独立 ELF 入口（`privilege-broker`）调用，也可由 APK 内的 JNI 启动桥
//! 在 `app_process` 进程中调用；两条路径都要求实测 shell(2000) 身份。

use std::sync::Arc;

use broker_android::{socket_name, PlatformState, StartupConfig};
use broker_core::{Broker, BrokerConfig};
use broker_ipc::Endpoint;

/// 阻塞运行服务，返回进程退出码（0 = 正常停止）。
pub fn run(startup: StartupConfig) -> i32 {
    let platform = PlatformState::probe(startup.require_shell_uid);
    eprintln!(
        "特权代理启动: uid={} backend={} selinux={} endpoint={}",
        platform.uid, platform.backend.as_str(), platform.selinux, startup.endpoint
    );
    if !platform.ready {
        eprintln!(
            "错误: 当前进程 uid {} 不是 shell(2000)，拒绝提供执行能力。请通过无线调试启动。",
            platform.uid
        );
        return 3;
    }

    let endpoint = match startup.endpoint.as_str() {
        "abstract" => Endpoint::Abstract(socket_name(&startup.token)),
        s if s.starts_with("tcp:") => match s[4..].parse() {
            Ok(addr) => Endpoint::Tcp(addr),
            Err(_) => {
                eprintln!("错误: 无法解析 TCP 地址 {}", &s[4..]);
                return 2;
            }
        },
        other => {
            eprintln!("错误: 未知端点类型 {other}");
            return 2;
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

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            eprintln!("错误: 无法启动 Tokio 运行时: {e}");
            return 4;
        }
    };

    runtime.block_on(async {
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

        // 绑定结果由 broker-core::serve 在 bind 之后如实打印（「监听成功:」/
        // 「监听失败:」/「特权代理已在运行:」）。这里不再打印阶段标记，避免
        // 重复日志，也不再把「准备监听」误读成已监听成功。
        tokio::select! {
            r = broker.serve(endpoint, shutdown) => {
                if r.is_err() { 4 } else { 0 }
            }
            _ = signal => { eprintln!("收到退出信号，停止服务"); 0 }
        }
    })
}
