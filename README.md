# android-privilege-broker

基于 **Rust 核心 + 自主 ADB 特权代理** 的 Android 静默安装器。特权进程由应用内的无线调试自助链路以 shell(uid 2000) 身份启动一次，全程不需要电脑或 USB；应用与代理之间用自研的长度前缀帧协议通信，安装 / 卸载时无需拉起系统安装界面，也不会跳到应用主界面，每一次后台操作都会写入本地安装日志。

| 项目 | 值 |
|---|---|
| 包名 | `io.github.lianxia233.privbroker`（v2.0.0 启用，与历史版本不互通） |
| 系统要求 | minSdk **26**（Android 8+）/ targetSdk **36**（Android 16）/ compileSdk **36** |
| 权限来源 | 自主 Broker，**ADB Shell / uid 2000 唯一后端**；不提供 root 后端，不引入第三方特权框架 |
| 架构 | 业务逻辑在 `installer-core`（Rust，host 可单测）；特权执行在独立 `broker-server`（Rust + Tokio，12 个 crate），无线调试链路在 `broker-wireless` |
| 产物 | 四 ABI（`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`）、16KB 页对齐、v2 签名 |
| 许可 | **GNU GPL-3.0-only**，见 `LICENSE` |
| 版本 | **2.1.14**；版本记录见 [CHANGELOG.md](CHANGELOG.md) |

## 1. 功能

| 编号 | 功能 | 实现位置 |
|---|---|---|
| F-01 | 特权代理状态检查、连接探测、**应用内无线调试启用与配对**（无需电脑） | `crates/core/src/state.rs`, `WirelessPairingService`, `WirelessCore`, `ServiceStarter`, `broker-wireless`, `BrokerClient`, `broker-android` |
| F-02 | 静默安装（无确认、不弹界面）：单 APK / 多 split / 归档格式，含社交软件追加的 `.apk.1` 后缀 | `install.rs`, `archive.rs`, `plan.rs`, `util.rs`, `FileNames` |
| F-03 | 静默卸载（含保留数据），从应用列表选择目标 | `uninstall.rs`, `AppPickerActivity`, `Uninstaller` |
| F-04 | 免确认包名白名单；安装前确认为可选开关（默认关闭） | `config.rs`, `SettingsActivity` |
| F-05 | 通知气泡显示安装中 / 进度 / 结果，一键启动、查看日志 | `Notifier`, `SilentTask` |
| F-06 | 安装日志：自动记录、历史查看、导出分享、清空 | `logbook.rs`, `InstallLogStore`, `LogActivity` |
| F-07 | **调用方授权**：首次调用由短时前台服务保活，直接在当前应用上方弹悬浮卡片询问；无悬浮窗权限时退化为可当场决策的横幅通知；设置页按用户/系统应用筛选并勾选 | `auth.rs`, `AuthPrompt`, `AuthForegroundService`, `Notifier`, `SettingsActivity`, `AppList` |
| F-08 | **无遮罩后台指示**：左上角科幻风旋转指示器，点击穿透、不染黑系统栏 | `FloatingIndicator`, `SciFiSpinner` |
| F-09 | **失败降级（默认不打断）**：只发一条可点击通知，点通知才进系统安装器 | `SilentTask`, `Notifier` |
| F-10 | **中文展示**：通知、日志、错误原因显示应用实际名称与中文说明 | `AppNames`, `apk_meta.rs`, `logbook.rs` |
| F-11 | **写入兜底**：stdin 流式写入失败时自动改用文件路径写入；中转文件由特权代理落到 `/data/local/tmp`（system_server 可读，避开 FUSE 域） | `install.rs`, `plan.rs`, `BrokerBridge`, `StagedFiles` |
| F-12 | **成功后删除安装包**：删除调用方原文件，读写权限不足时经代理以 shell 兜底并写日志 | `SilentTask`, `BrokerBridge` |
| F-13 | **关于页**：简介、可点击仓库地址、开发者、开源许可 | `AboutActivity` |

## 2. 支持格式与拉起方式

`AndroidManifest.xml` 对全部支持格式做了显式声明，`SilentInstallActivity` 统一接管。

