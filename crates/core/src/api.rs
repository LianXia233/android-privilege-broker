//! JSON 门面：JNI 层只暴露 `dispatch(method, argsJson) -> json` 一个入口。

use serde::de::DeserializeOwned;
use serde_json::{json, Value};

use crate::auth;
use crate::config;
use crate::error::{CoreError, Result};
use crate::install;
use crate::logbook::{self, OpContext};
use crate::model::{Config, InstallOptions, InstallPlan, UninstallOptions};
use crate::platform::{self, PlatformBridge};
use crate::state;
use crate::uninstall;

pub struct Core<B: PlatformBridge> {
    bridge: B,
}

fn error_kind(e: &CoreError) -> &'static str {
    e.kind()
}

fn opt<T: DeserializeOwned + Default>(a: &Value, key: &str) -> T {
    a.get(key)
        .and_then(|v| serde_json::from_value(v.clone()).ok())
        .unwrap_or_default()
}

fn string_list(a: &Value, key: &str) -> Vec<String> {
    a.get(key)
        .and_then(Value::as_array)
        .map(|v| v.iter().filter_map(Value::as_str).map(str::to_string).collect())
        .unwrap_or_default()
}

impl<B: PlatformBridge> Core<B> {
    pub fn new(bridge: B) -> Self {
        Self { bridge }
    }

    pub fn bridge(&self) -> &B {
        &self.bridge
    }

    /// 永不 panic：任何错误都编码为 JSON 返回。
    pub fn dispatch(&self, method: &str, args_json: &str) -> String {
        let args: Value = serde_json::from_str(args_json).unwrap_or_else(|_| json!({}));
        match self.inner(method, &args) {
            Ok(data) => json!({ "ok": true, "data": data }).to_string(),
            Err(e) => json!({
                "ok": false,
                "error": { "kind": error_kind(&e), "message": e.to_string() }
            })
            .to_string(),
        }
    }

    fn cfg(&self) -> Config {
        platform::config_load(&self.bridge).unwrap_or_default()
    }

    fn save_cfg(&self, c: &Config) -> Result<()> {
        platform::config_save(&self.bridge, c)
    }

