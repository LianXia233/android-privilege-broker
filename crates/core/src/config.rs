use crate::error::{CoreError, Result};
use crate::model::Config;
use crate::util::is_valid_package_name;

pub fn allow_list_add(cfg: &mut Config, pkg: &str) -> Result<()> {
    let p = pkg.trim();
    if !is_valid_package_name(p) {
        return Err(CoreError::InvalidArgument(format!("非法包名: {p}")));
    }
    if !cfg.allow_list.iter().any(|x| x == p) {
        cfg.allow_list.push(p.to_string());
    }
    Ok(())
}

pub fn allow_list_remove(cfg: &mut Config, pkg: &str) {
    cfg.allow_list.retain(|x| x != pkg.trim());
}

pub fn allow_list_clear(cfg: &mut Config) {
    cfg.allow_list.clear();
}

/// 是否需要展示安装前确认。
pub fn needs_confirmation(cfg: &Config, package_hint: Option<&str>, skip_confirm: bool) -> bool {
    if skip_confirm || !cfg.confirm_before_install {
        return false;
    }
    match package_hint {
        Some(p) if cfg.allow_list.iter().any(|x| x == p) => false,
        _ => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn list_is_deduplicated_and_validated() {
        let mut c = Config::default();
        allow_list_add(&mut c, "dev.example.app").unwrap();
        allow_list_add(&mut c, "dev.example.app").unwrap();
        assert_eq!(c.allow_list, vec!["dev.example.app"]);
        assert!(allow_list_add(&mut c, "bad;echo hi").is_err());
        assert!(allow_list_add(&mut c, "single").is_err());
    }

    #[test]
    fn default_is_silent() {
        let c = Config::default();
        assert!(!c.confirm_before_install);
        assert!(!needs_confirmation(&c, Some("dev.example.app"), false));
    }

    #[test]
    fn confirmation_rules_when_opted_in() {
        let mut c = Config::default();
        c.confirm_before_install = true;
        assert!(needs_confirmation(&c, Some("dev.example.app"), false));
        assert!(!needs_confirmation(&c, Some("dev.example.app"), true));
        allow_list_add(&mut c, "dev.example.app").unwrap();
        assert!(!needs_confirmation(&c, Some("dev.example.app"), false));
        assert!(needs_confirmation(&c, Some("dev.other.app"), false));
        c.confirm_before_install = false;
        assert!(!needs_confirmation(&c, None, false));
    }
}
