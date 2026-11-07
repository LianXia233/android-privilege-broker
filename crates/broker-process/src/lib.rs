//! 特权进程执行引擎（单一 ADB Shell 后端）。
//!
//! 约束：
//! - 命令始终以参数数组交给 `Command`，绝不拼 shell 字符串；
//! - stdout / stderr 分路收集，各自有上限，防止恶意输出耗尽内存；
//! - 超时与取消都真实终止子进程并回收，不留下僵尸进程；
//! - 句柄登记供会话撤销时批量终止；
//! - 显式剔除动态链接器注入变量，其余环境由启动者提供。

use std::collections::HashMap;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};

use broker_exec::{ExecOutcome, ExecSpec, StdinData};
use broker_protocol::error::ErrorCode;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, oneshot, Mutex};
use tokio::task::JoinHandle;
use tokio::time::{timeout, Duration};

/// 单条输出流的最大保留字节数。
pub const MAX_OUTPUT_BYTES: usize = 4 * 1024 * 1024;

struct Running {
    cancel: mpsc::Sender<()>,
    #[allow(dead_code)]
    task: JoinHandle<()>,
}

/// 进程管理器。`require_shell_uid=true` 时非 2000 一律拒绝执行。
pub struct ProcessManager {
    next_handle: AtomicU64,
    running: Mutex<HashMap<u64, Running>>,
    uid: i32,
    require_shell_uid: bool,
}

impl ProcessManager {
    /// `uid` 由平台层实测传入。
    pub fn new(uid: i32, require_shell_uid: bool) -> Self {
        Self {
            next_handle: AtomicU64::new(1),
            running: Mutex::new(HashMap::new()),
            uid,
            require_shell_uid,
        }
    }

    pub fn uid(&self) -> i32 {
        self.uid
    }

    fn backend_ready(&self) -> Result<(), ErrorCode> {
        if self.require_shell_uid && self.uid != 2000 {
            return Err(ErrorCode::BackendUnavailable);
        }
        Ok(())
    }

    /// 执行一条命令并等待结果（客户端主路径）。
    pub async fn execute(&self, spec: ExecSpec) -> Result<ExecOutcome, ErrorCode> {
        self.backend_ready()?;
        let (_tx, rx) = mpsc::channel::<()>(1);
        run_one(spec, rx).await
    }

    /// 带句柄登记执行，返回 (句柄, 结果接收端)，可经 `cancel` 提前终止。
    pub async fn execute_cancellable(
        &self,
        spec: ExecSpec,
    ) -> Result<(u64, oneshot::Receiver<ExecOutcome>), ErrorCode> {
        self.backend_ready()?;
        let handle = self.next_handle.fetch_add(1, Ordering::Relaxed).max(1);
        let (cancel_tx, cancel_rx) = mpsc::channel::<()>(1);
        let (done_tx, done_rx) = oneshot::channel::<ExecOutcome>();

        let task = tokio::spawn(async move {
            let out = run_one(spec, cancel_rx).await.unwrap_or(ExecOutcome {
                code: -1,
                stdout: vec![],
                stderr: "执行内部异常".as_bytes().to_vec(),
                timed_out: false,
                cancelled: false,
            });
            let _ = done_tx.send(out);
        });
        self.running.lock().await.insert(handle, Running { cancel: cancel_tx, task });
        Ok((handle, done_rx))
    }

    pub async fn cancel(&self, handle: u64) -> Result<(), ErrorCode> {
        match self.running.lock().await.remove(&handle) {
            Some(r) => {
                let _ = r.cancel.send(()).await;
                Ok(())
            }
            None => Err(ErrorCode::InvalidRequest),
        }
    }

    /// 会话撤销：终止该会话登记的全部句柄，返回成功发出取消的数量。
    pub async fn cancel_all(&self, handles: &[u64]) -> usize {
        let mut n = 0;
        for h in handles {
            if self.cancel(*h).await.is_ok() {
                n += 1;
            }
        }
        n
    }

    /// 正常结束后移除登记。
    pub async fn detach(&self, handle: u64) {
        self.running.lock().await.remove(&handle);
    }

