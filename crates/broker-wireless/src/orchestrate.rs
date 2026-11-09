//! 无电脑自助链路的编排入口。
//!
//! Java 层负责 mDNS 端口发现与配对码输入。参考同类成熟实现，配对与连接启动是
//! **解耦的两个阶段**：配对服务只在「使用配对码配对设备」弹窗打开期间存在，
//! 而配对成功后系统可能重分配 `_adb-tls-connect` 端口。因此本模块支持 `stage`：
//! * `pair`：只完成 TLS 配对与公钥注册，返回 `stage=paired`；
//! * `start`：跳过配对，用重新发现到的连接端口连接并启动；
//! * 缺省 / `full`：一次串完（通知栏可选通道的兼容路径）。
//!
//! 连接目标地址不再假定为 loopback。AOSP 把 adbd 的 TLS 端口绑在
//! `127.0.0.1`，但部分 ROM（如 HyperOS）绑定在 WLAN 接口 IP 上，设置页里
//! 显示的 `IP 地址和端口` 就是该地址。Java 层把 mDNS 解析地址、本机网卡
//! IPv4 与 loopback 一起作为 `hosts` 传进来，这里逐个尝试，任一连通即用。
//!
//! 所有结果以稳定 JSON 返回，带 stage / code / message，不抛异常。

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::adb::AdbClient;
use crate::error::WirelessError;
use crate::key::AdbKey;
use crate::pairing;

const STARTER_CLASS: &str = "io.github.lianxia233.privbroker.ServiceStarter";

/// server 进程名，对应 `app_process --nice-name`。对齐成熟实现
/// 的 固定进程名：启动前用它遍历 `/proc` 清理旧实例，保证单实例。
/// 必须短于 15 字符（Linux `comm` 上限），否则会被截断导致匹配不到。
pub const SERVER_PROCESS_NAME: &str = "prvbrk_svc";

/// native 在 `Listener::bind` **成功之后**打印的标记，代表端点确实已监听。
/// 此前「准备监听:」只在 bind 之前打印，不能证明成功；「监听:」更易误读。
pub const BROKER_LISTEN_MARKER: &str = "监听成功:";

/// native 检测到端点已被现有实例占用时打印。这时服务其实已经在运行，
/// 是一次幂等的重复激活，绝不能当成启动失败。
pub const BROKER_ALREADY_RUNNING_MARKER: &str = "特权代理已在运行";

pub const STAGE_PAIR: &str = "pair";
pub const STAGE_START: &str = "start";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WirelessRequest {
    /// 兼容旧调用方的主地址；`hosts` 为空时使用。
    #[serde(default)]
    pub host: String,
    /// 候选连接地址，按可靠性排序（mDNS 解析地址 → 本机网卡 IPv4 → loopback）。
    #[serde(default)]
    pub hosts: Vec<String>,
    pub pairing_port: u16,
    pub pairing_code: String,
    pub connect_port: u16,
    pub key_path: String,
    pub apk_path: String,
    pub native_lib_dir: String,
    pub token: String,
    pub client: String,
    pub endpoint: String,
    /// 执行阶段：`pair` / `start` / 缺省（完整链路）。
    #[serde(default)]
    pub stage: String,
}

