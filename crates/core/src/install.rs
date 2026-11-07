use serde::Serialize;
use serde_json::json;

use crate::apk_meta;
use crate::archive;
use crate::config;
use crate::error::{CoreError, Result};
use crate::logbook::{self, LogEntry, OpContext};
use crate::model::{Config, InstallOptions, InstallPlan, OpOutcome, Progress, SplitPart};
use crate::parse;
use crate::plan;
use crate::platform::{self, PlatformBridge, StdinSource};
use crate::state;
use crate::util;

const MANIFEST_ENTRY: &str = "AndroidManifest.xml";
const META_LIMIT: usize = 512 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct InstallOutcome {
    pub outcome: OpOutcome,
    pub session_id: Option<i64>,
    pub stages: Vec<Progress>,
    pub deleted_source: bool,
}

pub(crate) fn require_authorization(b: &dyn PlatformBridge) -> Result<()> {
    let raw = platform::broker_state(b)?;
    let st = state::classify(&raw);
    if st != crate::model::BrokerStatus::Authorized {
        return Err(CoreError::BrokerNotReady(state::guidance(st).title));
    }
    Ok(())
}

fn stdin_uri(part: &SplitPart) -> Option<String> {
    match &part.stdin {
        StdinSource::Uri { uri } => Some(uri.clone()),
        StdinSource::ArchiveEntry { uri, .. } => Some(uri.clone()),
        StdinSource::None => None,
    }
}

/// F-02 准备阶段：检查授权、枚举 split、推断元数据、判定是否需要确认。
pub fn prepare(
    b: &dyn PlatformBridge,
    uris: &[String],
    opts: &InstallOptions,
    cfg: &Config,
) -> Result<InstallPlan> {
    require_authorization(b)?;
    if uris.is_empty() || uris.len() > 32 {
        return Err(CoreError::InvalidArgument("安装包数量需在 1..32 之间".into()));
    }
    for u in uris {
        if u.is_empty() || u.len() > 4096 {
            return Err(CoreError::InvalidArgument("URI 无效".into()));
        }
    }

    let parts: Vec<SplitPart> = if uris.len() == 1 {
        let sd = platform::stat(b, &uris[0])?;
        let owned_kind = util::kind_of(&sd.display_name).to_string();
        let kind = if sd.kind.is_empty() { owned_kind.as_str() } else { sd.kind.as_str() };
        match kind {
            "apks" | "xapk" | "apkm" | "zip" => {
                let entries = platform::archive_entries(b, &sd.uri)?;
                archive::parts_from_archive(&sd.uri, &entries)?
            }
            _ => archive::parts_from_sources(&[(sd.uri, sd.display_name, sd.size)])?,
        }
    } else {
        let mut tuples = Vec::with_capacity(uris.len());
        for u in uris {
            let sd = platform::stat(b, u)?;
            tuples.push((sd.uri, sd.display_name, sd.size));
        }
        archive::parts_from_sources(&tuples)?
    };

    let total_size: u64 = parts.iter().map(|p| p.size).sum();
    if total_size == 0 {
        return Err(CoreError::InvalidArgument("无法获取安装包大小".into()));
    }

    // best-effort：仅对直接传入的 APK 解析 binary manifest；归档场景跳过。
    let (mut package_hint, mut version_hint, mut label_hint) = (None, None, None);
    if let Some(StdinSource::Uri { uri }) = parts.first().map(|p| &p.stdin) {
        let src = StdinSource::ArchiveEntry { uri: uri.clone(), entry: MANIFEST_ENTRY.into() };
        if let Ok(bytes) = platform::read_bytes(b, &src, META_LIMIT) {
            if let Some(m) = apk_meta::parse_axml(&bytes) {
                package_hint = m.package;
                version_hint = m.version_name;
                label_hint = m.label;
            }
        }
    }

    Ok(InstallPlan {
        parts,
        total_size,
        create_args: plan::install_create_args(opts, total_size),
        package_hint: package_hint.clone(),
        version_hint,
        label_hint,
        requires_confirmation: config::needs_confirmation(
            cfg,
            package_hint.as_deref(),
            opts.skip_confirm,
        ),
    })
}