| 扩展名 | 说明 | 处理方式 |
|---|---|---|
| `.apk` | 单个 APK | 直接建立会话 |
| `.apks` | Android App Bundle 导出的分片归档 | 按 zip 挑 `.apk` 条目，`base.apk` 优先 |
| `.xapk` / `.akp` | APKPure 等渠道的分片包 | 同上 |
| `.apkm` / `.apkmx` | APKMirror 等渠道的分片包 | 同上 |
| `.zip` | 包含多个 `.apk` 的通用归档 | 同上 |
| `*.apk.1` / `*.apk.2` / `*.apk.bin` … | 微信 / QQ 等社交软件转发时追加的备份后缀，文件本体仍是原安装包 | 先剥掉后缀，再按真实扩展名处理 |
| 多个 APK | 系统分享 / 文件管理器多选 | 合并为一次 multi-split 会话 |

> 社交软件转发会把 `xxx.apk` 改成 `xxx.apk.1`（也有 `.2`、多位数字、`.bin`、
> `.download` 等）以绕过系统安装器识别。`FileNames.normalize()` 与 Rust
> `util::normalize_name()` 在链路最前端统一剥掉这层后缀：`xxx.apk.1` 按单个
> APK 安装，`xxx.apks.1` 仍按归档拆分；staged 缓存文件名与通知 / 日志里的
> 显示名同步归一，不会出现 `xxx.apk.1.apk` 这类拼接结果。

拉起通道：

- **打开方式 / 安装**：`ACTION_VIEW`、`ACTION_INSTALL_PACKAGE`，匹配 `content://` 与
  `file://` + APK / 归档类 MIME，末尾用 `*/*` 兜底未知类型；另有独立的扩展名
  分组（含大写形式）与「备份后缀」分组（`.*\.apk\.1`、`.*\.apk\..*` 等），
  适配只给路径、不给 MIME 的调用方。
- **系统分享**：`ACTION_SEND` / `ACTION_SEND_MULTIPLE` 独立分组，不声明 scheme，
  `mimeType` 用 `*/*` 兜底，保证系统分享菜单与第三方应用分享都能拉起。
  入口会按显示名过滤明显无关的类型（图片 / 文本 / 音视频等），不会误触发安装。
- **卸载**：`ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` + `package:` URI。
- **应用内**：主界面「选择安装包并安装」支持 SAF 多选；「卸载应用」从已安装应用列表选择。

## 3. 架构

```
   用户（应用内：开启无线调试 → 通知栏输入 6 位配对码）
                    │  mDNS 发现 → TLS 配对 → ADB TLS → shell 启动
                    ▼
┌────────── broker-server：独立特权进程 ──────────────────────────┐
│  Rust + Tokio，启动即 getuid() 实测；非 shell(2000) 退出码 3     │
│  broker-core   状态机 / 调度 / 会话 / 维护清理                    │
│  broker-auth   挑战响应、nonce、时间窗口、撤销                    │
│  broker-exec   执行规格抽取（参数数组、stdin 校验）              │
│  broker-process 进程创建、超时 SIGKILL、stdout/stderr 分路        │
│  broker-ipc    abstract Unix socket / Unix path / TCP loopback    │
│  broker-protocol v2：4 字节大端长度前缀 + UTF-8 JSON             │
└───────────────────────▲──────────────────────────────────────────┘
                        │  帧协议 + 挑战响应认证
                        │  Hello → Challenge → Proof → AuthAck
┌───────────────────────┴────────── Android App ───────────────────┐
│  MainActivity / SettingsActivity / AppPickerActivity / AboutActivity│
│  LogActivity · SilentInstallActivity (零/短窗口入口)               │
│  WirelessPairingService · WirelessCore · ServiceStarter            │
│  broker-wireless：mDNS 后的 TLS / SPAKE2 / ADB TLS / shell 启动     │
│  AuthPrompt · AuthForegroundService · StagedFiles · StagedProvider │
│  TaskLauncher · SilentTask · FloatingIndicator · Notifier          │
│        │  RustCore.dispatch(method, argsJson) : String             │
│        ▼                                                          │
│  libinstaller_core.so (crates/android-ffi, JNI, Rust)              │
│        │  PlatformBridge.call(method, args) -> JSON                │
│        ▼                                                          │
│  installer-core (Rust, 纯逻辑, host 可单测)                       │
│   状态机 · 命令构造 · 会话流程 · 归档拆分 · 结果解析              │
│        │  BrokerBridge.call(...) 回调 Java                         │
│        ▼                                                          │
│  BrokerClient.java  帧协议客户端（LocalSocket / TCP）              │
│  BrokerBridge.java  I/O 胶水：ContentResolver / 文件 / 配置        │
│  InstallLogStore    本地 JSONL 日志                                │
└──────────────────────────────────────────────────────────────────┘
```