    fn inner(&self, method: &str, a: &Value) -> Result<Value> {
        match method {
            "status" => {
                let raw = platform::broker_state(&self.bridge)?;
                let st = state::classify(&raw);
                Ok(json!({
                    "state": st,
                    "guidance": state::guidance(st),
                    "raw": raw,
                    "config": self.cfg(),
                }))
            }
            "requestAuthorization" => {
                let granted = platform::request_permission(&self.bridge)?;
                let raw = platform::broker_state(&self.bridge)?;
                let st = state::classify(&raw);
                Ok(json!({ "granted": granted, "state": st, "guidance": state::guidance(st) }))
            }
            "setConfig" => {
                // 按字段合并：UI 侧只回传它关心的字段，不能把新增的授权配置重置成默认值。
                let mut merged = serde_json::to_value(self.cfg())?;
                if let (Some(dst), Some(src)) = (
                    merged.as_object_mut(),
                    a.get("config").and_then(Value::as_object),
                ) {
                    for (k, v) in src {
                        dst.insert(k.clone(), v.clone());
                    }
                }
                let c: Config = serde_json::from_value(merged)?;
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            // ------------------------------------------------ 调用方授权
            "callerDecision" => {
                let caller = a["caller"].as_str().unwrap_or("");
                let cfg = self.cfg();
                let d = auth::decide(&cfg, caller);
                let label = platform::caller_label(&self.bridge, &auth::normalize(caller))
                    .unwrap_or_default();
                Ok(json!({
                    "decision": d.as_str(),
                    "caller": auth::normalize(caller),
                    "callerLabel": label,
                    "authEnabled": cfg.caller_auth_enabled,
                }))
            }
            "callerGrant" => {
                let mut c = self.cfg();
                auth::grant(&mut c, a["caller"].as_str().unwrap_or(""),
                            a["remember"].as_bool().unwrap_or(true))?;
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "callerDeny" => {
                let mut c = self.cfg();
                auth::deny(&mut c, a["caller"].as_str().unwrap_or(""),
                           a["remember"].as_bool().unwrap_or(true))?;
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "callerSet" => {
                let mut c = self.cfg();
                auth::set_caller(&mut c, a["caller"].as_str().unwrap_or(""),
                                 a["allowed"].as_bool().unwrap_or(false))?;
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "callerClear" => {
                let mut c = self.cfg();
                auth::clear_all(&mut c);
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "installedApps" => platform::installed_apps(&self.bridge),
            "appLabel" => {
                let pkg = a["package"].as_str().unwrap_or("");
                let label = platform::app_label(&self.bridge, pkg).unwrap_or_default();
                Ok(json!({ "package": pkg, "label": label }))
            }
            "allowListAdd" => {
                let mut c = self.cfg();
                config::allow_list_add(&mut c, a["package"].as_str().unwrap_or(""))?;
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "allowListRemove" => {
                let mut c = self.cfg();
                config::allow_list_remove(&mut c, a["package"].as_str().unwrap_or(""));
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "allowListClear" => {
                let mut c = self.cfg();
                config::allow_list_clear(&mut c);
                self.save_cfg(&c)?;
                Ok(json!({ "config": c }))
            }
            "prepareInstall" => {
                let p = install::prepare(
                    &self.bridge,
                    &string_list(a, "uris"),
                    &opt::<InstallOptions>(a, "options"),
                    &self.cfg(),
                )?;
                Ok(json!({ "plan": p, "summary": install::describe(&p) }))
            }
            "commitInstall" => {
                let p: InstallPlan = serde_json::from_value(
                    a.get("plan").cloned().ok_or_else(|| CoreError::InvalidArgument("缺少 plan".into()))?,
                )?;
                let ctx = OpContext::from_json(a);
                let out = install::execute_with_context(
                    &self.bridge, &p, &opt::<InstallOptions>(a, "options"), &ctx,
                )?;
                Ok(json!({ "result": out }))
            }
            "uninstall" => {
                let ctx = OpContext::from_json(a);
                let out = uninstall::uninstall_with_context(
                    &self.bridge,
                    a["package"].as_str().unwrap_or(""),
                    &opt::<UninstallOptions>(a, "options"),
                    &ctx,
                )?;
                Ok(json!({ "result": out }))
            }
            "launch" => {
                let out = uninstall::launch(&self.bridge, a["package"].as_str().unwrap_or(""))?;
                Ok(json!({ "result": out }))
            }
            "logList" => {
                let limit = a["limit"].as_u64().unwrap_or(logbook::DEFAULT_LIST_LIMIT as u64) as usize;
                let entries = logbook::list(&self.bridge, limit)?;
                Ok(json!({ "entries": entries, "text": logbook::export_text(&entries) }))
            }
            "logExport" => {
                let entries = logbook::list(&self.bridge, 1000)?;
                Ok(json!({ "text": logbook::export_text(&entries) }))
            }
            "logClear" => {
                logbook::clear(&self.bridge)?;
                Ok(json!({ "cleared": true }))
            }
            // 补记核心未覆盖的事件（例如准备阶段失败），格式与核心日志一致。
            "logRecord" => {
                logbook::append(&self.bridge, &crate::logbook::LogEntry::from_json(a));
                Ok(json!({ "recorded": true }))
            }
            other => Err(CoreError::InvalidArgument(format!("未知方法: {other}"))),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock::MockBridge;

    #[test]
    fn dispatch_never_panics_on_bad_input() {
        let c = Core::new(MockBridge::new());
        let r: Value = serde_json::from_str(&c.dispatch("status", "not-json")).unwrap();
        assert_eq!(r["ok"], true); // 空参数 -> 默认状态
        let r: Value = serde_json::from_str(&c.dispatch("nope", "{}")).unwrap();
        assert_eq!(r["ok"], false);
        assert_eq!(r["error"]["kind"], "INVALID_ARGUMENT");
    }

    #[test]
    fn status_reports_unavailable_with_guidance() {
        let b = MockBridge::new();
        b.push("brokerState", json!({"available":false,"permission":0}));
        b.push("configLoad", json!({}));
        let c = Core::new(b);
        let r: Value = serde_json::from_str(&c.dispatch("status", "{}")).unwrap();
        assert_eq!(r["data"]["state"], "UNAVAILABLE");
        assert_ne!(r["data"]["guidance"]["action"], "");
    }

    #[test]
    fn log_list_export_and_manual_record() {
        let b = MockBridge::new();
        b.push("logList", json!({"entries":[
            {"time":"2026-11-06 11:52:33","op":"install","label":"a.apk","ok":true,"source":"silent"}
        ]}));
        let c = Core::new(b);
        let r: Value = serde_json::from_str(&c.dispatch("logList", "{}")).unwrap();
        assert_eq!(r["ok"], true);
        assert_eq!(r["data"]["entries"].as_array().unwrap().len(), 1);
        assert!(r["data"]["text"].as_str().unwrap().contains("a.apk"));

        let r2: Value = serde_json::from_str(&c.dispatch(
            "logRecord",
            r#"{"label":"b.apk","source":"silent","code":"SESSION","detail":"boom"}"#,
        )).unwrap();
        assert_eq!(r2["ok"], true);
        let (_, rec) = c.bridge().calls().into_iter()
            .find(|(m, _)| m == "logAppend").unwrap();
        assert_eq!(rec["source"], "silent");
        assert_eq!(rec["code"], "SESSION");
        assert_eq!(rec["label"], "b.apk");
    }

    #[test]
    fn allow_list_roundtrip() {
        let b = MockBridge::new();
        b.push("configLoad", json!({"confirmBeforeInstall":true,"deleteApkAfterInstall":false,"allowList":[]}));
        let c = Core::new(b);
        let r: Value = serde_json::from_str(&c.dispatch("allowListAdd", r#"{"package":"dev.example.app"}"#)).unwrap();
        assert_eq!(r["ok"], true);
        let saved = c.bridge().calls().into_iter().find(|(m, _)| m == "configSave").unwrap().1;
        assert_eq!(saved["allowList"][0], "dev.example.app");
    }
}