    pub async fn active_count(&self) -> usize {
        self.running.lock().await.len()
    }
}

/// 向进程发 SIGKILL；不需要持有 `Child` 的可变借用，避免与 `wait` 冲突。
fn kill_pid(pid: u32) {
    if pid > 0 {
        unsafe {
            libc::kill(pid as i32, libc::SIGKILL);
        }
    }
}

/// 单条命令的完整生命周期：创建 → 注入 stdin → 分路读取 → 等待/超时/取消 → 回收。
async fn run_one(spec: ExecSpec, mut cancel_rx: mpsc::Receiver<()>) -> Result<ExecOutcome, ErrorCode> {
    let mut cmd = tokio::process::Command::new(spec.program());
    cmd.args(spec.args())
        .env_remove("LD_PRELOAD")
        .env_remove("LD_LIBRARY_PATH")
        .env_remove("LD_AUDIT")
        .envs(spec.env.iter().map(|(k, v)| (k.as_str(), v.as_str())))
        .stdin(if matches!(spec.stdin, StdinData::None) { Stdio::null() } else { Stdio::piped() })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);

    let mut child = cmd.spawn().map_err(|_| ErrorCode::ExecSpawnFailed)?;
    let pid = child.id().ok_or(ErrorCode::ExecSpawnFailed)?;

    if let Some(mut sink) = child.stdin.take() {
        let result: Result<(), ErrorCode> = match &spec.stdin {
            StdinData::None => Ok(()),
            StdinData::Bytes(b) => sink.write_all(b).await.map_err(|_| ErrorCode::StdinWriteFailed),
            StdinData::Path(p) => match tokio::fs::File::open(p).await {
                Ok(mut f) => {
                    let mut buf = vec![0u8; 64 * 1024];
                    loop {
                        match f.read(&mut buf).await {
                            Ok(0) => break,
                            Ok(n) => {
                                // pm install-write 读够字节后会提前关管道，此时静默停止即可。
                                if sink.write_all(&buf[..n]).await.is_err() { break; }
                            }
                            Err(_) => break,
                        }
                    }
                    Ok(())
                }
                Err(_) => Err(ErrorCode::StdinWriteFailed),
            },
        };
        drop(sink); // 关闭 stdin，让等待 EOF 的命令能结束
        result?;
    }

    let mut stdout = child.stdout.take().ok_or(ErrorCode::ExecSpawnFailed)?;
    let mut stderr = child.stderr.take().ok_or(ErrorCode::ExecSpawnFailed)?;
    let out_task = tokio::spawn(async move { read_limited(&mut stdout).await });
    let err_task = tokio::spawn(async move { read_limited(&mut stderr).await });

    let dur = Duration::from_millis(spec.timeout_ms.max(1));
    let wait_fut = child.wait();
    tokio::pin!(wait_fut);

    let mut out = tokio::select! {
        r = timeout(dur, &mut wait_fut) => match r {
            Err(_) => {
                kill_pid(pid);
                ExecOutcome { code: -1, stdout: vec![], stderr: "执行超时".as_bytes().to_vec(), timed_out: true, cancelled: false }
            }
            Ok(Err(_)) => {
                kill_pid(pid);
                return Err(ErrorCode::ExecWaitFailed);
            }
            Ok(Ok(status)) => ExecOutcome {
                code: status.code().unwrap_or(-1),
                stdout: vec![], stderr: vec![], timed_out: false, cancelled: false,
            },
        },
        _ = cancel_rx.recv() => {
            kill_pid(pid);
            ExecOutcome { code: -1, stdout: vec![], stderr: "已取消".as_bytes().to_vec(), timed_out: false, cancelled: true }
        }
    };

    if let Ok(v) = out_task.await { out.stdout = v; }
    if let Ok(v) = err_task.await { out.stderr = v; }
    Ok(out)
}

