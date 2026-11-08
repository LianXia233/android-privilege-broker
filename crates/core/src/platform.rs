use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::error::{CoreError, Result};
use crate::model::{ArchiveEntry, BrokerStateRaw, SourceDescriptor};

/// 命令 stdin 的来源。UI 侧只搬运字节，不做任何业务判断。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum StdinSource {
    None,
    /// 直接把一个 content:// URI 的字节流作为 stdin。
    Uri { uri: String },
    /// 把归档内某个条目的解压字节流作为 stdin。
    ArchiveEntry { uri: String, entry: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExecResult {
    pub code: i32,
    pub stdout: String,
    pub stderr: String,
}

impl ExecResult {
    pub fn combined(&self) -> String {
        match (self.stdout.is_empty(), self.stderr.is_empty()) {
            (true, true) => String::new(),
            (false, true) => self.stdout.clone(),
            (true, false) => self.stderr.clone(),
            (false, false) => format!("{}\n{}", self.stdout, self.stderr),
        }
    }
}

/// 平台桥接：唯一一个原语。Android 侧由 JNI 转发到 Java `BrokerBridge.call`。
///
/// 所有入参/出参都是 JSON，便于审计与在 host 侧完整模拟。
pub trait PlatformBridge {
    fn call(&self, method: &str, args: &Value) -> Result<Value>;
}

fn field_str<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}

/// 平台桥接的统一错误闸门。Java 侧异常以 `{"error": "..."}` 返回；
/// 绝不能让调用方把它当成「成功但字段缺失」，否则真实故障（URI 读权限失效、
/// broken pipe、特权代理连接 断开等）会被静默降级成
/// `code=-1 / 空 stdout / 空 stderr`，日志里只剩一个无意义的 exit -1。
fn checked(method: &str, v: Value) -> Result<Value> {
    if let Some(msg) = v.get("error").and_then(Value::as_str) {
        return Err(CoreError::Bridge(format!("平台调用 {method} 失败: {msg}")));
    }
    Ok(v)
}

pub fn exec(b: &dyn PlatformBridge, argv: &[String], stdin: &StdinSource) -> Result<ExecResult> {
    let v = checked(
        "exec",
        b.call("exec", &json!({ "argv": argv, "stdin": stdin }))?,
    )?;
    Ok(ExecResult {
        code: v.get("code").and_then(Value::as_i64).unwrap_or(-1) as i32,
        stdout: field_str(&v, "stdout").to_string(),
        stderr: field_str(&v, "stderr").to_string(),
    })
}

pub fn stat(b: &dyn PlatformBridge, uri: &str) -> Result<SourceDescriptor> {
    let v = checked("stat", b.call("stat", &json!({ "uri": uri }))?)?;
    let uri = field_str(&v, "uri").to_string();
    if uri.is_empty() {
        return Err(CoreError::Bridge("stat 未返回 uri".into()));
    }
    Ok(SourceDescriptor {
        uri,
        display_name: field_str(&v, "displayName").to_string(),
        size: v.get("size").and_then(Value::as_u64).unwrap_or(0),
        kind: field_str(&v, "kind").to_string(),
    })
}

pub fn archive_entries(b: &dyn PlatformBridge, uri: &str) -> Result<Vec<ArchiveEntry>> {
    let v = checked(
        "archiveEntries",
        b.call("archiveEntries", &json!({ "uri": uri }))?,
    )?;
    let arr = v.get("entries").and_then(Value::as_array).cloned().unwrap_or_default();
    let mut out = Vec::new();
    for e in arr {
        out.push(ArchiveEntry {
            name: field_str(&e, "name").to_string(),
            size: e.get("size").and_then(Value::as_u64).unwrap_or(0),
        });
    }
    Ok(out)
}

/// 探测特权代理状态。
///
/// 这是一次**软探测**：代理没运行、端点不可连、握手被拒都属于正常业务状态，
/// 要由 `state::classify` 转成面向用户的指导，而不能当成桥接层故障。所以平台
/// 侧把探测失败原因放在独立的 `probeError`，不占用 `error`（后者只表示"这次
/// 桥接调用本身坏了"）。此前探测失败也写 `error`，被 [`checked`] 统一升级成
/// 致命错误，`classify` 里为"在跑但拒绝握手"准备的分支在生产环境根本走不到，
/// 状态查询与安装准备会一起整体失败。
pub fn broker_state(b: &dyn PlatformBridge) -> Result<BrokerStateRaw> {
    let v = checked("brokerState", b.call("brokerState", &json!({}))?)?;
    Ok(BrokerStateRaw {
        available: v.get("available").and_then(Value::as_bool).unwrap_or(false),
        permission: v.get("permission").and_then(Value::as_i64).unwrap_or(-1) as i32,
        server_version: v.get("serverVersion").and_then(Value::as_i64).unwrap_or(0) as i32,
        server_uid: v.get("serverUid").and_then(Value::as_i64).unwrap_or(-1) as i32,
        reachable: v.get("reachable").and_then(Value::as_bool).unwrap_or(false),
        error: field_str(&v, "probeError").to_string(),
    })
}

pub fn request_permission(b: &dyn PlatformBridge) -> Result<bool> {
    let v = checked("requestAuthorization", b.call("requestAuthorization", &json!({}))?)?;
    Ok(v.get("granted").and_then(Value::as_bool).unwrap_or(false))
}

