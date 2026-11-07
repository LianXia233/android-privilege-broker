//! 安装 / 卸载操作的结构化日志。
//!
//! Rust 核心在**成功与失败两条路径**上生成记录，平台侧只负责持久化、补时间戳与上限裁剪。
//! 日志写入是 fire-and-forget：持久化失败绝不影响安装结果。

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::error::Result;
use crate::platform::PlatformBridge;

/// 单条记录里保留的原始输出上限（字符）。
pub const DETAIL_LIMIT: usize = 2000;
/// 平台侧请求历史时默认返回的最大条数。
pub const DEFAULT_LIST_LIMIT: usize = 200;

/// 一次操作的展示上下文，由 UI / 静默入口显式传入。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct OpContext {
    /// 展示名：优先应用真实名称，退回安装包文件名。
    pub label: String,
    /// 触发来源：ui / silent。
    pub source: String,
    /// 应用真实名称（中文），与包名区分。
    pub app_name: Option<String>,
    /// 发起本次静默安装的调用方包名；应用内触发为 null。
    pub caller: Option<String>,
}

impl Default for OpContext {
    fn default() -> Self {
        Self { label: "安装包".into(), source: "ui".into(), app_name: None, caller: None }
    }
}

impl OpContext {
    /// 从 dispatch 参数里的 `context` 对象解析，缺失字段用安全默认值。
    pub fn from_json(a: &Value) -> Self {
        let c = a.get("context");
        let field = |key: &str| -> Option<String> {
            c.and_then(|v| v.get(key))
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
        };
        let source = field("source")
            .map(|s| if s == "silent" || s == "ui" { s } else { "ui".into() })
            .unwrap_or_else(|| "ui".into());
        let app_name = field("appName").map(|s| truncate(&s, 160));
        // 没有显式 appName 时，把 label 当作应用名（label 已由平台侧解析为中文名）。
        Self {
            label: truncate(&field("label").unwrap_or_else(|| "安装包".into()), 160),
            source,
            app_name: app_name.clone().or_else(|| field("label").map(|s| truncate(&s, 160))),
            caller: field("caller").map(|s| truncate(&s, 160)),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LogEntry {
    /// install / uninstall
    pub op: String,
    pub label: String,
    /// 应用真实名称（中文），用于通知 / 日志标题，避免只显示包名。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub app_name: Option<String>,
    /// 发起静默安装的调用方包名。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub caller: Option<String>,
    pub package: Option<String>,
    pub version: Option<String>,
    pub parts: usize,
    pub total_size: u64,
    pub ok: bool,
    pub code: String,
    pub detail: String,
    pub stages: Vec<String>,
    pub duration_ms: u64,
    pub session_id: Option<i64>,
    pub source: String,
}

impl Default for LogEntry {
    fn default() -> Self {
        Self {
            op: "install".into(),
            label: "安装包".into(),
            app_name: None,
            caller: None,
            package: None,
            version: None,
            parts: 0,
            total_size: 0,
            ok: false,
            code: "UNKNOWN".into(),
            detail: String::new(),
            stages: Vec::new(),
            duration_ms: 0,
            session_id: None,
            source: "ui".into(),
        }
    }
}

impl LogEntry {
    pub fn new(op: &str, ctx: &OpContext) -> Self {
        Self {
            op: op.to_string(),
            label: ctx.label.clone(),
            app_name: ctx.app_name.clone().or_else(|| Some(ctx.label.clone())),
            caller: ctx.caller.clone(),
            source: ctx.source.clone(),
            ..Self::default()
        }
    }

    /// 用于平台侧补记核心未覆盖的事件（例如准备阶段失败）。
    pub fn from_json(a: &Value) -> Self {
        let mut e = Self::default();
        let s = |k: &str| a.get(k).and_then(Value::as_str).unwrap_or("").to_string();
        let opt = |k: &str| a.get(k).and_then(Value::as_str)
            .map(str::trim).filter(|x| !x.is_empty()).map(|x| truncate(x, 160));
        e.op = match s("op").as_str() {
            "uninstall" => "uninstall".into(),
            _ => "install".into(),
        };
        let label = s("label");
        e.label = if label.is_empty() { "安装包".into() } else { truncate(&label, 160) };
        e.app_name = opt("appName").or_else(|| {
            if label.trim().is_empty() { None } else { Some(truncate(label.trim(), 160)) }
        });
        e.caller = opt("caller");
        e.package = a.get("package").and_then(Value::as_str).filter(|x| !x.is_empty()).map(str::to_string);
        e.version = a.get("version").and_then(Value::as_str).filter(|x| !x.is_empty()).map(str::to_string);
        e.parts = a.get("parts").and_then(Value::as_u64).unwrap_or(0) as usize;
        e.total_size = a.get("totalSize").and_then(Value::as_u64).unwrap_or(0);
        e.ok = a.get("ok").and_then(Value::as_bool).unwrap_or(false);
        e.code = if s("code").is_empty() { "UNKNOWN".into() } else { s("code") };
        e.detail = truncate(&s("detail"), DETAIL_LIMIT);
        e.stages = a.get("stages").and_then(Value::as_array)
            .map(|v| v.iter().filter_map(Value::as_str).map(str::to_string).collect())
            .unwrap_or_default();
        e.duration_ms = a.get("durationMs").and_then(Value::as_u64).unwrap_or(0);
        e.session_id = a.get("sessionId").and_then(Value::as_i64);
        e.source = if s("source") == "silent" { "silent".into() } else { "ui".into() };
        e
    }
}

/// 按字符数裁剪，避免超长 shell 输出撑爆日志文件。
pub fn truncate(s: &str, limit: usize) -> String {
    if s.chars().count() <= limit {
        return s.to_string();
    }
    let mut out: String = s.chars().take(limit).collect();
    out.push('…');
    out
}

/// 追加一条记录。任何失败都被吞掉。
pub fn append(b: &dyn PlatformBridge, entry: &LogEntry) {
    let v = serde_json::to_value(entry).unwrap_or_else(|_| json!({}));
    let _ = b.call("logAppend", &v);
}

/// 读取历史记录，返回平台侧原样数组。
pub fn list(b: &dyn PlatformBridge, limit: usize) -> Result<Vec<Value>> {
    let n = limit.clamp(1, 1000);
    let v = b.call("logList", &json!({ "limit": n }))?;
    Ok(v.get("entries").and_then(Value::as_array).cloned().unwrap_or_default())
}

pub fn clear(b: &dyn PlatformBridge) -> Result<()> {
    let _ = b.call("logClear", &json!({}))?;
    Ok(())
}

/// 把历史记录导出为可分享的纯文本（最新的在最前）。
pub fn export_text(entries: &[Value]) -> String {
    if entries.is_empty() {
        return "（暂无记录）\n".into();
    }
    let mut out = String::new();
    for e in entries {
        let s = |k: &str| e.get(k).and_then(Value::as_str).unwrap_or("").to_string();
        let ok = e.get("ok").and_then(Value::as_bool).unwrap_or(false);
        let op = if s("op") == "uninstall" { "卸载" } else { "安装" };
        let result = if ok { "成功" } else { "失败" };
        // 标题优先展示应用真实名称，包名放到下一行，避免「只显示包名」。
        let name = {
            let n = s("appName");
            if n.is_empty() { s("label") } else { n }
        };
        out.push_str(&format!("[{}] {} {} · {}\n", s("time"), op, result, name));
        let mut meta = Vec::new();
        let pkg = s("package");
        if !pkg.is_empty() {
            meta.push(format!("包名 {}", pkg));
        }
        if !s("version").is_empty() {
            meta.push(format!("版本 {}", s("version")));
        }
        let parts = e.get("parts").and_then(Value::as_u64).unwrap_or(0);
        if parts > 0 {
            meta.push(format!("分片 {}", parts));
        }
        let size = e.get("totalSize").and_then(Value::as_u64).unwrap_or(0);
        if size > 0 {
            meta.push(format!("大小 {}", crate::util::human_size(size)));
        }
        meta.push(format!("来源 {}", if s("source") == "silent" { "后台静默" } else { "应用内" }));
        let caller = s("caller");
        if !caller.is_empty() {
            meta.push(format!("调用方 {}", caller));
        }
        let ms = e.get("durationMs").and_then(Value::as_u64).unwrap_or(0);
        meta.push(format!("耗时 {} ms", ms));
        if let Some(id) = e.get("sessionId").and_then(Value::as_i64) {
            meta.push(format!("会话 {}", id));
        }
        out.push_str("  ");
        out.push_str(&meta.join(" · "));
        out.push('\n');
        if !ok {
            let code = s("code");
            let zh = crate::util::error_zh(&code);
            if zh.is_empty() {
                out.push_str(&format!("  原因 {}\n", code));
            } else {
                out.push_str(&format!("  原因 {}（{}）\n", zh, code));
            }
        }
        let detail = s("detail").trim().to_string();
        if !detail.is_empty() {
            out.push_str("  ");
            out.push_str(&truncate(&detail.replace('\n', "\n  "), 1200));
            out.push('\n');
        }
        out.push('\n');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock::MockBridge;

    #[test]
    fn context_parses_and_sanitizes() {
        let c = OpContext::from_json(&json!({"context":{"label":"  demo.apk  ","source":"silent"}}));
        assert_eq!(c.label, "demo.apk");
        assert_eq!(c.source, "silent");
        let d = OpContext::from_json(&json!({"context":{"source":"weird"}}));
        assert_eq!(d.label, "安装包");
        assert_eq!(d.source, "ui");
    }

    #[test]
    fn append_emits_structured_entry() {
        let b = MockBridge::new();
        let mut e = LogEntry::new("install", &OpContext::default());
        e.ok = true;
        e.code = "SUCCESS".into();
        e.package = Some("dev.example.app".into());
        append(&b, &e);
        let (m, a) = &b.calls()[0];
        assert_eq!(m, "logAppend");
        assert_eq!(a["op"], "install");
        assert_eq!(a["ok"], true);
        assert_eq!(a["code"], "SUCCESS");
        assert_eq!(a["package"], "dev.example.app");
        assert_eq!(a["source"], "ui");
    }

    #[test]
    fn manual_record_defaults_are_safe() {
        let e = LogEntry::from_json(&json!({"label":"x.apk","code":"SESSION","detail":"boom","source":"silent"}));
        assert_eq!(e.label, "x.apk");
        assert_eq!(e.code, "SESSION");
        assert_eq!(e.source, "silent");
        assert!(!e.ok);
        assert_eq!(e.op, "install");
    }

    #[test]
    fn truncate_is_char_based() {
        assert_eq!(truncate("静默安装", 20), "静默安装");
        assert_eq!(truncate("abcdef", 3), "abc…");
    }

    #[test]
    fn export_text_is_human_readable() {
        let entries = vec![json!({
            "time":"2026-11-06 11:52:33","op":"install","label":"demo.apk",
            "package":"dev.example.app","version":"1.0","parts":2,"totalSize":4096,
            "ok":false,"code":"INSTALL_FAILED","detail":"Failure [INSTALL_FAILED_ABORTED]",
            "durationMs":812,"sessionId":777,"source":"silent"
        })];
        let t = export_text(&entries);
        assert!(t.contains("安装 失败 · demo.apk"));
        assert!(t.contains("后台静默"));
        assert!(t.contains("INSTALL_FAILED"));
        assert!(t.contains("会话 777"));
        assert!(export_text(&[]).contains("暂无记录"));
    }
}
