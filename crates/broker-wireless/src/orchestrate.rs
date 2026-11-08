//! 无电脑自助链路的编排入口。
//!
//! Java 层负责 mDNS 端口发现与配对码输入。参考同类成熟实现，配对与连接启动是
//! **解耦的两个阶段**：配对服务只在「使用配对码配对设备」弹窗打开期间存在，
//! 而配对成功后系统可能重分配 `_adb-tls-connect` 端口。因此本模块支持 `stage`：
//! * `pair`：只完成 TLS 配对与公钥注册，返回 `stage=paired`；
//! * `start`：跳过配对，用重新发现到的连接端口连接并启动；
//! * 缺省 / `full`：一次串完（通知栏可选通道的兼容路径）。
//!
//! 连接目标地址不再假定为 loopback。AOSP 把 adbd 的 TLS 端口绑在
//! `127.0.0.1`，但部分 ROM（如 HyperOS）绑定在 WLAN 接口 IP 上，设置页里
//! 显示的 `IP 地址和端口` 就是该地址。Java 层把 mDNS 解析地址、本机网卡
//! IPv4 与 loopback 一起作为 `hosts` 传进来，这里逐个尝试，任一连通即用。
//!
//! 所有结果以稳定 JSON 返回，带 stage / code / message，不抛异常。

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::adb::AdbClient;
use crate::error::WirelessError;
use crate::key::AdbKey;
use crate::pairing;

const STARTER_CLASS: &str = "io.github.lianxia233.privbroker.ServiceStarter";

/// 运行时进入端点绑定阶段时打印到 stderr 的标记。注意它只表示「准备绑定」，
/// 不代表绑定成功；是否真的常驻仍由界面实测 abstract socket 判定。
/// 之前用「监听:」容易被误读成已监听成功，故改为「准备监听:」。
pub const BROKER_LISTEN_MARKER: &str = "准备监听:";

pub const STAGE_PAIR: &str = "pair";
pub const STAGE_START: &str = "start";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WirelessRequest {
    /// 兼容旧调用方的主地址；`hosts` 为空时使用。
    #[serde(default)]
    pub host: String,
    /// 候选连接地址，按可靠性排序（mDNS 解析地址 → 本机网卡 IPv4 → loopback）。
    #[serde(default)]
    pub hosts: Vec<String>,
    pub pairing_port: u16,
    pub pairing_code: String,
    pub connect_port: u16,
    pub key_path: String,
    pub apk_path: String,
    pub native_lib_dir: String,
    pub token: String,
    pub client: String,
    pub endpoint: String,
    /// 执行阶段：`pair` / `start` / 缺省（完整链路）。
    #[serde(default)]
    pub stage: String,
}