/// F-02 执行阶段：会话式安装，字节流不落盘。
///
/// 该入口在成功与失败两条路径上都会写一条结构化日志，
/// 因此后台静默入口与应用内入口共用同一份记录，不会漏记。
pub fn execute(
    b: &dyn PlatformBridge,
    plan_data: &InstallPlan,
    opts: &InstallOptions,
) -> Result<InstallOutcome> {
    execute_with_context(b, plan_data, opts, &OpContext::default())
}

pub fn execute_with_context(
    b: &dyn PlatformBridge,
    plan_data: &InstallPlan,
    opts: &InstallOptions,
    ctx: &OpContext,
) -> Result<InstallOutcome> {
    let started = platform::now_millis(b);
    let result = execute_inner(b, plan_data, opts);
    let duration_ms = platform::now_millis(b).saturating_sub(started);

    let mut entry = LogEntry::new("install", ctx);
    // 平台侧没能解析出应用名时，用 binary manifest 的 label 兜底。
    if let Some(l) = &plan_data.label_hint {
        let cur = entry.app_name.as_deref().unwrap_or("").to_ascii_lowercase();
        if cur.ends_with(".apk") || cur.ends_with(".apks") || cur.ends_with(".xapk")
            || cur == "安装包" || cur.is_empty() {
            entry.app_name = Some(l.clone());
            entry.label = l.clone();
        }
    }
    entry.package = plan_data.package_hint.clone();
    entry.version = plan_data.version_hint.clone();
    entry.parts = plan_data.parts.len();
    entry.total_size = plan_data.total_size;
    entry.duration_ms = duration_ms;
    match &result {
        Ok(out) => {
            entry.ok = out.outcome.ok;
            entry.code = out.outcome.code.clone();
            entry.detail = logbook::truncate(&out.outcome.raw, logbook::DETAIL_LIMIT);
            entry.stages = out.stages.iter().map(|s| s.stage.clone()).collect();
            entry.session_id = out.session_id;
        }
        Err(e) => {
            entry.ok = false;
            entry.code = e.kind().to_string();
            entry.detail = logbook::truncate(&e.to_string(), logbook::DETAIL_LIMIT);
        }
    }
    // 日志是旁路：持久化失败不影响安装结果。
    logbook::append(b, &entry);
    // 清理兜底写入产生的临时文件；入口 Activity 已把外部 content:// 复制到
    // 应用私有目录并在下一次启动时按时间清理，这里不动源文件。
    platform::cleanup_staged(b);
    result
}

fn write_success(r: &crate::platform::ExecResult) -> bool {
    let raw = r.combined();
    r.code == 0
        && (raw.contains("Success") || raw.contains("streamed") || raw.trim().is_empty())
}

