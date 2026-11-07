//! 调用方授权：决定「某个来源能否直接发起静默安装」。
//!
//! 平台侧（Java）负责识别调用方并弹窗，本模块只做纯策略判断，
//! 因此可以在 host 侧完整单测，且不带任何 I/O。

use serde::{Deserialize, Serialize};

use crate::error::{CoreError, Result};
use crate::model::Config;
use crate::util::is_valid_package_name;

/// 本应用内部发起（MainActivity），无需外部授权。
pub const CALLER_SELF: &str = "@self";
/// 无法识别来源（隐式 intent 常常拿不到 referrer），一律走询问，不能放行。
pub const CALLER_UNKNOWN: &str = "@unknown";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Decision {
    /// 已授权，直接静默执行。
    Allow,
    /// 首次调用，需要弹窗询问。
    Ask,
    /// 已被明确拒绝。
    Deny,
}

impl Decision {
    pub fn as_str(self) -> &'static str {
        match self {
            Decision::Allow => "ALLOW",
            Decision::Ask => "ASK",
            Decision::Deny => "DENY",
        }
    }
}

/// 归一化调用方标识：空白视为未知，非法包名也视为未知（防止伪造字符串污染配置）。
pub fn normalize(caller: &str) -> String {
    let c = caller.trim();
    if c.is_empty() || c == CALLER_SELF || c == CALLER_UNKNOWN {
        return if c == CALLER_SELF { CALLER_SELF.into() } else { CALLER_UNKNOWN.into() };
    }
    if is_valid_package_name(c) {
        c.to_string()
    } else {
        CALLER_UNKNOWN.into()
    }
}

/// 核心授权决策。
/// 白名单/黑名单优先，其次才是「首次询问」。
///
/// 安全不变量：**未知来源（拿不到 referrer）永远不能被永久记住**。
/// 否则一次「允许」会连带放行后续所有匿名调用方，授权就形同虚设，
/// 因此 `@unknown` 在开启授权时一律返回 Ask，白/黑名单里的残留也忽略。
pub fn decide(cfg: &Config, caller: &str) -> Decision {
    let c = normalize(caller);
    // 应用内主动发起永远放行，不受外部授权开关影响。
    if c == CALLER_SELF {
        return Decision::Allow;
    }
    if !cfg.caller_auth_enabled {
        return Decision::Allow;
    }
    if c == CALLER_UNKNOWN {
        return Decision::Ask;
    }
    if cfg.allowed_callers.iter().any(|x| x == &c) {
        return Decision::Allow;
    }
    if cfg.denied_callers.iter().any(|x| x == &c) {
        return Decision::Deny;
    }
    Decision::Ask
}

/// 用户同意。`remember=true` 时写入永久白名单；一次性同意不落库。
pub fn grant(cfg: &mut Config, caller: &str, remember: bool) -> Result<()> {
    let c = normalize(caller);
    if c == CALLER_SELF || c == CALLER_UNKNOWN || !remember {
        return Ok(());
    }
    cfg.denied_callers.retain(|x| x != &c);
    if !cfg.allowed_callers.iter().any(|x| x == &c) {
        cfg.allowed_callers.push(c);
    }
    Ok(())
}

/// 用户拒绝。`remember=true` 时写入黑名单，之后不再弹窗。
pub fn deny(cfg: &mut Config, caller: &str, remember: bool) -> Result<()> {
    let c = normalize(caller);
    if c == CALLER_SELF || c == CALLER_UNKNOWN || !remember {
        return Ok(());
    }
    cfg.allowed_callers.retain(|x| x != &c);
    if !cfg.denied_callers.iter().any(|x| x == &c) {
        cfg.denied_callers.push(c);
    }
    Ok(())
}

/// 设置页开关：直接指定某个来源是否允许发起静默安装。
/// 关闭即视为「明确不允许」，避免每次都弹窗打扰。
/// 只接受合法包名：设置页的应用列表由系统提供，不接受手写或匿名标识。
pub fn set_caller(cfg: &mut Config, caller: &str, allowed: bool) -> Result<()> {
    let c = normalize(caller);
    if c == CALLER_SELF || c == CALLER_UNKNOWN || !is_valid_package_name(&c) {
        return Err(CoreError::InvalidArgument("非法调用方包名".into()));
    }
    cfg.allowed_callers.retain(|x| x != &c);
    cfg.denied_callers.retain(|x| x != &c);
    if allowed {
        cfg.allowed_callers.push(c.clone());
    } else {
        cfg.denied_callers.push(c);
    }
    Ok(())
}

