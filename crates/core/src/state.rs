use serde::Serialize;

use crate::model::{BrokerStateRaw, BrokerStatus};

pub fn classify(raw: &BrokerStateRaw) -> BrokerStatus {
    if !raw.available {
        BrokerStatus::Unavailable
    } else {
        match raw.permission {
            1 => BrokerStatus::Authorized,
            0 => BrokerStatus::Denied,
            _ => BrokerStatus::NeedPermission,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Guidance {
    pub status: BrokerStatus,
    pub title: String,
    pub action: String,
    pub hint: String,
}

/// 状态对应的下一步引导。只提供 ADB Shell 一条启动路径，不提示 root。
pub fn guidance(status: BrokerStatus) -> Guidance {
    let (title, action, hint) = match status {
        BrokerStatus::Unavailable => (
            "特权代理服务未运行",
            "查看启用步骤",
            "请在应用内点「启用无线调试」，按引导打开开发者选项的无线调试，并在通知栏输入 6 位配对码完成配对；完成后返回刷新状态。",
        ),
        BrokerStatus::NeedPermission => (
            "尚未完成配对",
            "查看启用步骤",
            "请在通知栏重新输入 6 位配对码；配对公钥与令牌仅保存在本机。",
        ),
        BrokerStatus::Denied => (
            "连接被拒绝",
            "查看启用步骤",
            "请确认无线调试仍处于开启状态，并在应用内重新完成一次配对。",
        ),
        BrokerStatus::Authorized => (
            "特权代理已就绪",
            "",
            "现在可以静默安装 APK 或卸载应用。",
        ),
    };
    Guidance {
        status,
        title: title.into(),
        action: action.into(),
        hint: hint.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_all_states() {
        assert_eq!(classify(&BrokerStateRaw { available: false, permission: 1, ..Default::default() }), BrokerStatus::Unavailable);
        assert_eq!(classify(&BrokerStateRaw { available: true, permission: -1, ..Default::default() }), BrokerStatus::NeedPermission);
        assert_eq!(classify(&BrokerStateRaw { available: true, permission: 0, ..Default::default() }), BrokerStatus::Denied);
        assert_eq!(classify(&BrokerStateRaw { available: true, permission: 1, ..Default::default() }), BrokerStatus::Authorized);
    }

    #[test]
    fn authorized_has_no_pending_action() {
        assert_eq!(guidance(BrokerStatus::Authorized).action, "");
        assert_ne!(guidance(BrokerStatus::NeedPermission).action, "");
    }

    #[test]
    fn guidance_never_mentions_root() {
        for s in [BrokerStatus::Unavailable, BrokerStatus::NeedPermission,
                  BrokerStatus::Denied, BrokerStatus::Authorized] {
            let g = guidance(s);
            assert!(!g.title.contains("root") && !g.hint.contains("root"), "不得提示 root 后端");
            assert!(!g.title.contains("特权代理 "), "文案不应含机械替换残留");
        }
    }

    #[test]
    fn unavailable_points_at_in_app_pairing() {
        let g = guidance(BrokerStatus::Unavailable);
        assert!(g.hint.contains("无线调试"));
        assert!(g.hint.contains("通知栏"));
        assert!(!g.hint.contains("adb"), "不应再引导用户依赖电脑 adb");
        assert!(!g.hint.contains("电脑"));
    }
}