只有一个 JNI 入口、一个平台桥接原语，便于审计与测试。业务 core 的
`exec / stat / archiveEntries / readBytes / deleteSource / config…` JSON 契约在
v1 → v2 迁移中保持不变，只替换「特权状态 / 授权 / 执行」三个入口。
`broker-wireless` 与 `broker-server` 由 `android-ffi` 链接进 APK 的 native 库：
配对 / 启动桥与业务 JNI 入口共用同一个 `libinstaller_core.so`，Tokio 运行时只在
shell 身份的特权进程里启动。

## 4. 关键行为

### 静默与窗口

- 入口按「调用方是否等待结果」走两条路径：**不等待结果**（多数文件管理器 / 分享）在
  `onCreate` 内立即 `finish()`，framework 因 `mFinished=true` 跳过 `wm.addView`，
  完全零窗口；**等待结果**（`startActivityForResult` / `EXTRA_RETURN_RESULT`）用
  `@style/SilentEntryTheme` 驻留 1400ms，把结果回推过调用方在 MIUI 上的 1 秒
  「结果忽略窗口」，窗口被压到 1x1、全透明、不可聚焦、不可触摸、无 dim、系统栏透明。
- 延迟只作用于**结果回传**，后台安装在 `onCreate` 立即开始，通知与进度不等待。
- 主题刻意不设 `windowIsFloating`：floating + translucent 在部分 ROM 上会被铺成
  整屏半透明背板，是早期「一层透明遮罩」的来源。
- **安装链路零窗口**：失败降级默认只发通知，不自动 `startActivity`；指示器与授权卡片
  只用 `TYPE_APPLICATION_OVERLAY`，不会切换 task、不会 pause 前台应用。
- 「按返回才能继续操作」是顶层存在**可聚焦窗口**的确定信号，且该窗口未必属于本应用，
  排查时先看 `mCurrentFocus` 属主。完整定位过程见 [CHANGELOG.md](CHANGELOG.md) 的 v1.0.2。

### 调用方授权

- Rust `auth::decide` 返回 `ALLOW` / `ASK` / `DENY`：
  - `ALLOW`：立即后台安装，回传 `RESULT_OK`；
  - `ASK`：**跨应用悬浮卡片**叠在当前应用上方直接询问，允许后立即续装本次请求，
    入口回传 `RESULT_CANCELED` + `CALLER_PENDING`；
  - `DENY`：静默阻止、记一条日志，回传 `RESULT_CANCELED`。
- 卡片由 `AuthForegroundService`（`shortService`）承载：入口对普通调用会立即
  `finish`，进程随即降为 cached，异步申请完悬浮窗权限再 `addView` 往往来不及，
  国产 ROM 上表现为「弹窗没弹出来」。前台服务把进程钉在前台优先级，
  决策完成或 90 秒超时后立即 `stopForeground` + `stopSelf`，不做常驻。
- 拿不到悬浮窗权限时，先经自主通道以 shell 身份尝试开启 AppOps 权限；仍失败则退化为一条
  **带「允许并继续 / 拒绝」按钮的横幅通知**，按钮携带完整请求快照，
  点按即完成授权并续装本次请求，**不降级成需要切回本应用的普通页面**。
  通知正文点击才进入普通不透明授权页，全程不弹 Activity/Dialog 遮罩窗口。
