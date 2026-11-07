//! 可在 host 侧完整模拟 Android 平台桥接，用于 `cargo test`。

use std::collections::VecDeque;
use std::sync::Mutex;

use serde_json::{json, Value};

use crate::error::Result;
use crate::platform::PlatformBridge;

pub struct MockBridge {
    calls: Mutex<Vec<(String, Value)>>,
    responses: Mutex<VecDeque<(String, Value)>>,
    default: Mutex<Value>,
}

impl MockBridge {
    pub fn new() -> Self {
        Self {
            calls: Mutex::new(Vec::new()),
            responses: Mutex::new(VecDeque::new()),
            default: Mutex::new(json!({})),
        }
    }

    /// 预设一次按方法名匹配的响应（FIFO，同名按加入顺序消费）。
    pub fn push(&self, method: &str, value: Value) {
        self.responses.lock().unwrap().push_back((method.to_string(), value));
    }

    /// 未预设方法时的兜底响应。
    pub fn set_default(&self, value: Value) {
        *self.default.lock().unwrap() = value;
    }

    pub fn calls(&self) -> Vec<(String, Value)> {
        self.calls.lock().unwrap().clone()
    }

    pub fn call_count(&self, method: &str) -> usize {
        self.calls.lock().unwrap().iter().filter(|(m, _)| m == method).count()
    }
}

impl Default for MockBridge {
    fn default() -> Self {
        Self::new()
    }
}

impl PlatformBridge for MockBridge {
    fn call(&self, method: &str, args: &Value) -> Result<Value> {
        self.calls.lock().unwrap().push((method.to_string(), args.clone()));
        let mut q = self.responses.lock().unwrap();
        if let Some(pos) = q.iter().position(|(m, _)| m == method) {
            return Ok(q.remove(pos).map(|(_, v)| v).unwrap_or(json!({})));
        }
        Ok(self.default.lock().unwrap().clone())
    }
}