impl WirelessRequest {
    /// 去重后的候选地址；至少保证有一个可尝试的目标。
    fn candidate_hosts(&self) -> Vec<String> {
        let mut result: Vec<String> = Vec::new();
        for h in self.hosts.iter().chain(std::iter::once(&self.host)) {
            let s = h.trim();
            if !s.is_empty() && !result.iter().any(|x| x == s) {
                result.push(s.to_string());
            }
        }
        if result.is_empty() {
            result.push("127.0.0.1".to_string());
        }
        result
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WirelessResponse {
    pub ok: bool,
    pub stage: String,
    pub code: Option<String>,
    pub message: String,
    pub output: Option<String>,
    /// 启动日志中出现「准备绑定」标记，说明 app_process 已进入绑定阶段。
    /// 仅作辅助信息，是否真的常驻以界面实测 abstract socket 为准。
    pub listening: bool,
    /// 启动日志里完整的一条失败行（含 errno / 异常文本）。单独带回，避免
    /// 界面因字符截断把最关键的失败原因切掉，也便于落库到「执行日志」。
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure_reason: Option<String>,
}

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// 启动日志中出现运行时进入绑定阶段的标记。
pub fn boot_log_confirms_listening(log: &str) -> bool {
    log.contains(BROKER_LISTEN_MARKER)
}

/// 提取启动日志中完整的一条失败行（含 errno / 异常文本），倒序取最后一条。
/// 结果条与「执行日志」据此显示真实原因，而不是一个被截断的省略号。
pub fn boot_log_failure_reason(log: &str) -> Option<String> {
    const MARKERS: &[&str] = &[
        "监听失败", "特权代理启动失败", "无法加载 native", "加载 native 库失败",
        "ClassNotFoundException", "UnsatisfiedLinkError",
    ];
    log.lines()
        .rev()
        .map(str::trim)
        .find(|l| l.starts_with("错误:") || MARKERS.iter().any(|m| l.contains(m)))
        .filter(|l| !l.is_empty())
        .map(|l| l.to_string())
}

/// 构造在无线调试 shell 中后台启动特权代理的命令。
///
/// 三个细节决定「服务到底能不能起来」，缺一个就会表现为「提示成功但服务未运行」：
/// 1. **重定向 stdio**：常驻的 app_process 若继承 shell 通道的 stdout/stderr，
///    adbd 会一直等这些 fd 关闭才发 `A_CLSE`；客户端读超时断开后，adbd 又会
///    kill 掉整个进程组。所以后台进程必须 `</dev/null >>log 2>&1`，让主 shell
///    能立即结束、通道能正常关闭。
/// 2. **脱离进程组**：Java 侧 `ServiceStarter` 会调用 `Os.setsid()`，避免 adbd
///    会话收尾时把代理一起杀掉。
/// 3. **回读启动日志**：把 stderr 落到 shell 可写的临时文件再 `cat` 出来，
///    客户端既能据此判断是否真的进入了绑定阶段，也能在类加载 / so 加载 /
///    端点绑定失败时把真实原因带回界面，而不是只剩一个无从诊断的「激活失败」。
///    文件名带 `$$`（shell PID）：自动激活与手动激活并发时各写各的文件，
///    不会互相清空或把两份日志串成重复的一坨。
pub fn build_start_command(req: &WirelessRequest) -> String {
    let invoke = format!(
        "( export CLASSPATH={}; exec app_process /system/bin --nice-name=privilege-broker {} \
          --token={} --client={} --endpoint={} --lib={} )",
        shell_quote(&req.apk_path),
        STARTER_CLASS,
        shell_quote(&req.token),
        shell_quote(&req.client),
        shell_quote(&req.endpoint),
        shell_quote(&req.native_lib_dir),
    );
    format!(
        "LOG=/data/local/tmp/prvbrk_boot.$$.log; : > \"$LOG\" 2>/dev/null || LOG=/dev/null; \
         {invoke} </dev/null >>\"$LOG\" 2>&1 & sleep 2; cat \"$LOG\" 2>/dev/null"
    )
}

/// 按阶段校验：只配对时不要求连接端口与启动参数，只启动时反之。
fn validate(req: &WirelessRequest, need_pair: bool, need_start: bool) -> Result<(), WirelessError> {
    if req.candidate_hosts().is_empty() {
        return Err(WirelessError::Protocol("没有可用的连接地址".into()));
    }
    if req.key_path.trim().is_empty() {
        return Err(WirelessError::Protocol("密钥路径不能为空".into()));
    }
    if need_pair && req.pairing_port == 0 {
        return Err(WirelessError::Protocol("配对服务端口无效".into()));
    }
    if need_start {
        if req.connect_port == 0 {
            return Err(WirelessError::Protocol("连接服务端口无效".into()));
        }
        if req.token.len() < 16 {
            return Err(WirelessError::Protocol("令牌长度不足 16 字符".into()));
        }
        if req.client.trim().is_empty() {
            return Err(WirelessError::Protocol("客户端白名单不能为空".into()));
        }
        if !req.apk_path.starts_with('/') || !req.native_lib_dir.starts_with('/') {
            return Err(WirelessError::Protocol("APK 与 native 库路径必须为绝对路径".into()));
        }
    }
    Ok(())
}

/// 在多个候选地址上尝试同一个连接动作，任一成功即返回。
///
/// 全部失败时优先返回「非连接类」错误（配对码错误、协议错误等更有诊断价值，
/// 说明已经连上了只是对端拒绝了），否则返回最后一次连接错误。
fn try_hosts<T, F>(hosts: &[String], mut action: F) -> Result<T, WirelessError>
where
    F: FnMut(&str) -> Result<T, WirelessError>,
{
    let mut last_conn: Option<WirelessError> = None;
    for host in hosts {
        match action(host) {
            Ok(value) => return Ok(value),
            Err(e) => match e {
                WirelessError::Io(_) | WirelessError::Tls(_) => last_conn = Some(e),
                other => return Err(other),
            },
        }
    }
    Err(last_conn.unwrap_or_else(|| {
        WirelessError::Protocol("所有候选地址均无法连接".into())
    }))
}

fn execute(args_json: &str) -> Result<WirelessResponse, (String, WirelessError)> {
    let req: WirelessRequest = serde_json::from_str(args_json)
        .map_err(|e| ("config".to_string(), WirelessError::Protocol(format!("请求 JSON 非法: {e}"))))?;

    let need_pair = req.stage != STAGE_START;
    let need_start = req.stage != STAGE_PAIR;
    validate(&req, need_pair, need_start).map_err(|e| ("config".to_string(), e))?;

    let hosts = req.candidate_hosts();

    // 配对与后续连接必须复用同一把密钥：设备端登记的公钥就是 TLS 客户端证书。
    let key = AdbKey::load_or_create(Path::new(&req.key_path))
        .map_err(|e| ("key".to_string(), e))?;

    if need_pair {
        let _ = try_hosts(&hosts, |host| {
            pairing::pair(host, req.pairing_port, &req.pairing_code, &key)
        }).map_err(|e| ("pairing".to_string(), e))?;
        if !need_start {
            return Ok(WirelessResponse {
                ok: true,
                stage: "paired".to_string(),
                code: None,
                message: "配对成功，公钥已登记到设备。".to_string(),
                output: None,
                listening: false,
                failure_reason: None,
            });
        }
    }

    let mut client = try_hosts(&hosts, |host| {
        AdbClient::connect(host, req.connect_port, &key)
    }).map_err(|e| ("connect".to_string(), e))?;
    let command = build_start_command(&req);
    // 后台启动命令不依赖 A_CLSE：命令投递即返回，最终是否常驻由界面探测
    // abstract socket 判定，避免 adbd 通道时序把「已启动」误报成失败。
    let output = client.shell_detached(&command).map_err(|e| ("shell".to_string(), e))?;
    let text = String::from_utf8_lossy(&output).to_string();
    // 结构化带回完整失败行：界面与「执行日志」据此显示真实原因，
    // 即使启动日志本身被通道时序截断也不会丢。
    let listening = boot_log_confirms_listening(&text);
    let failure = boot_log_failure_reason(&text);

    Ok(WirelessResponse {
        ok: true,
        stage: "started".to_string(),
        code: None,
        message: "已通过无线调试投递特权代理启动命令。".to_string(),
        output: Some(text),
        listening,
        failure_reason: failure,
    })
}

/// 供 JNI 调用：返回 JSON，任何失败都带 stage / code / message，不抛异常。
pub fn run(args_json: &str) -> String {
    let response = match execute(args_json) {
        Ok(r) => r,
        Err((stage, e)) => WirelessResponse {
            ok: false,
            stage,
            code: Some(e.code().to_string()),
            message: e.to_string(),
            output: None,
            listening: false,
            failure_reason: None,
        },
    };
    serde_json::to_string(&response).unwrap_or_else(|e| {
        format!("{{\"ok\":false,\"stage\":\"config\",\"code\":\"PROTOCOL\",\"message\":\"结果序列化失败: {e}\"}}")
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request() -> WirelessRequest {
        WirelessRequest {
            host: "127.0.0.1".into(),
            hosts: vec![],
            pairing_port: 37331,
            pairing_code: "482913".into(),
            connect_port: 41235,
            key_path: "/data/user/0/io.github.lianxia233.privbroker/files/adb_key.pk8".into(),
            apk_path: "/data/app/~~abc/base.apk".into(),
            native_lib_dir: "/data/app/~~abc/lib/arm64".into(),
            token: "0123456789abcdef0123456789abcdef".into(),
            client: "installer".into(),
            endpoint: "abstract".into(),
            stage: String::new(),
        }
    }

    #[test]
    fn start_command_contains_all_arguments_and_is_backgrounded() {
        let cmd = build_start_command(&request());
        assert!(cmd.contains("export CLASSPATH='"));
        assert!(cmd.contains("exec app_process /system/bin"));
        assert!(cmd.contains("io.github.lianxia233.privbroker.ServiceStarter"));
        assert!(cmd.contains("--token='0123456789abcdef0123456789abcdef'"));
        assert!(cmd.contains("--endpoint='abstract'"));
        assert!(cmd.contains("--lib='/data/app/~~abc/lib/arm64'"));
        assert!(cmd.contains("& sleep 2; cat"));
    }

    /// 后台进程必须彻底脱离 shell 通道 fd，否则 adbd 不发 A_CLSE，客户端超时
    /// 断开后还会 kill 整个进程组 —— 这是「提示成功但服务从未运行」的根因。
    #[test]
    fn start_command_detaches_stdio_and_reads_boot_log() {
        let cmd = build_start_command(&request());
        assert!(cmd.contains("</dev/null >>\"$LOG\" 2>&1 &"));
        assert!(cmd.contains("cat \"$LOG\""));
        // 文件名带 shell PID：自动激活与手动激活并发时各写各的文件，不串味
        assert!(cmd.contains("prvbrk_boot.$$.log"));
        assert!(!cmd.contains("prvbrk_boot.log;"));
        // 不再有裸的 (...)& 结尾：那会让常驻进程持有 ADB socket fd。
        assert!(!cmd.ends_with(")&"));
    }

    #[test]
    fn listening_marker_is_detected_in_boot_log() {
        assert!(boot_log_confirms_listening(
            "特权代理启动: uid=2000 ...\n准备监听: abstract:prvbrk.1234\n"));
        assert!(!boot_log_confirms_listening("ClassNotFoundException: ServiceStarter"));
        assert!(!boot_log_confirms_listening(""));
    }

    /// 完整失败行（含 errno）必须结构化带回，不能被结果条字符截断吃掉。
    #[test]
    fn failure_reason_keeps_full_error_line() {
        let log = "特权代理进程已拉起，正在加载运行时…\n\
                   特权代理启动: uid=2000 backend=ADB_SHELL selinux=enforcing endpoint=abstract\n\
                   准备监听: abstract:prvbrk.3ae5ff681d61da99\n\
                   监听失败: Address already in use (os error 98)";
        assert_eq!(
            boot_log_failure_reason(log).as_deref(),
            Some("监听失败: Address already in use (os error 98)")
        );
        assert_eq!(
            boot_log_failure_reason("错误: 无法启动 Tokio 运行时: boom").as_deref(),
            Some("错误: 无法启动 Tokio 运行时: boom")
        );
        assert!(boot_log_failure_reason("准备监听: abstract:prvbrk.abc\n").is_none());
    }

    /// Java 侧按 camelCase 读 failureReason，序列化字段名必须对齐。
    #[test]
    fn response_serializes_failure_reason_in_camel_case() {
        let r = WirelessResponse {
            ok: true,
            stage: "started".into(),
            code: None,
            message: "投递完成".into(),
            output: None,
            listening: true,
            failure_reason: Some("监听失败: os error 98".into()),
        };
        let v: serde_json::Value =
            serde_json::from_str(&serde_json::to_string(&r).unwrap()).unwrap();
        assert_eq!(v["failureReason"], "监听失败: os error 98");
    }

    #[test]
    fn shell_quote_escapes_single_quotes() {
        let mut req = request();
        req.apk_path = "/data/app/o'clock/base.apk".into();
        let cmd = build_start_command(&req);
        assert!(cmd.contains("'/data/app/o'\\''clock/base.apk'"));
    }

    #[test]
    fn malformed_request_returns_structured_error() {
        let out = run("not-json");
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["ok"], false);
        assert_eq!(v["stage"], "config");
        assert_eq!(v["code"], "PROTOCOL");
    }

    #[test]
    fn invalid_token_is_rejected_before_network_access() {
        let mut req = request();
        req.token = "short".into();
        let args = serde_json::to_string(&serde_json::json!({
            "host": req.host, "pairingPort": req.pairing_port,
            "pairingCode": req.pairing_code, "connectPort": req.connect_port,
            "keyPath": req.key_path, "apkPath": req.apk_path,
            "nativeLibDir": req.native_lib_dir, "token": req.token,
            "client": req.client, "endpoint": req.endpoint,
        })).unwrap();
        let out = run(&args);
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["ok"], false);
        assert_eq!(v["stage"], "config");
        assert!(v["message"].as_str().unwrap().contains("令牌"));
    }

    /// 配对阶段不要求连接服务端口与启动参数——配对成功后端口会重分配。
    #[test]
    fn pair_stage_needs_no_connect_port_or_start_args() {
        let mut req = request();
        req.connect_port = 0;
        req.token.clear();
        req.apk_path.clear();
        req.native_lib_dir.clear();
        assert!(validate(&req, true, false).is_ok());
    }

    /// 启动阶段不要求配对端口与配对码——公钥已在上一阶段登记。
    #[test]
    fn start_stage_needs_no_pairing_port_or_code() {
        let mut req = request();
        req.pairing_port = 0;
        req.pairing_code.clear();
        assert!(validate(&req, false, true).is_ok());
    }

    #[test]
    fn start_stage_still_requires_connect_port() {
        let mut req = request();
        req.connect_port = 0;
        assert!(validate(&req, false, true).is_err());
    }

    #[test]
    fn pair_stage_still_requires_pairing_port() {
        let mut req = request();
        req.pairing_port = 0;
        assert!(validate(&req, true, false).is_err());
    }

    /// 缺省 stage 走完整链路，两个端口都必须给出（兼容旧调用方）。
    #[test]
    fn full_stage_requires_both_ports() {
        let mut req = request();
        req.connect_port = 0;
        assert!(validate(&req, true, true).is_err());
        let mut req = request();
        req.pairing_port = 0;
        assert!(validate(&req, true, true).is_err());
    }

    #[test]
    fn stage_field_is_optional_in_json() {
        let req = request();
        let json = serde_json::to_string(&serde_json::json!({
            "host": req.host, "pairingPort": req.pairing_port,
            "pairingCode": req.pairing_code, "connectPort": req.connect_port,
            "keyPath": req.key_path, "apkPath": req.apk_path,
            "nativeLibDir": req.native_lib_dir, "token": req.token,
            "client": req.client, "endpoint": req.endpoint,
        })).unwrap();
        let parsed: WirelessRequest = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.stage, "");
    }

    // ------------------------------------------------------------ 多地址候选

    #[test]
    fn candidate_hosts_dedup_and_order() {
        let mut req = request();
        req.hosts = vec![
            "192.168.88.158".into(),
            "192.168.88.158".into(),
            " 10.0.0.2 ".into(),
            "".into(),
        ];
        req.host = "127.0.0.1".into();
        assert_eq!(req.candidate_hosts(), vec![
            "192.168.88.158".to_string(),
            "10.0.0.2".to_string(),
            "127.0.0.1".to_string(),
        ]);
    }

    #[test]
    fn candidate_hosts_falls_back_when_all_empty() {
        let mut req = request();
        req.host.clear();
        req.hosts.clear();
        assert_eq!(req.candidate_hosts(), vec!["127.0.0.1".to_string()]);
    }

    #[test]
    fn hosts_field_is_optional_in_json() {
        let req = request();
        let json = serde_json::to_string(&serde_json::json!({
            "host": req.host, "pairingPort": req.pairing_port,
            "pairingCode": req.pairing_code, "connectPort": req.connect_port,
            "keyPath": req.key_path, "apkPath": req.apk_path,
            "nativeLibDir": req.native_lib_dir, "token": req.token,
            "client": req.client, "endpoint": req.endpoint,
        })).unwrap();
        let parsed: WirelessRequest = serde_json::from_str(&json).unwrap();
        assert!(parsed.hosts.is_empty());
        assert_eq!(parsed.candidate_hosts(), vec!["127.0.0.1".to_string()]);
    }

    #[test]
    fn try_hosts_skips_failing_addresses() {
        let hosts = vec!["a".to_string(), "b".to_string(), "c".to_string()];
        let mut seen = Vec::new();
        let value: i32 = try_hosts(&hosts, |h| {
            seen.push(h.to_string());
            if h == "c" {
                Ok(7)
            } else {
                Err(WirelessError::Io(std::io::Error::new(
                    std::io::ErrorKind::ConnectionRefused, "refused")))
            }
        }).unwrap();
        assert_eq!(value, 7);
        assert_eq!(seen, vec!["a", "b", "c"]);
    }

    #[test]
    fn try_hosts_returns_protocol_error_immediately() {
        let hosts = vec!["a".to_string(), "b".to_string()];
        let mut seen = Vec::new();
        let err = try_hosts::<(), _>(&hosts, |h| {
            seen.push(h.to_string());
            Err(WirelessError::BadPairingCode)
        }).unwrap_err();
        assert_eq!(err.code(), "BAD_PAIRING_CODE");
        assert_eq!(seen, vec!["a"]);
    }

    #[test]
    fn wlan_ip_is_accepted_as_candidate() {
        let json = serde_json::json!({
            "hosts": ["192.168.88.158", "127.0.0.1"],
            "pairingPort": 39099, "pairingCode": "146861", "connectPort": 41407,
            "keyPath": "/tmp/k", "apkPath": "/a.apk", "nativeLibDir": "/lib",
            "token": "0123456789abcdef", "client": "installer", "endpoint": "abstract",
            "stage": "pair",
        });
        let parsed: WirelessRequest = serde_json::from_str(&json.to_string()).unwrap();
        assert_eq!(parsed.candidate_hosts()[0], "192.168.88.158");
    }
}