fn execute_inner(
    b: &dyn PlatformBridge,
    plan_data: &InstallPlan,
    opts: &InstallOptions,
) -> Result<InstallOutcome> {
    require_authorization(b)?;
    if plan_data.parts.is_empty() {
        return Err(CoreError::InvalidArgument("安装计划为空".into()));
    }

    let total = plan_data.parts.len() + 2usize;
    let mut stages = Vec::new();

    platform::publish_progress(b, "create_session", 0, total);
    let created = platform::exec(b, &plan_data.create_args, &StdinSource::None)?;
    let created_raw = created.combined();
    if created.code != 0 || !created_raw.contains("Success") {
        return Err(CoreError::Session(format!(
            "install-create 失败 (exit {}): {}",
            created.code,
            created_raw.trim()
        )));
    }
    let session = parse::parse_session_id(&created_raw)?;
    stages.push(Progress { stage: "create_session".into(), index: 0, total });

    for (i, part) in plan_data.parts.iter().enumerate() {
        platform::publish_progress(b, "write_split", i + 1, total);
        let argv = plan::install_write_args(session, &part.name, part.size);

        // 首选：stdin 流式写入（不落盘）。平台侧若抛异常（例如 URI 读权限已失效、
        // 管道破裂、特权代理连接 断开），不能再把它当成 exit -1 直接失败：
        // 先把该 split 复制到 shell 可读目录，用文件路径参数重试一次。
        let mut ok = false;
        let mut diagnostics = String::new();
        match platform::exec(b, &argv, &part.stdin) {
            Ok(r) => {
                ok = write_success(&r);
                if !ok {
                    diagnostics = format!("stdin 写入 exit {}: {}", r.code, r.combined().trim());
                }
            }
            Err(e) => diagnostics = format!("stdin 写入异常: {e}"),
        }

        if !ok {
            match platform::stage_for_shell(b, &part.stdin, &part.name) {
                Ok(path) => {
                    let pargv = plan::install_write_path_args(
                        session, &part.name, part.size, &path,
                    );
                    match platform::exec(b, &pargv, &StdinSource::None) {
                        Ok(r2) => {
                            ok = write_success(&r2);
                            diagnostics.push_str(&format!(
                                "; 文件路径重试 exit {}: {}",
                                r2.code,
                                r2.combined().trim()
                            ));
                        }
                        Err(e) => diagnostics.push_str(&format!("; 文件路径重试异常: {e}")),
                    }
                }
                Err(e) => diagnostics.push_str(&format!("; 无法准备文件路径: {e}")),
            }
        }

        if !ok {
            let _ = platform::exec(b, &plan::install_abandon_args(session), &StdinSource::None);
            return Err(CoreError::Write(format!(
                "写入 {} 失败: {}",
                part.name, diagnostics
            )));
        }
        stages.push(Progress { stage: format!("write_split:{}", part.name), index: i + 1, total });
    }

    platform::publish_progress(b, "commit", total - 1, total);
    let committed = platform::exec(b, &plan::install_commit_args(session), &StdinSource::None)?;
    let outcome = parse::install_outcome(&committed);
    stages.push(Progress { stage: "commit".into(), index: total - 1, total });

    let mut deleted_source = false;
    if outcome.ok && opts.delete_source_after {
        let mut seen = Vec::new();
        for p in &plan_data.parts {
            if let Some(u) = stdin_uri(p) {
                if !seen.contains(&u) {
                    seen.push(u.clone());
                    if platform::delete_source(b, &u).unwrap_or(false) {
                        deleted_source = true;
                    }
                }
            }
        }
    }
    platform::publish_progress(b, "done", total, total);

    Ok(InstallOutcome { outcome, session_id: Some(session), stages, deleted_source })
}

