//! 特权代理 静默安装/卸载的业务逻辑核心。
//!
//! 平台无关：`PlatformBridge` 是唯一的系统边界，host 侧可用 `mock::MockBridge` 完整测试。
//! Android 侧由 `crates/android-ffi` 通过 JNI 转发到极薄的 Java 胶水层。

pub mod api;
pub mod apk_meta;
pub mod archive;
pub mod auth;
pub mod config;
pub mod error;
pub mod install;
pub mod logbook;
pub mod mock;
pub mod model;
pub mod parse;
pub mod plan;
pub mod platform;
pub mod state;
pub mod uninstall;
pub mod util;

pub use api::Core;
pub use error::{CoreError, Result};
pub use platform::{ExecResult, PlatformBridge, StdinSource};
