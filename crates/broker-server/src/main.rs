//! 独立特权进程入口。
//!
//! 由用户通过无线调试显式启动一次（普通应用无法自行获得 shell 身份）。
//! 启动即实测 uid；非 2000 时拒绝提供执行能力并输出明确错误，不降级放行。

use broker_android::StartupConfig;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let startup = match StartupConfig::parse(&args) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("启动参数错误: {e}");
            eprintln!("用法: privilege-broker --token <TOKEN> [--client <ID,...>] [--endpoint abstract|tcp:HOST:PORT] [--session-ttl-ms <MS>]");
            std::process::exit(2);
        }
    };
    std::process::exit(broker_server::run(startup));
}
