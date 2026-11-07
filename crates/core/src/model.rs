use serde::{Deserialize, Serialize};

use crate::platform::StdinSource;

/// 特权代理服务状态（来自平台桥接的原始观测值）。
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrokerStateRaw {
    /// 特权代理服务是否可用（binder 已连接）。
    pub available: bool,
    /// -1 未知 / 0 拒绝 / 1 已授权。
    pub permission: i32,
    /// 特权代理服务端版本，未知为 0。
    pub server_version: i32,
    /// 服务端实测 uid（ADB Shell 应为 2000），未知为 -1。
    pub server_uid: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BrokerStatus {
    Unavailable,
    NeedPermission,
    Denied,
    Authorized,
}

impl BrokerStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            BrokerStatus::Unavailable => "UNAVAILABLE",
            BrokerStatus::NeedPermission => "NEED_PERMISSION",
            BrokerStatus::Denied => "DENIED",
            BrokerStatus::Authorized => "AUTHORIZED",
        }
    }
}

/// 一个待安装输入源的元数据（由 SAF 提供，纯 I/O 观测）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceDescriptor {
    pub uri: String,
    pub display_name: String,
    pub size: u64,
    /// apk / apks / xapk / zip / unknown
    pub kind: String,
}

/// 归档内条目（纯枚举结果，不含业务判断）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArchiveEntry {
    pub name: String,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SplitPart {
    /// 传给 `pm install-write` 的 split 名。
    pub name: String,
    pub size: u64,
    pub stdin: StdinSource,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallOptions {
    /// -r 覆盖安装
    pub replace: bool,
    /// -g 授予清单声明的运行时权限
    pub grant_runtime_permissions: bool,
    /// -d 允许降级（默认关闭）
    pub allow_downgrade: bool,
    pub user_id: Option<i32>,
    pub installer_package: Option<String>,
    /// 成功后尝试删除安装包（仅当来源可删除）
    pub delete_source_after: bool,
    /// 跳过安装前确认（由 UI 在用户确认后置 true）
    pub skip_confirm: bool,
}

impl Default for InstallOptions {
    fn default() -> Self {
        Self {
            replace: true,
            grant_runtime_permissions: true,
            allow_downgrade: false,
            user_id: None,
            installer_package: None,
            delete_source_after: false,
            skip_confirm: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallPlan {
    pub parts: Vec<SplitPart>,
    pub total_size: u64,
    pub create_args: Vec<String>,
    pub package_hint: Option<String>,
    pub version_hint: Option<String>,
    /// best-effort 从 binary manifest 的 application@label 读到的应用名。
    #[serde(default)]
    pub label_hint: Option<String>,
    pub requires_confirmation: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UninstallOptions {
    /// -k 保留数据与缓存
    pub keep_data: bool,
    pub user_id: Option<i32>,
}

impl Default for UninstallOptions {
    fn default() -> Self {
        Self { keep_data: false, user_id: None }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OpOutcome {
    pub ok: bool,
    /// SUCCESS / INSTALL_FAILED_* / DELETE_FAILED_* / SHELL_ERROR / ...
    pub code: String,
    pub message: String,
    /// 未裁剪的平台原始输出，供「复制错误」使用。
    pub raw: String,
}

impl OpOutcome {
    pub fn success(raw: impl Into<String>) -> Self {
        Self { ok: true, code: "SUCCESS".into(), message: "Success".into(), raw: raw.into() }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    pub stage: String,
    pub index: usize,
    pub total: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Config {
    pub confirm_before_install: bool,
    pub delete_apk_after_install: bool,
    pub allow_list: Vec<String>,
    /// 调用方授权总开关：开启后，未被允许的来源不能「直接」静默安装。
    pub caller_auth_enabled: bool,
    /// 已获永久授权的调用方包名。
    pub allowed_callers: Vec<String>,
    /// 已被明确拒绝的调用方包名。
    pub denied_callers: Vec<String>,
    /// 静默安装失败后提供「改用系统安装器」的通知入口。
    pub fallback_to_system_installer: bool,
    /// 失败时是否自动拉起系统安装器界面。
    /// 默认关闭：自动 startActivity 会弹出一个可聚焦窗口，遮挡前台应用、
    /// 染黑系统栏并要求用户按返回才能继续，与「静默」语义冲突。
    /// 用户主动开启时才自动拉起，否则只在通知里提供点击入口。
    pub auto_open_system_installer: bool,
    /// 安装期间显示左上角悬浮进度指示。
    pub floating_indicator: bool,
}

impl Default for Config {
    fn default() -> Self {
        // 静默安装语义：默认不要求任何安装前确认；但外部调用方必须先授权。
        Self {
            confirm_before_install: false,
            delete_apk_after_install: false,
            allow_list: Vec::new(),
            caller_auth_enabled: true,
            allowed_callers: Vec::new(),
            denied_callers: Vec::new(),
            fallback_to_system_installer: true,
            auto_open_system_installer: false,
            floating_indicator: true,
        }
    }
}

/// 应用配置持久化的抽象；Android 侧由 Java SharedPreferences 桥接实现。
pub trait ConfigStore: Send + Sync {
    fn load(&self) -> Result<Config, crate::error::CoreError>;
    fn save(&self, cfg: &Config) -> Result<(), crate::error::CoreError>;
}
