//! JNI 边界。Java 胶水层只保留一个 native 方法：
//! `RustCore.dispatch(method, argsJson) -> resultJson`
//!
//! Rust 通过 `BrokerBridge.call(method, argsJson)` 回调 Java，
//! 由 Java 完成 特权代理接口 / ContentResolver / Process 的无逻辑 I/O。

use std::cell::RefCell;

use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::Value;
use installer_core::error::{CoreError, Result};
use installer_core::platform::PlatformBridge;
use installer_core::Core;

const BRIDGE_CLASS: &str = "io/github/lianxia233/privbroker/BrokerBridge";
const BRIDGE_SIG: &str =
    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

struct JniBridge<'a, 'local> {
    env: RefCell<&'a mut JNIEnv<'local>>,
}

impl<'a, 'local> JniBridge<'a, 'local> {
    fn new(env: &'a mut JNIEnv<'local>) -> Self {
        Self { env: RefCell::new(env) }
    }
}

fn jni_err(e: impl std::fmt::Display) -> CoreError {
    CoreError::Bridge(format!("JNI: {e}"))
}

impl PlatformBridge for JniBridge<'_, '_> {
    fn call(&self, method: &str, args: &Value) -> Result<Value> {
        let mut binding = self.env.borrow_mut();
        let env: &mut JNIEnv = &mut **binding;

        let jm = env.new_string(method).map_err(jni_err)?;
        let ja = env.new_string(args.to_string()).map_err(jni_err)?;
        let om: &JObject = jm.as_ref();
        let oa: &JObject = ja.as_ref();
        let ret = env
            .call_static_method(
                BRIDGE_CLASS,
                "call",
                BRIDGE_SIG,
                &[JValue::Object(om), JValue::Object(oa)],
            )
            .map_err(jni_err)?;
        let obj = ret.l().map_err(jni_err)?;
        let js = JString::from(obj);
        let text: String = env.get_string(&js).map_err(jni_err)?.into();
        let v: Value = serde_json::from_str(&text).map_err(|e| {
            CoreError::Bridge(format!("桥接返回非法 JSON: {e}; raw={text}"))
        })?;
        // Java 胶水层把异常编码成 {"error": "..."}。若不在此拦截，上层会把
        // 缺失的 code 当成 -1、把缺失的 stdout/stderr 当成空串，真实故障
        // （URI 读权限失效、broken pipe、特权代理连接 断开等）会被伪装成
        // "写入 xxx 失败 (exit -1)" 而无从诊断。
        if let Some(msg) = v.get("error").and_then(Value::as_str) {
            return Err(CoreError::Bridge(format!("平台调用 {method} 失败: {msg}")));
        }
        Ok(v)
    }
}

/// `RustCore.dispatch(String method, String argsJson): String`
#[no_mangle]
pub extern "system" fn Java_io_github_lianxia233_privbroker_RustCore_dispatch<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    method: JString<'local>,
    args: JString<'local>,
) -> jstring {
    let m: String = env.get_string(&method).map(|x| x.into()).unwrap_or_default();
    let a: String = env.get_string(&args).map(|x| x.into()).unwrap_or_default();

    let bridge = JniBridge::new(&mut env);
    let core = Core::new(bridge);
    let out = core.dispatch(&m, &a);

    match env.new_string(out) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `WirelessCore.run(String argsJson): String`
///
/// 在应用进程中完成无线调试配对、ADB TLS 连接与特权进程启动，返回 JSON 结果。
#[no_mangle]
pub extern "system" fn Java_io_github_lianxia233_privbroker_WirelessCore_run<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    args: JString<'local>,
) -> jstring {
    let a: String = env.get_string(&args).map(|x| x.into()).unwrap_or_default();
    let out = broker_wireless::run(&a);
    match env.new_string(out) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `WirelessCore.startBroker(String argsJson): void`
///
/// 由 `app_process` 以 shell 身份加载并调用；此调用阻塞直到服务停止，
/// 从而让特权进程保持常驻。uid 校验在运行时内部完成，不接受任何绕过。
#[no_mangle]
pub extern "system" fn Java_io_github_lianxia233_privbroker_WirelessCore_startBroker<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    args: JString<'local>,
) {
    let a: String = env.get_string(&args).map(|x| x.into()).unwrap_or_default();
    let v: Value = serde_json::from_str(&a).unwrap_or(Value::Null);
    let text = |key: &str| -> String {
        v.get(key).and_then(Value::as_str).unwrap_or("").to_string()
    };
    let client = text("client");
    let mut allowed_clients: Vec<String> = client
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    if allowed_clients.is_empty() {
        allowed_clients.push("installer".to_string());
    }
    let endpoint = text("endpoint");
    let cfg = broker_android::StartupConfig {
        token: text("token"),
        allowed_clients,
        endpoint: if endpoint.is_empty() { "abstract".to_string() } else { endpoint },
        session_ttl_ms: v.get("sessionTtlMs").and_then(Value::as_u64).unwrap_or(60_000),
        require_shell_uid: true,
    };
    let code = broker_server::run(cfg);
    if code != 0 {
        eprintln!("特权代理异常退出，代码 {code}");
    }
}
