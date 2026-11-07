use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum CoreError {
    #[error("特权代理未就绪: {0}")]
    BrokerNotReady(String),

    #[error("平台桥接调用失败: {0}")]
    Bridge(String),

    #[error("会话创建失败: {0}")]
    Session(String),

    #[error("写入安装会话失败: {0}")]
    Write(String),

    #[error("归档无效: {0}")]
    Archive(String),

    #[error("参数无效: {0}")]
    InvalidArgument(String),

    #[error("操作被拒绝: {0}")]
    Rejected(String),

    #[error("JSON 契约错误: {0}")]
    Json(String),
}

impl CoreError {
    /// 稳定的机器可读错误码，用于日志与 JSON 门面。
    pub fn kind(&self) -> &'static str {
        match self {
            CoreError::BrokerNotReady(_) => "BROKER_NOT_READY",
            CoreError::Bridge(_) => "BRIDGE",
            CoreError::Session(_) => "SESSION",
            CoreError::Write(_) => "WRITE",
            CoreError::Archive(_) => "ARCHIVE",
            CoreError::InvalidArgument(_) => "INVALID_ARGUMENT",
            CoreError::Rejected(_) => "REJECTED",
            CoreError::Json(_) => "JSON",
        }
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::Json(e.to_string())
    }
}