- 匿名来源（识别不到调用方）**永远不能被永久记住**，否则一次允许会放行后续所有匿名调用。
- 设置页列出系统已安装应用：支持「全部 / 用户应用 / 系统应用」筛选、搜索、一键清空，
  **已授权应用置顶**并带绿色徽章，系统应用以独立徽章标出。

### 数据生命周期

- 外部 `content://` 的读权限只在本 Activity 存活期间有效，入口在仍持有权限时把安装包
  复制到应用私有缓存，由自家只读 `StagedProvider` 暴露；后台任务全程只读这个 URI。
- 调用方给的**原始 URI** 单独保留一份：开启「成功后删除安装包」时删的是原文件。
  `ContentResolver.delete` 常因只有读权限而失败，此时经代理以 shell 身份解析真实路径
  执行 `rm -f`（argv 数组，不经 shell 拼接），并把删除结果写入安装日志。
- 输入统一走 `content://`；`stat` / `archiveEntries` / `readBytes` 只做字节与元数据搬运，
  所有策略判断在 Rust。包名经 `is_valid_package_name` 校验，避免命令注入。
- 特权进程侧不接受 `Uri` / `ArchiveEntry` 形式的 stdin：客户端须先把内容落到绝对路径文件，
  再以 `StdinSpec::Path` 传入，避免把 Android 权限模型带进独立进程。
- 文件路径兜底的中转落点：外部缓存属于 FUSE 域（`u:object_r:fuse:s0`），shell(2000) 可读，
  但 `pm install-write <path>` 由 **system_server** 打开文件，会被 SELinux 拒绝（`avc: denied
  { read } ... tclass=file`，`pm` exit 255）。因此兜底时应用只把字节写到外部缓存，再由特权
  代理以 shell 身份复制到 `/data/local/tmp/prvbrk-stage/<随机目录>`：目录 `0711`、文件
  `0644`，system_server 可按路径读取，其它应用无法枚举目录；安装结束或失败立即整目录删除，
  目录按次隔离，失败当场回滚，不会误删并发的中间产物。
- 握手鉴权允许同一客户端并发：挑战表按 `(client_id, nonce)` 多槽保存并按 nonce 精确消费，
  客户端令牌只允许单写入者生成。状态轮询、安装各步骤、焦点快照各自新建连接时，后到的
  握手不会作废先到的挑战。

### 日志与失败处理

- Rust 核心在成功与失败两条路径都生成结构化记录（操作类型、应用名、包名/版本、
  分片数与大小、结果码、原始输出、耗时、来源、调用方），落到
  `filesDir/logs/install-log.jsonl`，保留最近 **500** 条，原子替换、统一 LF。
- 准备阶段失败、授权被拒由入口通过 `logRecord` 补记；日志写入是旁路，不影响安装结果。
- 通知标题显示应用实际名称（已安装应用名 > APK 内 `application@label` > 文件名），
  失败原因显示中文说明并保留原始错误码；日志同样以中文名作为标题。
- 代理链路失败区分「进程没运行」与「进程在跑但拒绝握手」：后者单独给出 `HANDSHAKE_REJECTED`
  状态与「重新激活（无需重新配对）」的指引，不再误报「服务未运行」；自动恢复以完整握手
  （而非端口监听）判定是否真的恢复，恢复成功后重试本次操作一次。
- 主界面不再展示「本次执行日志」，完整记录由独立的「安装日志」页承担。

## 5. 应用内启用特权代理（无需电脑）

普通应用无法自行获得 shell 身份，代理必须由设备上的无线调试以 shell(uid 2000)
启动一次。本应用把「启用无线调试 + 配对 + 启动」做成无需电脑的自助链路，
由 `broker-wireless`（Rust）与 `WirelessPairingService`（Java 前台服务）协作完成。

### 用户步骤

1. 主界面点「启用无线调试」→「去设置」，打开「开发者选项 → 无线调试」并开启开关。
2. 在无线调试页点「使用配对码配对设备」，记下系统显示的 6 位配对码。
3. 回到应用点「开始配对」；应用启动前台服务，通知栏出现常驻提示。
4. 下拉通知栏，直接在通知里输入 6 位配对码并发送。
5. 应用自动完成剩余步骤：注册 ADB 公钥、建立 ADB TLS 通道、以 shell 身份启动特权代理。
6. 返回应用点「刷新状态」，状态变为「已就绪」即可使用。

