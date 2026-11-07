//! 特权代理的自主通信协议层。
//!
//! 该 crate 只定义数据结构、帧格式、错误码、认证与会话语义，不包含任何
//! 平台 I/O、进程创建或 socket 监听；传输与执行由上级模块实现。
//! 这样协议可以脱离 Android 设备在 host 侧完整单元测试。

pub mod auth;
pub mod error;
pub mod frame;
pub mod message;
pub mod session;

pub use auth::{AuthConfig, Authenticator};
pub use error::ErrorCode;
pub use frame::{decode, encode, FrameReader};
pub use message::{
    BackendKind, Body, ClientIdentity, Envelope, ResponseStatus, StdinSpec, StreamKind,
    MAX_FRAME_BYTES, PROTOCOL_VERSION,
};
pub use session::{Session, SessionTable};

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const TOKEN: &str = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";

    #[test]
    fn frame_carries_every_required_protocol_field() {
        let e = Envelope::new(
            101,
            202,
            "client-a",
            303,
            1_000,
            6_000,
            Body::Exec {
                argv: vec!["pm".into(), "install-create".into(), "-r".into()],
                stdin: StdinSpec::Uri {
                    uri: "content://pkg/base.apk".into(),
                },
                env: vec![("MODE".into(), "silent".into())],
                timeout_ms: 30_000,
                cancel_group: 9,
            },
        );
        let frame = encode(&e).unwrap();
        let v: serde_json::Value = serde_json::from_slice(&frame[4..]).unwrap();

        assert_eq!(v["version"], json!(PROTOCOL_VERSION)); // 协议版本
        assert_eq!(v["requestId"], json!(101)); // 请求 ID
        assert_eq!(v["clientId"], json!("client-a")); // 客户端身份
        assert_eq!(v["sessionId"], json!(202)); // 会话标识
        assert_eq!(v["nonce"], json!(303)); // 防重放
        assert_eq!(v["issuedAtMs"], json!(1_000)); // 时间戳
        assert_eq!(v["deadlineMs"], json!(6_000)); // 超时控制
        assert_eq!(v["body"]["type"], json!("exec")); // 请求类型
        assert_eq!(v["body"]["argv"][0], json!("pm")); // 参数（数组）
        assert_eq!(v["body"]["timeoutMs"], json!(30_000));

        let rep = e.reply(
            ResponseStatus::Completed,
            ErrorCode::Ok,
            "完成",
            json!({"code": 0}),
            1_500,
            6_000,
        );
        let rv: serde_json::Value =
            serde_json::from_slice(&encode(&rep).unwrap()[4..]).unwrap();
        assert_eq!(rv["body"]["status"], json!("COMPLETED")); // 响应状态
        assert_eq!(rv["body"]["code"], json!("OK")); // 错误码
    }

    #[test]
    fn full_flow_hello_exec_revoke() {
        let mut auth = Authenticator::new(AuthConfig::new(TOKEN, &["client-a"]));
        let mut sessions = SessionTable::new(60_000);

        // 1) 认证
        let hello = Envelope::new(
            1,
            0,
            "client-a",
            1,
            1_000,
            5_000,
            Body::Hello {
                token: TOKEN.into(),
                advertised_version: PROTOCOL_VERSION,
            },
        );
        let id = auth.verify(&hello, 1_000).unwrap();
        assert_eq!(id.client_id, "client-a");

        // 2) 服务端实测 uid 后建立会话
        let sid = sessions.open(&id.client_id, 2000, BackendKind::AdbShell, 1_000);
        assert_eq!(sessions.get(sid, "client-a", 1_000).unwrap().uid, 2000);

        // 3) 执行命令
        let exec = Envelope::new(
            2,
            sid,
            "client-a",
            2,
            1_100,
            5_000,
            Body::Exec {
                argv: vec!["pm".into(), "install-commit".into(), "42".into()],
                stdin: StdinSpec::None,
                env: vec![],
                timeout_ms: 20_000,
                cancel_group: 1,
            },
        );
        assert!(auth.verify(&exec, 1_100).is_ok());
        assert!(exec.validate_exec().is_ok());
        assert!(sessions.touch(sid, "client-a", 1_100).is_ok());
        assert!(sessions
            .attach_handle(sid, "client-a", 501, 1_100)
            .is_ok());

        // 4) 授权撤销：会话失效并回收句柄
        let handles = sessions.revoke(sid).unwrap();
        assert_eq!(handles, vec![501]);
        assert_eq!(
            sessions.get(sid, "client-a", 1_200),
            Err(ErrorCode::SessionRevoked)
        );
        let later = Envelope::new(
            3,
            sid,
            "client-a",
            3,
            1_200,
            5_000,
            Body::StatusQuery {},
        );
        // 认证层仍可通过（客户端未被撤销），但会话层必须拒绝
        assert!(auth.verify(&later, 1_200).is_ok());
        assert_eq!(
            sessions.touch(sid, "client-a", 1_200),
            Err(ErrorCode::SessionRevoked)
        );
    }
}
