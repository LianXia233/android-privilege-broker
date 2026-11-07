//! 无线调试启用链路的协议实现。
//!
//! 从 mDNS 拿到端口之后的所有字节级工作都在此完成：TLS 握手与导出密钥、
//! SPAKE2 配对注册、ADB TLS 通道与 `shell:` 启动。Java 层只负责服务发现、
//! 通知栏配对码输入与结果展示。

pub mod adb;
pub mod crypto;
pub mod error;
pub mod key;
pub mod orchestrate;
pub mod pairing;
pub mod spake2;
pub mod tls;

pub use error::WirelessError;
pub use orchestrate::run;