pub fn clear_all(cfg: &mut Config) {
    cfg.allowed_callers.clear();
    cfg.denied_callers.clear();
}

/// UI 渲染开关状态用。
pub fn is_allowed(cfg: &Config, caller: &str) -> bool {
    let c = normalize(caller);
    cfg.allowed_callers.iter().any(|x| x == &c)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn internal_caller_always_allowed() {
        let mut c = Config::default();
        assert!(c.caller_auth_enabled);
        c.caller_auth_enabled = true;
        assert_eq!(decide(&c, CALLER_SELF), Decision::Allow);
        assert_eq!(decide(&c, "com.example.files"), Decision::Ask);
    }

    #[test]
    fn unknown_caller_must_ask_even_when_not_denied() {
        let c = Config::default();
        assert_eq!(decide(&c, ""), Decision::Ask);
        assert_eq!(decide(&c, CALLER_UNKNOWN), Decision::Ask);
        assert_eq!(decide(&c, "not;a;package"), Decision::Ask);
    }

    #[test]
    fn grant_and_deny_are_exclusive_and_persistent() {
        let mut c = Config::default();
        grant(&mut c, "com.example.files", true).unwrap();
        assert_eq!(decide(&c, "com.example.files"), Decision::Allow);
        deny(&mut c, "com.example.files", true).unwrap();
        assert_eq!(decide(&c, "com.example.files"), Decision::Deny);
        assert!(!c.allowed_callers.contains(&"com.example.files".to_string()));
        grant(&mut c, "com.example.files", true).unwrap();
        assert_eq!(c.denied_callers.len(), 0);
        assert_eq!(c.allowed_callers.len(), 1);
    }

    #[test]
    fn once_only_does_not_persist() {
        let mut c = Config::default();
        grant(&mut c, "com.example.browser", false).unwrap();
        assert!(c.allowed_callers.is_empty());
        assert_eq!(decide(&c, "com.example.browser"), Decision::Ask);
    }

    #[test]
    fn unknown_cannot_be_remembered() {
        let mut c = Config::default();
        grant(&mut c, CALLER_UNKNOWN, true).unwrap();
        deny(&mut c, "", true).unwrap();
        assert!(c.allowed_callers.is_empty());
        assert!(c.denied_callers.is_empty());
    }

    #[test]
    fn stale_unknown_whitelist_cannot_bypass_asking() {
        // 模拟旧配置里误存的 @unknown / 匿名字符串：必须仍然每次询问。
        let mut c = Config::default();
        c.allowed_callers.push(CALLER_UNKNOWN.into());
        assert_eq!(decide(&c, CALLER_UNKNOWN), Decision::Ask);
        assert_eq!(decide(&c, ""), Decision::Ask);
        assert_eq!(decide(&c, "not;a;package"), Decision::Ask);
        // 但已识别的合法包名不受影响。
        set_caller(&mut c, "com.example.files", true).unwrap();
        assert_eq!(decide(&c, "com.example.files"), Decision::Allow);
    }

    #[test]
    fn disabling_auth_allows_everything() {
        let mut c = Config::default();
        c.caller_auth_enabled = false;
        assert_eq!(decide(&c, "com.anything.app"), Decision::Allow);
        assert_eq!(decide(&c, CALLER_UNKNOWN), Decision::Allow);
    }

    #[test]
    fn settings_switch_roundtrip() {
        let mut c = Config::default();
        set_caller(&mut c, "com.example.files", true).unwrap();
        assert!(is_allowed(&c, "com.example.files"));
        assert_eq!(decide(&c, "com.example.files"), Decision::Allow);
        set_caller(&mut c, "com.example.files", false).unwrap();
        assert_eq!(decide(&c, "com.example.files"), Decision::Deny);
        assert!(set_caller(&mut c, "bad pkg", true).is_err());
        clear_all(&mut c);
        assert!(c.allowed_callers.is_empty() && c.denied_callers.is_empty());
    }
}