impl WirelessRequest {
    /// 去重后的候选地址；至少保证有一个可尝试的目标。
    fn candidate_hosts(&self) -> Vec<String> {
        let mut result: Vec<String> = Vec::new();
        for h in self.hosts.iter().chain(std::iter::once(&self.host)) {
            let s = h.trim();
            if !s.is_empty() && !result.iter().any(|x| x == s) {
                result.push(s.to_string());
            }
        }
        if result.is_empty() {
            result.push("127.0.0.1".to_string());
        }
        result
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WirelessResponse {
    pub ok: bool,
    pub stage: String,
    pub code: Option<String>,
    pub message: String,
    pub output: Option<String>,
    /// native 在 `Listener::bind` 成功后打印「监听成功:」，或探测到已有实例
    /// 时打印「特权代理已在运行:」。对齐成熟实现：以启动器
    /// 输出判定启动结果：这是 shell 侧权威证据，不依赖 app 进程能否直连
    /// server 的 abstract socket（部分 ROM 的 SELinux 会拦截该直连）。
    pub listening: bool,
    /// 本次是幂等重复激活：端点已被一个正常运行的现有实例占用，无需重启。
    pub already_running: bool,
    /// 启动日志里完整的一条失败行（含 errno / 异常文本）。单独带回，避免
    /// 界面因字符截断把最关键的失败原因切掉，也便于落库到「执行日志」。
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure_reason: Option<String>,
}

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

/// 启动日志是否出现「服务已在监听」的标记。仅作诊断线索，不再作为
/// 激活成功的判据 —— 成功一律以 app 侧实测连通为准。端点已改为 TCP
/// loopback，两侧都能访问，实测结果是可靠的。
/// `监听成功:` = 本次 bind 成功；`特权代理已在运行` = 复用了已有实例。
pub fn boot_log_confirms_listening(log: &str) -> bool {
    log.contains(BROKER_LISTEN_MARKER) || log.contains(BROKER_ALREADY_RUNNING_MARKER)
}

/// 启动日志是否表明本次是「已有实例在跑」的幂等重复激活。
pub fn boot_log_confirms_already_running(log: &str) -> bool {
    log.contains(BROKER_ALREADY_RUNNING_MARKER)
}

/// 提取启动日志中完整的一条失败行（含 errno / 异常文本），倒序取最后一条。
/// 结果条与「执行日志」据此显示真实原因，而不是一个被截断的省略号。
pub fn boot_log_failure_reason(log: &str) -> Option<String> {
    const MARKERS: &[&str] = &[
        "监听失败", "特权代理启动失败", "无法加载 native", "加载 native 库失败",
        "ClassNotFoundException", "UnsatisfiedLinkError",
    ];
    log.lines()
        .rev()
        .map(str::trim)
        .find(|l| l.starts_with("错误:") || MARKERS.iter().any(|m| l.contains(m)))
        .filter(|l| !l.is_empty())
        .map(|l| l.to_string())
}

/// 构造在无线调试 shell 中后台启动特权代理的命令。
///
/// 五个细节决定「服务能不能起来并常驻」，缺一个就会表现为「提示成功但服务未运行」：
///
/// **本命令有硬性时间预算**：native 投递后只等 6 秒（`DETACHED_READ_TIMEOUT`）
/// 就断开。一旦超时，adbd 会连带清理本次 shell 会话的进程组，而那时命令很可能
/// 还停在启动前的准备工作里 —— 表现正是「命令确实下发过（logcat 能看到
/// `adbd service requested 'shell:...'`），进程却始终没起来、启动日志也没有新增」。
/// 所以每一段都必须足够快：真机实测用 `$(tr -d ...)` 逐个读 `/proc/pid/cmdline`
/// 会为每个进程 fork 一次外部命令，882 个进程耗掉 26 秒，单这一段就吃满了预算；
/// 改用 shell 内建 `read` 读同一个文件只需约 0.1 秒，且匹配语义不变。
///
/// 0. **单实例（对齐成熟实现的「启动前清理旧进程」）**：启动前遍历
///    `/proc/*/cmdline`，把同名旧实例全部 `SIGKILL`。此前每次「激活」都无脑再拉
///    一个 app_process，旧进程仍占着监听端点，新进程 bind 必然
///    `Address already in use (os error 98)` 并退出。成熟实现用 C 层的
///    `foreach_proc` + `kill(pid, SIGKILL)` 做同一件事，这里用 shell 等价实现。
///
///    **判据必须取 cmdline，不能取 comm**：`app_process --nice-name=` 只把
///    `/proc/pid/cmdline` 收缩成该短名，而 `comm` 会被运行时改写成 `main`
///    （Java 主线程名）。按 comm 匹配在真机上恒为 0 命中，旧实例永远清不掉，
///    升级后新客户端仍连上旧代理（旧代理的挑战随机数未限制在 JSON 精确整数
///    范围内），表现为「激活看着成功、所有静默安装都失败」。匹配还必须锚定
///    **首个参数**：执行这段清理的 `sh -c` 自身 cmdline 里也含该进程名字样。
/// 1. **重定向 stdio**：常驻的 app_process 若继承 shell 通道的 stdout/stderr，
///    adbd 会一直等这些 fd 关闭才发 `A_CLSE`；客户端读超时断开后，adbd 又会
///    kill 掉整个进程组。所以后台进程必须 `</dev/null >>log 2>&1`。
/// 2. **启动即脱离会话（保活关键）**：命令层用 `setsid` 让 app_process
///    从第一条指令起就是独立会话的首进程，adbd 会话收尾时按进程组发出的信号
///    再也碰不到它。仅靠 Java 侧 `Os.setsid()` 不够 —— 后台 job 常常已经是
///    进程组首进程，`setsid()` 会失败，进程仍留在 adbd 会话里被连带清理。
///    `ServiceStarter` 里的 `Os.setsid()` 保留作兜底；设备缺少 setsid 命令时
///    也退化为原来的后台启动，不至于完全无法激活。
/// 3. **回读启动日志**：把 stderr 落到 shell 可写的临时文件再 `cat` 出来，
///    客户端既能据此判断是否真的绑定成功，也能在类加载 / so 加载失败时把
///    真实原因带回界面。文件名带 `$$`（shell PID）：并发激活各写各的文件。
/// 4. **端点可达**：默认 TCP loopback。SELinux enforcing 下 app 域连不上 shell
///    域的 abstract socket，只有本机回环 TCP 能被两侧同时访问。
/// 5. **防幻影进程清理**：Android 12+ 会回收 shell fork 的子进程（即使已
///    setsid），启动前用 shell 权限关闭幻影进程监控并抬高上限，这是代理
///    「激活几分钟后无故消失」的根因。
pub fn build_start_command(req: &WirelessRequest) -> String {
    // 用 `env` 设置 CLASSPATH 再 exec app_process，避免再套一层 `sh -c`
    // 造成参数里单引号的二次转义；setsid 直接把这个命令拉进新会话。
    let invoke = format!(
        "env CLASSPATH={} app_process /system/bin --nice-name={} {} \
         --token={} --client={} --endpoint={} --lib={}",
        shell_quote(&req.apk_path),
        SERVER_PROCESS_NAME,
        STARTER_CLASS,
        shell_quote(&req.token),
        shell_quote(&req.client),
        shell_quote(&req.endpoint),
        shell_quote(&req.native_lib_dir),
    );
    // 判据取自 `/proc/pid/cmdline` 而非 comm：`--nice-name=` 只改写 cmdline，
    // comm 会被运行时改成 `main`（Java 主线程名），按 comm 匹配恒不命中 ——
    // 真机实测残留实例的 comm 就是 `main`，旧逻辑 0 命中。
    // 匹配锚定首个参数（前缀匹配）：执行本段清理的 `sh -c` 自身 cmdline 以
    // `sh` 开头，用 `*prvbrk_svc*` 会把清理者自己算进去（实测还会命中无关的
    // 长驻进程）。但前缀之后不能用 `\\ *`：ash 读 cmdline 是把 NUL **删除后
    // 拼接**（实测 `sh -c ...` 读到的是 `/system/bin/sh-cecho ...`），带空格的
    // 形态永远不会出现，所以统一用 `{new}*` 覆盖「只有名字」与「名字后跟参数」
    // 两种 cmdline 形态。
    // 读取必须用 shell 内建 `read`：`$(tr -d '\\000' < ...)` 会为每个进程
    // fork 一次外部命令，882 个进程 26 秒，直接吃满 native 的 6 秒预算
    // （见本函数文档的「硬性时间预算」）。`read` 对无换行的 cmdline 恒返回
    // 非 0，所以不能写 `|| continue`，改为每轮显式 `c=` 清空，避免读取失败时
    // 沿用上一轮的值误杀。
    // 同时兼容旧版进程名 `privilege-broker`，确保从旧版本升级后第一次激活就能
    // 清掉残留实例 —— 残留实例正是本轮「升级后安装全失败」的直接来源。
    let kill_old = format!(
        "SELF=$$; for p in /proc/[0-9]*; do \
           c=; read -r c < \"$p/cmdline\" 2>/dev/null; \
           case \"$c\" in {new}*|privilege-broker*) \
             pid=${{p##*/}}; [ \"$pid\" = \"$SELF\" ] && continue; \
             kill -9 \"$pid\" 2>/dev/null ;; \
           esac; \
         done; sleep 1",
        new = SERVER_PROCESS_NAME,
    );
    // 保活的另一半：Android 12+ 的幻影进程监控会清理 shell fork 出来的
    // app_process 子进程——即使它已 setsid 脱离会话，激活几分钟后代理无故
    // 消失、安装时报「特权代理服务未运行」正是它干的。shell(2000) 恰好有
    // 权限直接关掉这两项设置；失败（老系统无此命令）不影响启动。
    // 命名空间必须是 `global`、键名是 `settings_enable_monitor_phantom_procs`：
    // 旧写法 `settings put settings enable_phantom_process_monitoring` 里
    // `settings` 不是合法命名空间，真机实测直接返回 `Invalid namespace 'settings'`
    // （rc=255），等于这项保活从未生效过。
    let keep_alive = "settings put global settings_enable_monitor_phantom_procs false 2>/dev/null; \
                      device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null";
    format!(
        "{kill_old}; {keep_alive}; \
         LOG=/data/local/tmp/prvbrk_boot.$$.log; : > \"$LOG\" 2>/dev/null || LOG=/dev/null; \
         if command -v setsid >/dev/null 2>&1; then SETSID=setsid; else SETSID=; fi; \
         $SETSID {invoke} </dev/null >>\"$LOG\" 2>&1 & sleep 2; cat \"$LOG\" 2>/dev/null"
    )
}

/// 按阶段校验：只配对时不要求连接端口与启动参数，只启动时反之。
fn validate(req: &WirelessRequest, need_pair: bool, need_start: bool) -> Result<(), WirelessError> {
    if req.candidate_hosts().is_empty() {
        return Err(WirelessError::Protocol("没有可用的连接地址".into()));
    }
    if req.key_path.trim().is_empty() {
        return Err(WirelessError::Protocol("密钥路径不能为空".into()));
    }
    if need_pair && req.pairing_port == 0 {
        return Err(WirelessError::Protocol("配对服务端口无效".into()));
    }
    if need_start {
        if req.connect_port == 0 {
            return Err(WirelessError::Protocol("连接服务端口无效".into()));
        }
        if req.token.len() < 16 {
            return Err(WirelessError::Protocol("令牌长度不足 16 字符".into()));
        }
        if req.client.trim().is_empty() {
            return Err(WirelessError::Protocol("客户端白名单不能为空".into()));
        }
        if !req.apk_path.starts_with('/') || !req.native_lib_dir.starts_with('/') {
            return Err(WirelessError::Protocol("APK 与 native 库路径必须为绝对路径".into()));
        }
    }
    Ok(())
}

/// 在多个候选地址上尝试同一个连接动作，任一成功即返回。
///
/// 全部失败时优先返回「非连接类」错误（配对码错误、协议错误等更有诊断价值，
/// 说明已经连上了只是对端拒绝了），否则返回最后一次连接错误。
fn try_hosts<T, F>(hosts: &[String], mut action: F) -> Result<T, WirelessError>
where
    F: FnMut(&str) -> Result<T, WirelessError>,
{
    let mut last_conn: Option<WirelessError> = None;
    for host in hosts {
        match action(host) {
            Ok(value) => return Ok(value),
            Err(e) => match e {
                WirelessError::Io(_) | WirelessError::Tls(_) => last_conn = Some(e),
                other => return Err(other),
            },
        }
    }
    Err(last_conn.unwrap_or_else(|| {
        WirelessError::Protocol("所有候选地址均无法连接".into())
    }))
}

fn execute(args_json: &str) -> Result<WirelessResponse, (String, WirelessError)> {
    let req: WirelessRequest = serde_json::from_str(args_json)
        .map_err(|e| ("config".to_string(), WirelessError::Protocol(format!("请求 JSON 非法: {e}"))))?;

    let need_pair = req.stage != STAGE_START;
    let need_start = req.stage != STAGE_PAIR;
    validate(&req, need_pair, need_start).map_err(|e| ("config".to_string(), e))?;

    let hosts = req.candidate_hosts();

    // 配对与后续连接必须复用同一把密钥：设备端登记的公钥就是 TLS 客户端证书。
    let key = AdbKey::load_or_create(Path::new(&req.key_path))
        .map_err(|e| ("key".to_string(), e))?;

    if need_pair {
        let _ = try_hosts(&hosts, |host| {
            pairing::pair(host, req.pairing_port, &req.pairing_code, &key)
        }).map_err(|e| ("pairing".to_string(), e))?;
        if !need_start {
            return Ok(WirelessResponse {
                ok: true,
                stage: "paired".to_string(),
                code: None,
                message: "配对成功，公钥已登记到设备。".to_string(),
                output: None,
                listening: false,
                already_running: false,
                failure_reason: None,
            });
        }
    }

    let mut client = try_hosts(&hosts, |host| {
        AdbClient::connect(host, req.connect_port, &key)
    }).map_err(|e| ("connect".to_string(), e))?;
    let command = build_start_command(&req);
    // 后台启动命令不依赖 A_CLSE：命令投递即返回，最终是否常驻由界面探测
    // abstract socket 判定，避免 adbd 通道时序把「已启动」误报成失败。
    let output = client.shell_detached(&command).map_err(|e| ("shell".to_string(), e))?;
    let text = String::from_utf8_lossy(&output).to_string();
    // 以 shell 侧启动日志的权威标记为准：bind 成功或复用已有实例都算服务在跑。
    // 这样「app 进程连不上 shell 域 abstract socket」不会再被误判成激活失败，
    // 也正是 成熟实现判定启动结果的方式。
    let already_running = boot_log_confirms_already_running(&text);
    let listening = boot_log_confirms_listening(&text);
    // 只有确实没起来的情况下，失败行才有诊断价值；已确认监听时不报假失败。
    let failure = if listening { None } else { boot_log_failure_reason(&text) };
    let message = if already_running {
        "特权代理已在运行，本次激活复用了现有实例。".to_string()
    } else if listening {
        "特权代理已启动并开始监听。".to_string()
    } else {
        "已投递启动命令，正在等待代理绑定端点。".to_string()
    };

    Ok(WirelessResponse {
        ok: true,
        stage: "started".to_string(),
        code: None,
        message,
        output: Some(text),
        listening,
        already_running,
        failure_reason: failure,
    })
}

/// 供 JNI 调用：返回 JSON，任何失败都带 stage / code / message，不抛异常。
pub fn run(args_json: &str) -> String {
    let response = match execute(args_json) {
        Ok(r) => r,
        Err((stage, e)) => WirelessResponse {
            ok: false,
            stage,
            code: Some(e.code().to_string()),
            message: e.to_string(),
            output: None,
            listening: false,
            already_running: false,
            failure_reason: None,
        },
    };
    serde_json::to_string(&response).unwrap_or_else(|e| {
        format!("{{\"ok\":false,\"stage\":\"config\",\"code\":\"PROTOCOL\",\"message\":\"结果序列化失败: {e}\"}}")
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request() -> WirelessRequest {
        WirelessRequest {
            host: "127.0.0.1".into(),
            hosts: vec![],
            pairing_port: 37331,
            pairing_code: "482913".into(),
            connect_port: 41235,
            key_path: "/data/user/0/io.github.lianxia233.privbroker/files/adb_key.pk8".into(),
            apk_path: "/data/app/~~abc/base.apk".into(),
            native_lib_dir: "/data/app/~~abc/lib/arm64".into(),
            token: "0123456789abcdef0123456789abcdef".into(),
            client: "installer".into(),
            endpoint: "abstract".into(),
            stage: String::new(),
        }
    }

    #[test]
    fn start_command_contains_all_arguments_and_is_backgrounded() {
        let cmd = build_start_command(&request());
        assert!(cmd.contains("env CLASSPATH='"));
        assert!(cmd.contains("app_process /system/bin"));
        assert!(cmd.contains("io.github.lianxia233.privbroker.ServiceStarter"));
        assert!(cmd.contains("--token='0123456789abcdef0123456789abcdef'"));
        assert!(cmd.contains("--endpoint='abstract'"));
        assert!(cmd.contains("--lib='/data/app/~~abc/lib/arm64'"));
        assert!(cmd.contains("& sleep 2; cat"));
    }

    /// 对齐成熟实现 的 kill old process：启动前必须清理旧实例，
    /// 否则旧进程占着监听端点，新进程 bind 必然 EADDRINUSE(os error 98)。
    /// 判据必须是 cmdline：`--nice-name=` 只改写 cmdline，`comm` 恒为 `main`。
    #[test]
    fn start_command_kills_old_instances_before_start() {
        let cmd = build_start_command(&request());
        assert!(cmd.contains("--nice-name=prvbrk_svc"));
        assert!(cmd.contains("/proc/[0-9]*"), "必须遍历 /proc");
        assert!(cmd.contains("< \"$p/cmdline\""), "按 cmdline 识别进程");
        assert!(!cmd.contains("/comm"), "不得再使用 comm 判据");
        // 必须用 shell 内建 read：`$(tr -d ...)` 逐个 fork 外部命令读 cmdline，
        // 真机 882 个进程要 26 秒，单这一段就吃满 native 的 6 秒投递预算。
        assert!(!cmd.contains("tr -d"), "不得逐进程 fork 外部命令读 cmdline");
        assert!(
            cmd.contains("c=; read -r c < \"$p/cmdline\""),
            "用内建 read 读 cmdline（read 对无换行文件恒返回非 0，故每轮显式清空）"
        );
        assert!(cmd.contains("kill -9"), "SIGKILL 旧实例");
        // 锚定首个参数（前缀匹配）：执行清理的 sh -c 自身 cmdline 以 sh 开头。
        // ash 读 cmdline 会删除 NUL 后拼接，带空格的形态不会出现，统一用 `*`。
        assert!(
            cmd.contains("prvbrk_svc*|privilege-broker*)"),
            "按首参数前缀匹配，兼容旧进程名"
        );
        // 清理必须发生在启动之前，否则清掉的是刚拉起的新进程。
        let kill_at = cmd.find("kill -9").unwrap();
        let start_at = cmd.find("app_process").unwrap();
        assert!(kill_at < start_at, "kill 必须早于 app_process 启动");
        // 不能误杀执行清理的 shell 自身。
        assert!(cmd.contains("[ \"$pid\" = \"$SELF\" ] && continue"));
    }

    /// 后台进程必须彻底脱离 shell 通道 fd，否则 adbd 不发 A_CLSE，客户端超时
    /// 断开后还会 kill 整个进程组 —— 这是「提示成功但服务从未运行」的根因。
    #[test]
    fn start_command_detaches_stdio_and_reads_boot_log() {
        let cmd = build_start_command(&request());
        assert!(cmd.contains("</dev/null >>\"$LOG\" 2>&1 &"));
        assert!(cmd.contains("cat \"$LOG\""));
        // 文件名带 shell PID：自动激活与手动激活并发时各写各的文件，不串味
        assert!(cmd.contains("prvbrk_boot.$$.log"));
        assert!(!cmd.contains("prvbrk_boot.log;"));
        // 不再有裸的 (...)& 结尾：那会让常驻进程持有 ADB socket fd。
        assert!(!cmd.ends_with(")&"));
    }

    /// 保活：进程必须从启动那一刻起就在独立会话里，不能只靠 Java 侧
    /// 事后 setsid（后台 job 常已是进程组首进程，会失败）。
    #[test]
    fn start_command_detaches_service_into_own_session() {
        let cmd = build_start_command(&request());
        assert!(cmd.contains("command -v setsid"), "探测 setsid 可用性");
        assert!(cmd.contains("SETSID=setsid"), "可用时启用 setsid");
        assert!(cmd.contains("$SETSID env CLASSPATH="), "setsid 直接 exec 启动体");
        let setsid_at = cmd.find("$SETSID").unwrap();
        let app_at = cmd.find("app_process").unwrap();
        assert!(setsid_at < app_at, "setsid 必须早于 app_process");
        // stdio 全脱离 + 独立会话，主 shell 退出不会带走服务
        assert!(cmd.contains("</dev/null >>\"$LOG\" 2>&1 &"));
    }

    /// 保活第二环：Android 12+ 幻影进程监控会回收 shell fork 的子进程，
    /// 即使已 setsid。启动命令必须在拉起服务前用 shell 权限关掉它。
    #[test]
    fn start_command_disables_phantom_process_monitoring() {
        let cmd = build_start_command(&request());
        // 命名空间必须是 global：旧写法 `settings put settings ...` 的
        // `settings` 不是合法命名空间，真机实测恒返回
        // `Invalid namespace 'settings'`(rc=255)，该项保活从未生效。
        assert!(cmd.contains(
            "settings put global settings_enable_monitor_phantom_procs false 2>/dev/null"),
            "关闭幻影进程监控（global 命名空间 + 正确键名）");
        assert!(!cmd.contains("settings put settings"), "不得使用非法命名空间");
        assert!(cmd.contains(
            "device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null"),
            "抬高幻影进程上限");
        // 必须发生在启动体之前
        let ka_at = cmd.find("settings_enable_monitor_phantom_procs").unwrap();
        let app_at = cmd.find("app_process").unwrap();
        assert!(ka_at < app_at, "防幻影进程设置必须早于启动");
    }

    #[test]
    fn listening_marker_is_detected_in_boot_log() {
        // bind 成功之后的权威标记才算「在跑」，bind 之前的阶段标记不算。
        assert!(boot_log_confirms_listening(
            "特权代理启动: uid=2000 ...\n监听成功: abstract:prvbrk.1234\n"));
        assert!(!boot_log_confirms_listening("准备监听: abstract:prvbrk.1234"));
        assert!(!boot_log_confirms_listening("ClassNotFoundException: ServiceStarter"));
        assert!(!boot_log_confirms_listening(""));
    }

    /// EADDRINUSE 幂等复用：日志出现「特权代理已在运行」也应判定服务在跑，
    /// 这正是这次要修的 `Address already in use` 场景。
    #[test]
    fn already_running_marker_counts_as_running() {
        let log = "特权代理启动: uid=2000 ...\n特权代理已在运行: abstract:prvbrk.abc（复用现有实例）";
        assert!(boot_log_confirms_listening(log));
        assert!(boot_log_confirms_already_running(log));
        assert!(!boot_log_confirms_already_running("监听成功: abstract:prvbrk.abc"));
    }

    /// 完整失败行（含 errno）必须结构化带回，不能被结果条字符截断吃掉。
    #[test]
    fn failure_reason_keeps_full_error_line() {
        let log = "特权代理进程已拉起，正在加载运行时…\n\
                   特权代理启动: uid=2000 backend=ADB_SHELL selinux=enforcing endpoint=abstract\n\
                   准备监听: abstract:prvbrk.3ae5ff681d61da99\n\
                   监听失败: Address already in use (os error 98)";
        assert_eq!(
            boot_log_failure_reason(log).as_deref(),
            Some("监听失败: Address already in use (os error 98)")
        );
        assert_eq!(
            boot_log_failure_reason("错误: 无法启动 Tokio 运行时: boom").as_deref(),
            Some("错误: 无法启动 Tokio 运行时: boom")
        );
        assert!(boot_log_failure_reason("准备监听: abstract:prvbrk.abc\n").is_none());
    }

    /// Java 侧按 camelCase 读 failureReason，序列化字段名必须对齐。
    #[test]
    fn response_serializes_failure_reason_in_camel_case() {
        let r = WirelessResponse {
            ok: true,
            stage: "started".into(),
            code: None,
            message: "投递完成".into(),
            output: None,
            listening: true,
            already_running: true,
            failure_reason: Some("监听失败: os error 98".into()),
        };
        let v: serde_json::Value =
            serde_json::from_str(&serde_json::to_string(&r).unwrap()).unwrap();
        assert_eq!(v["failureReason"], "监听失败: os error 98");
        assert_eq!(v["alreadyRunning"], true);
        assert_eq!(v["listening"], true);
    }

    #[test]
    fn shell_quote_escapes_single_quotes() {
        let mut req = request();
        req.apk_path = "/data/app/o'clock/base.apk".into();
        let cmd = build_start_command(&req);
        assert!(cmd.contains("'/data/app/o'\\''clock/base.apk'"));
    }

    #[test]
    fn malformed_request_returns_structured_error() {
        let out = run("not-json");
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["ok"], false);
        assert_eq!(v["stage"], "config");
        assert_eq!(v["code"], "PROTOCOL");
    }

    #[test]
    fn invalid_token_is_rejected_before_network_access() {
        let mut req = request();
        req.token = "short".into();
        let args = serde_json::to_string(&serde_json::json!({
            "host": req.host, "pairingPort": req.pairing_port,
            "pairingCode": req.pairing_code, "connectPort": req.connect_port,
            "keyPath": req.key_path, "apkPath": req.apk_path,
            "nativeLibDir": req.native_lib_dir, "token": req.token,
            "client": req.client, "endpoint": req.endpoint,
        })).unwrap();
        let out = run(&args);
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["ok"], false);
        assert_eq!(v["stage"], "config");
        assert!(v["message"].as_str().unwrap().contains("令牌"));
    }

    /// 配对阶段不要求连接服务端口与启动参数——配对成功后端口会重分配。
    #[test]
    fn pair_stage_needs_no_connect_port_or_start_args() {
        let mut req = request();
        req.connect_port = 0;
        req.token.clear();
        req.apk_path.clear();
        req.native_lib_dir.clear();
        assert!(validate(&req, true, false).is_ok());
    }

    /// 启动阶段不要求配对端口与配对码——公钥已在上一阶段登记。
    #[test]
    fn start_stage_needs_no_pairing_port_or_code() {
        let mut req = request();
        req.pairing_port = 0;
        req.pairing_code.clear();
        assert!(validate(&req, false, true).is_ok());
    }

    #[test]
    fn start_stage_still_requires_connect_port() {
        let mut req = request();
        req.connect_port = 0;
        assert!(validate(&req, false, true).is_err());
    }

    #[test]
    fn pair_stage_still_requires_pairing_port() {
        let mut req = request();
        req.pairing_port = 0;
        assert!(validate(&req, true, false).is_err());
    }

    /// 缺省 stage 走完整链路，两个端口都必须给出（兼容旧调用方）。
    #[test]
    fn full_stage_requires_both_ports() {
        let mut req = request();
        req.connect_port = 0;
        assert!(validate(&req, true, true).is_err());
        let mut req = request();
        req.pairing_port = 0;
        assert!(validate(&req, true, true).is_err());
    }

    #[test]
    fn stage_field_is_optional_in_json() {
        let req = request();
        let json = serde_json::to_string(&serde_json::json!({
            "host": req.host, "pairingPort": req.pairing_port,
            "pairingCode": req.pairing_code, "connectPort": req.connect_port,
            "keyPath": req.key_path, "apkPath": req.apk_path,
            "nativeLibDir": req.native_lib_dir, "token": req.token,
            "client": req.client, "endpoint": req.endpoint,
        })).unwrap();
        let parsed: WirelessRequest = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.stage, "");
    }

    // ------------------------------------------------------------ 多地址候选

    #[test]
    fn candidate_hosts_dedup_and_order() {
        let mut req = request();
        req.hosts = vec![
            "192.168.88.158".into(),
            "192.168.88.158".into(),
            " 10.0.0.2 ".into(),
            "".into(),
        ];
        req.host = "127.0.0.1".into();
        assert_eq!(req.candidate_hosts(), vec![
            "192.168.88.158".to_string(),
            "10.0.0.2".to_string(),
            "127.0.0.1".to_string(),
        ]);
    }

    #[test]
    fn candidate_hosts_falls_back_when_all_empty() {
        let mut req = request();
        req.host.clear();
        req.hosts.clear();
        assert_eq!(req.candidate_hosts(), vec!["127.0.0.1".to_string()]);
    }

    #[test]
    fn hosts_field_is_optional_in_json() {
        let req = request();
        let json = serde_json::to_string(&serde_json::json!({
            "host": req.host, "pairingPort": req.pairing_port,
            "pairingCode": req.pairing_code, "connectPort": req.connect_port,
            "keyPath": req.key_path, "apkPath": req.apk_path,
            "nativeLibDir": req.native_lib_dir, "token": req.token,
            "client": req.client, "endpoint": req.endpoint,
        })).unwrap();
        let parsed: WirelessRequest = serde_json::from_str(&json).unwrap();
        assert!(parsed.hosts.is_empty());
        assert_eq!(parsed.candidate_hosts(), vec!["127.0.0.1".to_string()]);
    }

    #[test]
    fn try_hosts_skips_failing_addresses() {
        let hosts = vec!["a".to_string(), "b".to_string(), "c".to_string()];
        let mut seen = Vec::new();
        let value: i32 = try_hosts(&hosts, |h| {
            seen.push(h.to_string());
            if h == "c" {
                Ok(7)
            } else {
                Err(WirelessError::Io(std::io::Error::new(
                    std::io::ErrorKind::ConnectionRefused, "refused")))
            }
        }).unwrap();
        assert_eq!(value, 7);
        assert_eq!(seen, vec!["a", "b", "c"]);
    }

    #[test]
    fn try_hosts_returns_protocol_error_immediately() {
        let hosts = vec!["a".to_string(), "b".to_string()];
        let mut seen = Vec::new();
        let err = try_hosts::<(), _>(&hosts, |h| {
            seen.push(h.to_string());
            Err(WirelessError::BadPairingCode)
        }).unwrap_err();
        assert_eq!(err.code(), "BAD_PAIRING_CODE");
        assert_eq!(seen, vec!["a"]);
    }

    #[test]
    fn wlan_ip_is_accepted_as_candidate() {
        let json = serde_json::json!({
            "hosts": ["192.168.88.158", "127.0.0.1"],
            "pairingPort": 39099, "pairingCode": "146861", "connectPort": 41407,
            "keyPath": "/tmp/k", "apkPath": "/a.apk", "nativeLibDir": "/lib",
            "token": "0123456789abcdef", "client": "installer", "endpoint": "abstract",
            "stage": "pair",
        });
        let parsed: WirelessRequest = serde_json::from_str(&json.to_string()).unwrap();
        assert_eq!(parsed.candidate_hosts()[0], "192.168.88.158");
    }
}
