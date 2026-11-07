use crate::error::{CoreError, Result};
use crate::model::OpOutcome;
use crate::platform::ExecResult;

/// `Success: created install session [1234567]` -> 1234567
pub fn parse_session_id(text: &str) -> Result<i64> {
    let open = text.rfind('[').ok_or_else(|| {
        CoreError::Session(format!("未找到会话编号: {}", text.trim()))
    })?;
    let rest = &text[open + 1..];
    let close = rest.find(']').ok_or_else(|| {
        CoreError::Session(format!("会话编号格式错误: {}", text.trim()))
    })?;
    rest[..close]
        .trim()
        .parse::<i64>()
        .map_err(|_| CoreError::Session(format!("无法解析会话编号: {}", &rest[..close])))
}

/// 从 pm 输出中提取 `INSTALL_FAILED_*` / `DELETE_FAILED_*` 等失败码。
pub fn failure_code(text: &str) -> Option<String> {
    for line in text.lines() {
        if let Some(pos) = line.find('[') {
            if let Some(end) = line[pos + 1..].find(']') {
                let inner = &line[pos + 1..pos + 1 + end];
                if inner.starts_with("INSTALL_FAILED")
                    || inner.starts_with("DELETE_FAILED")
                    || inner.starts_with("INSTALL_PARSE_FAILED")
                {
                    return Some(inner.to_string());
                }
            }
        }
        for token in line.split(|c: char| !c.is_ascii_alphanumeric() && c != '_') {
            if token.starts_with("INSTALL_FAILED")
                || token.starts_with("DELETE_FAILED")
                || token.starts_with("INSTALL_PARSE_FAILED")
            {
                return Some(token.to_string());
            }
        }
    }
    None
}

fn outcome(res: &ExecResult, default_code: &str) -> OpOutcome {
    let raw = res.combined();
    let trimmed = raw.trim();
    let success = res.code == 0 && trimmed.contains("Success");
    if success {
        return OpOutcome::success(raw);
    }
    let code = failure_code(&raw).unwrap_or_else(|| {
        if res.code != 0 { "SHELL_ERROR".to_string() } else { default_code.to_string() }
    });
    let message = trimmed.lines().last().unwrap_or("").trim().to_string();
    OpOutcome {
        ok: false,
        code,
        message: if message.is_empty() { format!("pm 退出码 {}", res.code) } else { message },
        raw,
    }
}

pub fn install_outcome(res: &ExecResult) -> OpOutcome {
    outcome(res, "INSTALL_FAILED_UNKNOWN")
}

pub fn uninstall_outcome(res: &ExecResult) -> OpOutcome {
    outcome(res, "DELETE_FAILED_UNKNOWN")
}

/// 解析 `cmd package resolve-activity --brief` 的组件行 `pkg/.Activity`。
pub fn resolve_activity_component(text: &str) -> Option<String> {
    text.lines()
        .map(str::trim)
        .filter(|l| l.contains('/') && !l.contains(' ') && !l.starts_with("priority="))
        .last()
        .map(|s| s.to_string())
}