/// 供 UI 展示的计划摘要（不参与执行决策）。
pub fn describe(plan_data: &InstallPlan) -> serde_json::Value {
    json!({
        "parts": plan_data.parts.len(),
        "totalSize": plan_data.total_size,
        "totalSizeHuman": util::human_size(plan_data.total_size),
        "package": plan_data.package_hint,
        "version": plan_data.version_hint,
        "appLabel": plan_data.label_hint,
        "requiresConfirmation": plan_data.requires_confirmation,
        "splitNames": plan_data.parts.iter().map(|p| p.name.clone()).collect::<Vec<_>>(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mock::MockBridge;
    use serde_json::json;

    fn authorized(b: &MockBridge) {
        b.push("brokerState", json!({"available": true, "permission": 1, "serverVersion": 13, "serverUid": 2000}));
    }

    #[test]
    fn prepare_single_apk_builds_create_args() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("stat", json!({"uri":"content://apk/1","displayName":"demo.apk","size":4096,"kind":"apk"}));
        b.push("readBytes", json!({"hex":""}));
        let p = prepare(&b, &["content://apk/1".into()], &InstallOptions::default(), &Config::default()).unwrap();
        assert_eq!(p.parts.len(), 1);
        assert_eq!(p.parts[0].name, "base.apk");
        assert_eq!(p.total_size, 4096);
        assert_eq!(p.create_args.last().unwrap(), "4096");
        assert!(!p.requires_confirmation, "默认静默安装，不应要求确认");
    }

    #[test]
    fn prepare_archive_expands_splits() {
        let b = MockBridge::new();
        authorized(&b);
        b.push("stat", json!({"uri":"content://bundle","displayName":"app.apks","size":999,"kind":"apks"}));
        b.push("archiveEntries", json!({"entries":[
            {"name":"base.apk","size":100},
            {"name":"split_config.arm64_v8a.apk","size":50},
            {"name":"meta.json","size":5}
        ]}));
        let p = prepare(&b, &["content://bundle".into()], &InstallOptions::default(), &Config::default()).unwrap();
        assert_eq!(p.parts.len(), 2);
        assert_eq!(p.parts[0].name, "base.apk");
        assert_eq!(p.total_size, 150);
        assert!(!p.requires_confirmation);
    }

    #[test]
    fn prepare_rejects_without_authorization() {
        let b = MockBridge::new();
        b.push("brokerState", json!({"available": false, "permission": 0}));
        let e = prepare(&b, &["content://x".into()], &InstallOptions::default(), &Config::default()).unwrap_err();
        assert!(matches!(e, CoreError::BrokerNotReady(_)));
    }

    #[test]
    fn execute_full_session_flow() {
        let b = MockBridge::new();
        authorized(&b);
        for _ in 0..2 { authorized(&b); }
        b.push("exec", json!({"code":0,"stdout":"Success: created install session [777]","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success: streamed 10 bytes","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let plan_data = InstallPlan {
            parts: vec![SplitPart {
                name: "base.apk".into(), size: 10,
                stdin: StdinSource::Uri { uri: "content://a".into() },
            }],
            total_size: 10,
            create_args: plan::install_create_args(&InstallOptions::default(), 10),
            package_hint: None, version_hint: None, label_hint: None, requires_confirmation: false,
        };
        let out = execute(&b, &plan_data, &InstallOptions::default()).unwrap();
        assert!(out.outcome.ok);
        assert_eq!(out.session_id, Some(777));
        let calls = b.calls();
        let execs: Vec<_> = calls.iter().filter(|(m, _)| m == "exec").collect();
        assert_eq!(execs.len(), 3);
        assert!(execs[0].1["argv"].as_array().unwrap()[1].as_str() == Some("install-create"));
        assert!(execs[1].1["argv"].as_array().unwrap().last().unwrap().as_str() == Some("-"));
        assert!(execs[2].1["argv"].as_array().unwrap()[1].as_str() == Some("install-commit"));
    }

    fn sample_plan() -> InstallPlan {
        InstallPlan {
            parts: vec![SplitPart {
                name: "base.apk".into(), size: 10,
                stdin: StdinSource::Uri { uri: "content://a".into() },
            }],
            total_size: 10,
            create_args: plan::install_create_args(&InstallOptions::default(), 10),
            package_hint: Some("dev.example.app".into()),
            version_hint: Some("1.0".into()),
            label_hint: None,
            requires_confirmation: false,
        }
    }

    #[test]
    fn execute_logs_success_with_silent_context() {
        let b = MockBridge::new();
        for _ in 0..2 { authorized(&b); }
        b.push("exec", json!({"code":0,"stdout":"Success: created install session [777]","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success: streamed 10 bytes","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let ctx = OpContext { label: "demo.apk".into(), source: "silent".into(), ..Default::default() };
        let out = execute_with_context(&b, &sample_plan(), &InstallOptions::default(), &ctx).unwrap();
        assert!(out.outcome.ok);
        let (_, a) = b.calls().into_iter().find(|(m, _)| m == "logAppend").unwrap();
        assert_eq!(a["op"], "install");
        assert_eq!(a["ok"], true);
        assert_eq!(a["code"], "SUCCESS");
        assert_eq!(a["label"], "demo.apk");
        assert_eq!(a["source"], "silent");
        assert_eq!(a["package"], "dev.example.app");
        assert_eq!(a["sessionId"], 777);
    }

    #[test]
    fn execute_prefers_axml_app_name_over_file_name() {
        let b = MockBridge::new();
        for _ in 0..2 { authorized(&b); }
        b.push("exec", json!({"code":0,"stdout":"Success: created install session [777]","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success: streamed 10 bytes","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let mut plan_data = sample_plan();
        plan_data.label_hint = Some("示例应用".into());
        let ctx = OpContext { label: "base.apk".into(), source: "silent".into(), ..Default::default() };
        execute_with_context(&b, &plan_data, &InstallOptions::default(), &ctx).unwrap();
        let (_, a) = b.calls().into_iter().find(|(m, _)| m == "logAppend").unwrap();
        assert_eq!(a["appName"], "示例应用");
        assert_eq!(a["label"], "示例应用");
    }

    #[test]
    fn execute_logs_failure_when_not_authorized() {
        let b = MockBridge::new();
        b.push("brokerState", json!({"available":false,"permission":0}));
        for _ in 0..2 { authorized(&b); }
        let ctx = OpContext { label: "bg.apk".into(), source: "silent".into(), ..Default::default() };
        let r = execute_with_context(&b, &sample_plan(), &InstallOptions::default(), &ctx);
        assert!(r.is_err());
        let (_, a) = b.calls().into_iter().find(|(m, _)| m == "logAppend").unwrap();
        assert_eq!(a["ok"], false);
        assert_eq!(a["code"], "BROKER_NOT_READY");
        assert_eq!(a["source"], "silent");
        assert!(!a["detail"].as_str().unwrap().is_empty());
    }

    #[test]
    fn execute_falls_back_to_file_path_when_stdin_write_fails() {
        let b = MockBridge::new();
        for _ in 0..2 { authorized(&b); }
        b.push("exec", json!({"code":0,"stdout":"Success: created install session [9]","stderr":""}));
        // stdin 写入模拟 Java 侧异常：平台桥接直接返回 error 字段
        b.push("exec", json!({"error":"java.io.IOException: Broken pipe"}));
        b.push("stageForShell", json!({"path":"/sdcard/stage/base.apk"}));
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let plan_data = InstallPlan {
            parts: vec![SplitPart { name: "base.apk".into(), size: 1, stdin: StdinSource::Uri { uri: "u".into() } }],
            total_size: 1,
            create_args: vec!["pm".into(), "install-create".into()],
            package_hint: None, version_hint: None, label_hint: None, requires_confirmation: false,
        };
        let out = execute(&b, &plan_data, &InstallOptions::default()).unwrap();
        assert!(out.outcome.ok);
        let execs: Vec<_> = b.calls().into_iter().filter(|(m, _)| m == "exec").collect();
        // create / stdin 写入 / 文件路径重试 / commit
        assert_eq!(execs.len(), 4);
        let retry = &execs[2].1["argv"];
        assert!(retry.as_array().unwrap().last().unwrap().as_str() == Some("/sdcard/stage/base.apk"));
    }

    #[test]
    fn execute_abandons_on_write_failure() {
        let b = MockBridge::new();
        for _ in 0..2 { authorized(&b); }
        b.push("exec", json!({"code":0,"stdout":"Success: created install session [9]","stderr":""}));
        b.push("exec", json!({"code":1,"stdout":"","stderr":"write error"}));
        b.push("exec", json!({"code":0,"stdout":"Success","stderr":""}));
        let plan_data = InstallPlan {
            parts: vec![SplitPart { name: "base.apk".into(), size: 1, stdin: StdinSource::Uri { uri: "u".into() } }],
            total_size: 1,
            create_args: vec!["pm".into(), "install-create".into()],
            package_hint: None, version_hint: None, label_hint: None, requires_confirmation: false,
        };
        let e = execute(&b, &plan_data, &InstallOptions::default()).unwrap_err();
        assert!(matches!(e, CoreError::Write(_)));
        let execs: Vec<_> = b.calls().into_iter().filter(|(m, _)| m == "exec").collect();
        assert!(execs.iter().any(|(_, a)| a["argv"].as_array().unwrap().contains(&json!("install-abandon"))));
    }
}
