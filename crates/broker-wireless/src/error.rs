//! 无线调试链路的统一错误模型。所有错误都以稳定 code 返回给 Java 层，
//! 由通知栏与引导页给出可操作的提示，不吞异常、不猜测。

use thiserror::Error;

#[derive(Debug, Error)]
pub enum WirelessError {
    #[error("连接 I/O 失败: {0}")]
    Io(#[from] std::io::Error),

    #[error("TLS 失败: {0}")]
    Tls(#[from] rustls::Error),

    #[error("协议错误: {0}")]
    Protocol(String),

    #[error("配对码不正确或密钥交换被拒绝")]
    BadPairingCode,

    #[error("密码学运算失败: {0}")]
    Crypto(String),

    #[error("密钥错误: {0}")]
    Key(String),
}

impl WirelessError {
    /// 稳定的机器可读错误码，Java 侧据此选择文案。
    pub fn code(&self) -> &'static str {
        match self {
            WirelessError::Io(_) => "IO",
            WirelessError::Tls(_) => "TLS",
            WirelessError::Protocol(_) => "PROTOCOL",
            WirelessError::BadPairingCode => "BAD_PAIRING_CODE",
            WirelessError::Crypto(_) => "CRYPTO",
            WirelessError::Key(_) => "KEY",
        }
    }
}

impl From<String> for WirelessError {
    fn from(s: String) -> Self {
        WirelessError::Protocol(s)
    }
}