全程不需要电脑、USB 数据线或任何第三方特权框架。配对用 RSA 私钥保存在应用私有目录
`filesDir/adb_key.pk8`，已在备份规则中排除，不会随备份或云端同步外泄。
`broker-server` 启动即调用 `getuid()`，非 2000 以退出码 3 拒绝，不提供任何绕过开关。

### 链路分层

| 层 | 位置 | 职责 |
|---|---|---|
| 服务发现 | `WirelessPairingService` | mDNS 发现 `_adb-tls-pairing._tcp` 与 `_adb-tls-connect._tcp`，通知栏 RemoteInput 收配对码 |
| 密钥 | `broker-wireless/src/key.rs` | RSA 2048、ADB 公钥结构编码、TLS 客户端证书、认证签名 |
| 配对 | `broker-wireless/src/pairing.rs` | TLS 1.3 握手、密钥导出、SPAKE2 交换、AES-128-GCM 序列帧、PeerInfo 公钥注册 |
| 密码学 | `broker-wireless/src/spake2.rs`, `crypto.rs` | Edwards25519 SPAKE2、HMAC/HKDF-SHA256、GCM nonce 序列 |
| 传输 | `broker-wireless/src/adb.rs`, `tls.rs` | 24 字节小端 A_* 帧、`A_STLS` 升级、`A_AUTH` 回退、`shell:` 通道 |
| 编排 | `broker-wireless/src/orchestrate.rs`, `WirelessCore` | 单次 JNI 调用返回稳定 JSON（ok / stage / code / message） |
| 启动 | `ServiceStarter`, `broker-server` | `app_process` 以 shell 身份加载 native 库并阻塞运行 Tokio 服务 |

### 验证状态

沙箱内已验证：SPAKE2 与固定测试向量逐字节一致、HKDF 符合 RFC 5869、
本地 TLS 端到端配对与公钥注册、`A_STLS` 升级与 `shell:` 通道、帧协议与错误码。

**待设备验证**：真机的无线调试服务发现、通知栏配对码交互、设备端 TLS 实现差异、
`app_process` 加载 APK 内 native 库、SELinux 下的 abstract socket 连通性，
以及最终的安装链路。这些环节无法在沙箱中模拟，UI 与关于页已如实标注。

> 协议细节见 `crates/broker-protocol`，执行规格见 `crates/broker-exec`。

## 6. 构建

依赖：Rust stable + 4 个 Android target、cargo-ndk、Android SDK 36 / build-tools 36 /
NDK 27.2.12479018、JDK 17+、Gradle 8.14（wrapper 已包含）、Python 3 + Pillow。

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
bash scripts/build.sh
# 产物: PrivilegeBroker.apk
```

分步：

```bash
cargo test --workspace                           # 协议/认证/IPC/进程/状态机/端到端单测
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 \
    -o android/app/src/main/jniLibs build --release -p installer-android-ffi