async fn read_limited<R: AsyncReadExt + Unpin>(r: &mut R) -> Vec<u8> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 8192];
    loop {
        match r.read(&mut chunk).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                let room = MAX_OUTPUT_BYTES.saturating_sub(buf.len());
                buf.extend_from_slice(&chunk[..n.min(room)]);
                if buf.len() >= MAX_OUTPUT_BYTES { break; }
            }
        }
    }
    buf
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spec(argv: &[&str], stdin: StdinData, timeout_ms: u64) -> ExecSpec {
        ExecSpec {
            argv: argv.iter().map(|s| s.to_string()).collect(),
            stdin,
            env: vec![],
            timeout_ms,
            cancel_group: 0,
        }
    }

    fn pm() -> ProcessManager {
        ProcessManager::new(-1, false) // host 测试不要求 shell uid
    }

    #[tokio::test]
    async fn executes_argv_without_shell_interpretation() {
        let out = pm().execute(spec(&["/bin/sh", "-c", "printf '%s' 'a b;$(x)'"], StdinData::None, 5000)).await.unwrap();
        assert_eq!(out.code, 0);
        assert_eq!(String::from_utf8_lossy(&out.stdout), "a b;$(x)");
        assert!(!out.timed_out && !out.cancelled);
    }

    #[tokio::test]
    async fn stdout_and_stderr_are_separated() {
        let out = pm().execute(spec(&["/bin/sh", "-c", "printf OUT; printf ERR >&2"], StdinData::None, 5000)).await.unwrap();
        assert_eq!(String::from_utf8_lossy(&out.stdout), "OUT");
        assert_eq!(String::from_utf8_lossy(&out.stderr), "ERR");
    }

    #[tokio::test]
    async fn bytes_stdin_is_delivered() {
        let out = pm().execute(spec(&["/bin/cat"], StdinData::Bytes(b"hello-stdin".to_vec()), 5000)).await.unwrap();
        assert_eq!(String::from_utf8_lossy(&out.stdout), "hello-stdin");
    }

    #[tokio::test]
    async fn path_stdin_is_streamed_from_file() {
        let p = std::env::temp_dir().join(format!("broker_stdin_{}.bin", std::process::id()));
        tokio::fs::write(&p, b"file-content").await.unwrap();
        let out = pm().execute(spec(&["/bin/cat"], StdinData::Path(p.to_string_lossy().into()), 5000)).await.unwrap();
        assert_eq!(String::from_utf8_lossy(&out.stdout), "file-content");
        tokio::fs::remove_file(&p).await.ok();
    }

    #[tokio::test]
    async fn timeout_kills_process() {
        let out = pm().execute(spec(&["/bin/sleep", "5"], StdinData::None, 120)).await.unwrap();
        assert!(out.timed_out, "超时必须被标记，不能伪装成正常退出");
        assert!(!out.cancelled);
    }

    #[tokio::test]
    async fn unknown_program_returns_spawn_error() {
        let r = pm().execute(spec(&["/definitely/not/a/program"], StdinData::None, 1000)).await;
        assert_eq!(r.unwrap_err(), ErrorCode::ExecSpawnFailed);
    }

    #[tokio::test]
    async fn shell_uid_requirement_is_enforced() {
        let strict = ProcessManager::new(0, true);
        assert_eq!(strict.execute(spec(&["/bin/true"], StdinData::None, 1000)).await, Err(ErrorCode::BackendUnavailable));
        let ok = ProcessManager::new(2000, true);
        assert!(ok.execute(spec(&["/bin/true"], StdinData::None, 1000)).await.is_ok());
    }

    #[tokio::test]
    async fn cancellation_marks_outcome_and_clears_registration() {
        let mgr = std::sync::Arc::new(pm());
        let (handle, done) = mgr.execute_cancellable(spec(&["/bin/sleep", "5"], StdinData::None, 10_000)).await.unwrap();
        assert_eq!(mgr.active_count().await, 1);
        mgr.cancel(handle).await.unwrap();
        let out = tokio::time::timeout(Duration::from_secs(3), done).await.unwrap().unwrap();
        assert!(out.cancelled, "取消必须被标记");
        mgr.detach(handle).await;
        assert_eq!(mgr.active_count().await, 0);
    }
}
