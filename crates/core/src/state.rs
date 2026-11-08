use serde::Serialize;

use crate::model::{BrokerStateRaw, BrokerStatus};

pub fn classify(raw: &BrokerStateRaw) -> BrokerStatus {
    if !raw.available {
        // 端点可连但握手被拒，说明代理进程在跑、只是不接受当前应用的令牌。
        // 这与「没运行」的处置方式不同（需要重启代理而非重新配对），
        // 不能都折叠成 UNAVAILABLE。
        if raw.reachable && is_auth_rejection(&raw.error) {
            BrokerStatus::HandshakeRejected
        } else {
            BrokerStatus::Unavailable
        }
    } else {
        match raw.permission {
            1 => BrokerStatus::Authorized,
            0 => BrokerStatus::Denied,
            _ => BrokerStatus::NeedPermission,
        }
    }
}

/// 平台侧错误串里是否带鉴权被拒的特征。
fn is_auth_rejection(error: &str) -> bool {
    error.contains("UNAUTHENTICATED")
        || error.contains("SESSION_EXPIRED")
        || error.contains("REPLAY_DETECTED")
        || error.contains("握手失败")
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
        BrokerStatus::HandshakeRejected => (
            "特权代理握手被拒绝",
            "查看启用步骤",
            "代理进程在运行，但不接受当前应用的令牌（常见于应用数据被清理、覆盖安装或旧版本实例残留）。请点「查看启用步骤」重新激活，让代理以当前令牌重启；若仍失败，可重启手机后再试。",
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
    fn reachable_but_rejected_handshake_is_not_reported_as_not_running() {
        // 现场日志：端点 tcp:127.0.0.1:31473 能连上，但握手返回
        // REJECTED / UNAUTHENTICATED。这种形态不能再报「服务未运行」。
        let raw = BrokerStateRaw {
            available: false,
            reachable: true,
            error: "java.lang.IllegalStateException: 握手失败: {\"body\":{\"code\":\"UNAUTHENTICATED\"}}"
                .into(),
            ..Default::default()
        };
        assert_eq!(classify(&raw), BrokerStatus::HandshakeRejected);
        assert_eq!(guidance(classify(&raw)).title, "特权代理握手被拒绝");
    }

    #[test]
    fn nonce_precision_parse_failure_is_reported_as_handshake_rejected() {
        // 旧版代理仍在运行、下发 64 位随机数：客户端主动中止握手并带上
        // 「握手失败」前缀。这种形态是「进程在跑但版本不兼容」，报「服务
        // 未运行」会把用户引向重新配对，而正确处置是重新激活以更换代理。
        let raw = BrokerStateRaw {
            available: false,
            reachable: true,
            error: "java.lang.IllegalStateException: 无法连接特权代理 (tcp:127.0.0.1:31473): \
                    java.lang.IllegalStateException: 握手失败: 挑战随机数超出 JSON 精确整数范围\
                    （serverNonce=1.0621E19）：特权代理随机数未限制在 53 位内，请重新激活以更新代理"
                .into(),
            ..Default::default()
        };
        assert_eq!(classify(&raw), BrokerStatus::HandshakeRejected);
        assert_eq!(guidance(classify(&raw)).title, "特权代理握手被拒绝");
    }

    #[test]
    fn unreachable_endpoint_stays_unavailable() {
        let raw = BrokerStateRaw {
            available: false,
            reachable: false,
            error: "java.net.ConnectException: Connection refused".into(),
            ..Default::default()
        };
        assert_eq!(classify(&raw), BrokerStatus::Unavailable);
    }

    #[test]
    fn authorized_has_no_pending_action() {
        assert_eq!(guidance(BrokerStatus::Authorized).action, "");
        assert_ne!(guidance(BrokerStatus::NeedPermission).action, "");
    }

    #[test]
    fn guidance_never_mentions_root() {
        for s in [BrokerStatus::Unavailable, BrokerStatus::HandshakeRejected,
                  BrokerStatus::NeedPermission, BrokerStatus::Denied,
                  BrokerStatus::Authorized] {
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
