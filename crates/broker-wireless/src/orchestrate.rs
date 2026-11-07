//! 无电脑自助链路的编排入口。
//!
//! Java 层负责 mDNS 端口发现与配对码输入。参考同类成熟实现，配对与连接启动是
//! **解耦的两个阶段**：配对服务只在「使用配对码配对设备」弹窗打开期间存在，
//! 而配对成功后系统可能重分配 `_adb-tls-connect` 端口。因此本模块支持 `stage`：
//! * `pair`：只完成 TLS 配对与公钥注册，返回 `stage=paired`；
//! * `start`：跳过配对，用重新发现到的连接端口连接并启动；
//! * 缺省 / `full`：一次串完（通知栏可选通道的兼容路径）。
//!
//! 所有结果以稳定 JSON 返回，带 stage / code / message，不抛异常。

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::adb::AdbClient;
use crate::error::WirelessError;
use crate::key::AdbKey;
use crate::pairing;

const STARTER_CLASS: &str = "io.github.lianxia233.privbroker.ServiceStarter";

pub const STAGE_PAIR: &str = "pair";
pub const STAGE_START: &str = "start";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WirelessRequest {
    pub host: String,
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

#[derive(Debug, Serialize)]
pub struct WirelessResponse {
    pub ok: bool,
    pub stage: String,
    pub code: Option<String>,
    pub message: String,
    pub output: Option<String>,
}

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// 构造在无线调试 shell 中后台启动特权代理的命令。
pub fn build_start_command(req: &WirelessRequest) -> String {
    format!(
        "(CLASSPATH={} app_process /system/bin --nice-name=privilege-broker {} \
         --token={} --client={} --endpoint={} --lib={})&",
        shell_quote(&req.apk_path),
        STARTER_CLASS,
        shell_quote(&req.token),
        shell_quote(&req.client),
        shell_quote(&req.endpoint),
        shell_quote(&req.native_lib_dir),
    )
}

/// 按阶段校验：只配对时不要求连接端口与启动参数，只启动时反之。
fn validate(req: &WirelessRequest, need_pair: bool, need_start: bool) -> Result<(), WirelessError> {
    if req.host.trim().is_empty() {
        return Err(WirelessError::Protocol("host 不能为空".into()));
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

fn execute(args_json: &str) -> Result<WirelessResponse, (String, WirelessError)> {
    let req: WirelessRequest = serde_json::from_str(args_json)
        .map_err(|e| ("config".to_string(), WirelessError::Protocol(format!("请求 JSON 非法: {e}"))))?;

    let need_pair = req.stage != STAGE_START;
    let need_start = req.stage != STAGE_PAIR;
    validate(&req, need_pair, need_start).map_err(|e| ("config".to_string(), e))?;

    // 配对与后续连接必须复用同一把密钥：设备端登记的公钥就是 TLS 客户端证书。
    let key = AdbKey::load_or_create(Path::new(&req.key_path))
        .map_err(|e| ("key".to_string(), e))?;

    if need_pair {
        pairing::pair(&req.host, req.pairing_port, &req.pairing_code, &key)
            .map_err(|e| ("pairing".to_string(), e))?;
        if !need_start {
            return Ok(WirelessResponse {
                ok: true,
                stage: "paired".to_string(),
                code: None,
                message: "配对成功，公钥已登记到设备。".to_string(),
                output: None,
            });
        }
    }

    let mut client = AdbClient::connect(&req.host, req.connect_port, &key)
        .map_err(|e| ("connect".to_string(), e))?;
    let command = build_start_command(&req);
    let output = client.shell(&command).map_err(|e| ("shell".to_string(), e))?;

    Ok(WirelessResponse {
        ok: true,
        stage: "started".to_string(),
        code: None,
        message: "配对成功，已通过无线调试启动特权代理。".to_string(),
        output: Some(String::from_utf8_lossy(&output).to_string()),
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
        assert!(cmd.starts_with("(CLASSPATH='"));
        assert!(cmd.ends_with(")&"));
        assert!(cmd.contains("app_process /system/bin"));
        assert!(cmd.contains("io.github.lianxia233.privbroker.ServiceStarter"));
        assert!(cmd.contains("--token='0123456789abcdef0123456789abcdef'"));
        assert!(cmd.contains("--endpoint='abstract'"));
        assert!(cmd.contains("--lib='/data/app/~~abc/lib/arm64'"));
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
}