cd android && ./gradlew assembleDebug
python3 scripts/gen_icons.py                     # 生成各密度图标资源（可复现）
python3 scripts/gen_axml_fixture.py              # 重新生成测试样本
```

**云编译已启用**：`.github/workflows/build.yml` 在 `push main` 或手动
`workflow_dispatch` 时构建，产物为 `PrivilegeBroker.apk`，并默认创建 GitHub Release
附上 APK（tag 形如 `v<version>-build.<run_number>`，依赖 `permissions: contents: write`，
由 runner 预装的 `gh` CLI 完成，不使用第三方 release action）。

### 签名一致性

`android/app/signing/release.keystore` 随仓库分发，`build.gradle.kts` 让 `debug`
（`scripts/build.sh` 走 `assembleDebug`）与 `release` 都显式指向它。因此**沙箱本地
构建与 GitHub Actions 云构建产出的 APK 证书逐字节一致**，同包名下可互相覆盖安装。

> `build.gradle.kts` 中的 `storePassword` / `keyAlias` / `keyPassword` 是该 keystore
> 内部的条目常量，必须与文件逐字一致；改动会令证书指纹变化。该密钥是**公开的自签名
> 密钥**，只用于保证构建可复现与版本间签名一致，不提供任何身份信任。
> v2.0.0 启用全新 applicationId，与历史版本包名不同：从任意旧版本升级都必须先卸载
> 旧包，授权列表与安装日志不会自动迁移。

## 7. 使用

1. 按「启动特权代理」一节在无线调试 shell 中启动一次代理进程，回主界面刷新状态。
2. 允许通知权限（进度气泡需要）。
3. 在「设置」里确认静默安装授权总开关，按用户 / 系统应用筛选并勾选允许发起静默安装的应用。
4. 从文件管理器用「打开方式 / 系统分享」把 APK 交给本应用：已授权来源直接后台执行；
   首次调用直接在当前应用上方弹卡片询问，允许后立即安装。
5. 安装期间停留在当前应用，通知栏显示进度，屏幕左上角有小旋转指示器；不跳主界面。
6. 结果更新同一条通知；成功可一键启动，失败显示中文原因并可点通知改用系统安装器。
7. 「卸载应用」从应用列表选择，可筛选系统应用，确认时提示风险；卸载意图
   （`package:` URI）同样静默执行。
8. 打开「安装日志」查看每一次操作的时间、应用名、结果、耗时与原因，可导出或清空。

## 8. 兼容性

| 平台要求 | 处理 |
|---|---|
| Android 16 (API 36) | targetSdk 36；`android:exported` 显式声明；16KB page size |
| Android 15 (API 35) | `fitsSystemWindows` + 系统栏配色；小窗口避开状态栏 |
| Android 14 (API 34) | 授权询问用 `shortService` 前台服务保活；`PendingIntent.FLAG_IMMUTABLE`；FGS type 与 manifest 声明一致 |
| Android 13 (API 33) | `POST_NOTIFICATIONS` 动态申请；通知渠道；themed icon |
| Android 11+ (API 30) | 无线调试；`<queries>` 包可见性；`QUERY_ALL_PACKAGES` 用于枚举应用；全量 SAF |
| Android 10+ (API 29) | 后台启动 Activity 受限，指示器与授权卡片改用系统悬浮窗 |
| Android 8+ (API 26) | `TYPE_APPLICATION_OVERLAY`；adaptive icon + 单色图标 |

`SYSTEM_ALERT_WINDOW` 只用于可选的进度指示器与跨应用授权卡片，可经自主通道以 shell
身份尝试开启；未授予时退化为纯通知反馈。`QUERY_ALL_PACKAGES` 只用于设置页与卸载选择器
展示应用列表，不上传、不持久化到任何远端。

## 9. 目录

```
Cargo.toml                  Cargo workspace（12 个 crate）
crates/core/                纯 Rust 业务逻辑 installer-core + 单元测试
crates/android-ffi/         JNI cdylib installer-android-ffi
crates/broker-protocol/     协议 v2、帧格式、错误码、会话
crates/broker-auth/         挑战响应、nonce、常量时间比较
crates/broker-exec/         执行规格与结果模型
crates/broker-process/      进程引擎、超时与取消
crates/broker-ipc/          传输层与帧读写
crates/broker-android/      uid / SELinux / 端点派生 / 启动参数
crates/broker-core/         Tokio Broker 服务状态机
crates/broker-client/       Rust 客户端 SDK 与端到端测试
crates/broker-server/       独立特权进程入口
android/                    Gradle 工程（Java 胶水、Manifest、资源）
android/app/signing/        固定签名密钥（本地与云编译共用）
icons/ scripts/             图标素材与构建 / 资源生成脚本
.github/workflows/          CI（云编译并发布 Release）
CHANGELOG.md                版本更新日志
LICENSE                     GPL-3.0
```

## 10. 许可

本项目以 **GNU General Public License v3.0（GPL-3.0-only）** 发布，完整条款见 `LICENSE`。
图标素材与生成的图形资源随仓库一同提供，同样适用该许可。