/// 读取某个 stdin 来源的字节（hex 编码），上限由调用方控制。
pub fn read_bytes(b: &dyn PlatformBridge, stdin: &StdinSource, limit: usize) -> Result<Vec<u8>> {
    let v = checked(
        "readBytes",
        b.call("readBytes", &json!({ "stdin": stdin, "limit": limit }))?,
    )?;
    let hex = field_str(&v, "hex");
    crate::util::hex_decode(hex)
}

pub fn delete_source(b: &dyn PlatformBridge, uri: &str) -> Result<bool> {
    let v = checked("deleteSource", b.call("deleteSource", &json!({ "uri": uri }))?)?;
    Ok(v.get("deleted").and_then(Value::as_bool).unwrap_or(false))
}

/// 把配置读写也走同一个桥（避免在 Rust 侧引入文件 I/O 差异）。
pub fn config_load(b: &dyn PlatformBridge) -> Result<crate::model::Config> {
    let v = checked("configLoad", b.call("configLoad", &json!({}))?)?;
    serde_json::from_value(v).map_err(CoreError::from)
}

pub fn config_save(b: &dyn PlatformBridge, cfg: &crate::model::Config) -> Result<()> {
    let v = checked("configSave", b.call("configSave", &serde_json::to_value(cfg)?)?)?;
    // 平台侧可以返回 {}（fire-and-forget），只有显式 ok:false 才算失败。
    if v.get("ok").and_then(Value::as_bool) == Some(false) {
        return Err(CoreError::Bridge("configSave 失败".into()));
    }
    Ok(())
}

/// 请求平台侧发送通知/更新 UI 进度（fire-and-forget）。
pub fn publish_progress(b: &dyn PlatformBridge, stage: &str, index: usize, total: usize) {
    let _ = b.call("progress", &json!({ "stage": stage, "index": index, "total": total }));
}

/// 读取系统应用列表（纯 I/O，由平台侧 PackageManager 提供）。
pub fn installed_apps(b: &dyn PlatformBridge) -> Result<Value> {
    checked("installedApps", b.call("installedApps", &json!({}))?)
}

/// 把包名解析成应用真实名称（中文）。查不到时返回空串。
pub fn app_label(b: &dyn PlatformBridge, package: &str) -> Result<String> {
    let v = checked("appLabel", b.call("appLabel", &json!({ "package": package }))?)?;
    Ok(field_str(&v, "label").to_string())
}

/// 把调用方标识解析成可展示的名称（用于首次授权弹窗）。
pub fn caller_label(b: &dyn PlatformBridge, caller: &str) -> Result<String> {
    let v = checked("callerLabel", b.call("callerLabel", &json!({ "caller": caller }))?)?;
    Ok(field_str(&v, "label").to_string())
}

/// stdin 流式写入失败时的兜底：把该 split 中转成 system_server 可读的路径
/// （/data/local/tmp 下按次隔离的目录，由特权代理以 shell 身份复制，
/// 避开 FUSE 域的读权限限制），返回绝对路径，
/// 供 `pm install-write <session> <name> <path>` 使用。
pub fn stage_for_shell(
    b: &dyn PlatformBridge,
    stdin: &StdinSource,
    name: &str,
) -> Result<String> {
    let v = checked(
        "stageForShell",
        b.call("stageForShell", &json!({ "stdin": stdin, "name": name }))?,
    )?;
    let path = field_str(&v, "path");
    if path.is_empty() {
        return Err(CoreError::Bridge("stageForShell 未返回路径".into()));
    }
    Ok(path.to_string())
}

/// 清理本次安装产生的临时文件（fire-and-forget）。
pub fn cleanup_staged(b: &dyn PlatformBridge) {
    let _ = b.call("cleanupStaged", &json!({}));
}

/// 平台侧墙钟时间（毫秒），用于记录操作耗时。不可用时返回 0，不影响业务逻辑。
pub fn now_millis(b: &dyn PlatformBridge) -> u64 {
    b.call("now", &json!({}))
        .ok()
        .and_then(|v| v.get("millis").and_then(Value::as_u64))
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockBridge(Value);

    impl PlatformBridge for MockBridge {
        fn call(&self, _method: &str, _args: &Value) -> Result<Value> {
            Ok(self.0.clone())
        }
    }

    #[test]
    fn broker_state_keeps_probe_failure_soft() {
        // 代理不可用是业务状态：必须原样交给状态机，不能升级成桥接故障。
        let down = MockBridge(json!({
            "available": false,
            "permission": -1,
            "serverVersion": 0,
            "serverUid": -1,
            "reachable": true,
            "probeError": "java.lang.IllegalStateException: 握手失败: {\"code\":\"PROOF_MISMATCH\"}"
        }));
        let st = broker_state(&down).expect("探测失败不应升级为桥接故障");
        assert!(!st.available);
        assert!(st.reachable, "端点可连的信息必须保留，用于区分没跑与拒绝握手");
        assert!(st.error.contains("握手失败"));

        // 真正的桥接异常（error 字段）仍必须是致命错误。
        let fatal = MockBridge(json!({"error": "unknown"}));
        assert!(broker_state(&fatal).is_err());
    }

    #[test]
    fn checked_rejects_bridge_error_instead_of_faking_exit_code() {
        let e = checked("exec", json!({"error":"java.io.IOException: Broken pipe"}));
        assert!(e.is_err());
        assert!(e.unwrap_err().to_string().contains("Broken pipe"));
        let ok = checked("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        assert!(ok.is_ok());
    }
}
