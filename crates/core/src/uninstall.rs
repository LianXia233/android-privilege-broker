use crate::error::{CoreError, Result};
use crate::install::require_authorization;
use crate::logbook::{self, LogEntry, OpContext};
use crate::model::{OpOutcome, UninstallOptions};
use crate::parse;
use crate::plan;
use crate::platform::{self, PlatformBridge, StdinSource};
use crate::util::is_valid_package_name;

/// F-03 静默卸载；成功与失败都会写入日志。
pub fn uninstall(
    b: &dyn PlatformBridge,
    package: &str,
    opts: &UninstallOptions,
) -> Result<OpOutcome> {
    uninstall_with_context(b, package, opts, &OpContext::default())
}

pub fn uninstall_with_context(
    b: &dyn PlatformBridge,
    package: &str,
    opts: &UninstallOptions,
    ctx: &OpContext,
) -> Result<OpOutcome> {
    let started = crate::platform::now_millis(b);
    let result = uninstall_inner(b, package, opts);
    let duration_ms = crate::platform::now_millis(b).saturating_sub(started);

    let mut entry = LogEntry::new("uninstall", ctx);
    if entry.label == "安装包" {
        entry.label = package.trim().to_string();
    }
    // 平台侧未传 appName 时，至少保证标题是包名而不是空洞的「安装包」；
    // 真正的应用中文名由 Java 在 context.appName 中提供。
    if entry.app_name.as_deref() == Some("安装包") || entry.app_name.is_none() {
        entry.app_name = Some(entry.label.clone());
    }
    entry.package = Some(package.trim().to_string());
    entry.duration_ms = duration_ms;
    match &result {
        Ok(o) => {
            entry.ok = o.ok;
            entry.code = o.code.clone();
            entry.detail = logbook::truncate(&o.raw, logbook::DETAIL_LIMIT);
        }
        Err(e) => {
            entry.ok = false;
            entry.code = e.kind().to_string();
            entry.detail = logbook::truncate(&e.to_string(), logbook::DETAIL_LIMIT);
        }
    }
    logbook::append(b, &entry);
    result
}

fn uninstall_inner(
    b: &dyn PlatformBridge,
    package: &str,
    opts: &UninstallOptions,
) -> Result<OpOutcome> {
    require_authorization(b)?;
    let pkg = package.trim();
    if !is_valid_package_name(pkg) {
        return Err(CoreError::InvalidArgument(format!("非法包名: {pkg}")));
    }
    platform::publish_progress(b, "uninstall", 0, 1);
    let r = platform::exec(b, &plan::uninstall_args(pkg, opts), &StdinSource::None)?;
    let outcome = parse::uninstall_outcome(&r);
    platform::publish_progress(b, "done", 1, 1);
    Ok(outcome)
}

/// 启动已安装应用的 LAUNCHER Activity（F-05）。
pub fn launch(b: &dyn PlatformBridge, package: &str) -> Result<OpOutcome> {
    require_authorization(b)?;
    let pkg = package.trim();
    if !is_valid_package_name(pkg) {
        return Err(CoreError::InvalidArgument(format!("非法包名: {pkg}")));
    }
    let resolved = platform::exec(b, &plan::resolve_activity_args(pkg), &StdinSource::None)?;
    let component = parse::resolve_activity_component(&resolved.combined()).ok_or_else(|| {
        CoreError::Rejected(format!("找不到 {pkg} 的启动 Activity: {}", resolved.combined().trim()))
    })?;
    let r = platform::exec(b, &plan::am_start_args(&component), &StdinSource::None)?;
    if r.code == 0 {
        Ok(OpOutcome::success(r.combined()))
    } else {
        Ok(OpOutcome {
            ok: false,
            code: "LAUNCH_FAILED".into(),
            message: r.combined().trim().to_string(),
            raw: r.combined(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock::MockBridge;
    use serde_json::json;

    fn authorized(b: &MockBridge) {
        b.push("brokerState", json!({"available":true,"permission":1}));
    }

    #[test]
    fn rejects_shell_metacharacters() {
        let b = MockBridge::new();
        authorized(&b);
        let e = uninstall(&b, "a.b; rm -rf /", &UninstallOptions::default()).unwrap_err();
        assert!(matches!(e, CoreError::InvalidArgument(_)));
    }

    #[test]
    fn uninstall_success() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let o = uninstall(&b, "dev.example.app", &UninstallOptions::default()).unwrap();
        assert!(o.ok);
        let (_m, a) = &b.calls().into_iter().find(|(m, _)| m == "exec").unwrap();
        assert_eq!(a["argv"].as_array().unwrap().last().unwrap(), "dev.example.app");
    }

    #[test]
    fn uninstall_writes_log_entry() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let ctx = OpContext { label: "dev.example.app".into(), source: "silent".into(), ..Default::default() };
        let o = uninstall_with_context(&b, "dev.example.app", &UninstallOptions::default(), &ctx)
            .unwrap();
        assert!(o.ok);
        let (_, a) = b.calls().into_iter().find(|(m, _)| m == "logAppend").unwrap();
        assert_eq!(a["op"], "uninstall");
        assert_eq!(a["ok"], true);
        assert_eq!(a["source"], "silent");
        assert_eq!(a["package"], "dev.example.app");
    }

    #[test]
    fn uninstall_failure_keeps_raw_output() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("exec", json!({"code":1,"stdout":"Failure [DELETE_FAILED_INTERNAL_ERROR]","stderr":""}));
        let o = uninstall(&b, "dev.example.app", &UninstallOptions::default()).unwrap();
        assert!(!o.ok);
        assert_eq!(o.code, "DELETE_FAILED_INTERNAL_ERROR");
        assert!(o.raw.contains("DELETE_FAILED_INTERNAL_ERROR"));
    }
}
