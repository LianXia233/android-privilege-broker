# 更新日志

本文件记录各版本的可见变更。按时间倒序排列，版本号与 `android/app/build.gradle.kts`
中的 `versionName`、`Cargo.toml` 中的 `version` 一致。

---

## v2.0.0

### 架构：迁移到自主 ADB-only 特权代理

- **移除第三方特权框架**：删除旧桥接类，不再引入任何第三方 SDK / AIDL /
  Provider / 专属权限。Manifest 去掉对应权限声明、包可见性条目与 provider；
  Gradle `dependencies` 清空；proguard 规则只保留 JNI 入口。
- **新增 Rust Tokio Broker（11 个 crate）**：`broker-protocol`（协议 v2）、
  `broker-auth`（挑战响应）、`broker-exec`（执行规格）、`broker-process`（进程引擎）、
  `broker-ipc`（传输层）、`broker-android`（平台实测）、`broker-core`（服务状态机）、
  `broker-client`（客户端 SDK）、`broker-server`（独立特权进程入口）。
- **只有 ADB Shell 一条后端**：`BackendKind` 仅 `AdbShell` / `Unknown`，
  `AdbShell.expected_uid() == 2000`。`broker-server` 启动时调用 `getuid()` 实测，
  非 shell(2000) 立即以退出码 3 拒绝；uid 0 判为 `Unknown`，不作为可用后端。
  代码、协议、状态引导与文档均不再提供 root 启动路径。
- **自主通信协议 v2**：4 字节大端长度前缀 + UTF-8 JSON；
  `Hello → Challenge → ChallengeProof → AuthAck → 业务请求` 状态机；
  SHA-256 域分隔挑战证明与常量时间比较；nonce 防重放、时间窗口、会话 TTL、
  撤销与多客户端隔离。
- **IPC 承载**：Linux/Android abstract Unix socket、文件系统 Unix socket（host 测试）、
  TCP loopback（配合端口转发）。帧读写含超大帧、截断帧、分片/粘包处理。
- **进程执行**：`tokio::process::Command` 参数数组执行，不拼接 shell 字符串；
  清除 `LD_PRELOAD` / `LD_LIBRARY_PATH` / `LD_AUDIT`；stdin 支持字节流与文件路径，
  stdout/stderr 限长分路；超时与取消通过 `SIGKILL` 回收。
- **Java 侧**：新增 `BrokerBridge.java`（Rust JNI 回调目标，保留原有业务 I/O 契约）
  与 `BrokerClient.java`（帧协议、挑战响应、LocalSocket / TCP 通道）。
  业务 Rust core 的 `exec / stat / archiveEntries / readBytes / deleteSource / config…`
  JSON 契约保持不变，只替换特权状态、授权、执行三个入口。
- **启动方式**：用户在「开发者选项 → 无线调试」用 adb 连接设备后，
  按主界面提示执行一次启动命令；主界面提供「复制命令」按钮，
  `requestAuthorization` 改为连接探测 + 状态刷新。

### 命名、图标与构建

- **启用全新 applicationId 与仓库**：`io.github.lianxia233.privbroker` 用于
  applicationId、Java 包路径、JNI 导出符号与 `pm --installer-package-name`；
  仓库名、crate、native 库、APK 与签名条目全部使用中性命名，不含第三方品牌字样。
  v2.0.0 与历史版本包名不互通，安装前需卸载旧包，授权与日志数据不迁移。
- Rust crate 改名：`installer-core` / `installer-android-ffi`，
  native 库为 `libinstaller_core.so`，Java 同步加载 `installer_core`。
  签名 keystore 条目改为中性名称，私钥与证书链保持不变。
- **应用图标替换为用户提供的插画**：圆形 alpha 蒙版裁掉四角，
  legacy / round / adaptive 前景 / backdrop 四套 mipmap 全部重生成；
  adaptive 背景色由素材采样得到 `#DDCAF3`。`scripts/gen_icons.py` 重写为可复现脚本，
  重跑后 20 个 PNG 逐字节一致。
- 构建链同步：`scripts/build.sh` 改用 `cargo test --workspace`、
  `installer-android-ffi` 与 `PrivilegeBroker.apk`，并清理旧命名 `.so`；
  CI 产物名、artifact 名与 Release 标题同步更新；`Cargo.toml` 补回
  `[profile.release]`；`rootProject.name` 改为 `PrivilegeBroker`。
- 开源组件清单补充 Tokio、libc、sha2、hex；协作规范同步 ADB-only 描述。

### 兼容与验证

- **签名证书更新为中性主题**：`release.keystore` 重新生成，证书主题、条目名与
  口令均不含第三方品牌字样；该文件随仓库分发，本地与云构建产物证书一致。
  因 v2.0.0 更换包名，与历史版本不再互通，需卸载旧包后安装。
- 沙箱内 `cargo test --workspace` 通过（136 项），业务核心单独复测 55 项通过。
- 需设备环境验证：无线调试启动 Broker、shell uid 2000 实测、SELinux 下的
  abstract socket / TCP loopback 连通性、真机 `pm install-create/write/commit`
  与卸载。上述能力标记为「待设备验证」，不标记为已通过。
- **运行时缺陷修复**：Java 客户端 nonce 改为进程级递增（原先每次连接重置为 1，
  会被服务端按 client 全局去重的防重放机制拒绝）；删除 `--allow-any-uid` 启动参数，
  uid 2000 硬闸门不可绕过；修复 `requestAuthorization()` 未声明 `JSONException`
  导致的 Java 编译失败。
- **产物验证**：`PrivilegeBroker.apk` v2 签名、四 ABI 全部 `Align 0x4000`、
  JNI 符号导出正确；签名证书主题与 dex、资源中均无第三方品牌残留。

---

## v1.0.4

### 新增

- **支持社交软件追加后缀的安装包**：微信 / QQ 等转发 APK 时常把
  `xxx.apk` 改成 `xxx.apk.1`（也有 `.2`、多位数字、`.bin`、`.download` 等），
  文件本体仍是原 APK。现在 `FileNames.normalize()` 与 Rust
  `util::normalize_name()` 会在链路最前端剥掉这层后缀，
  `xxx.apk.1` 按单个 APK 安装，`xxx.apks.1` 仍按归档拆分；
  staged 缓存文件名、通知 / 日志显示名同步归一，不再出现 `xxx.apk.1.apk`。
- **声明备份后缀**：`AndroidManifest.xml` 新增独立的扩展名分组，
  显式声明 `.*\\.apk\\.1` / `.*\\.APK\\.1` 及全部格式的 `.<后缀>` 通配；
  VIEW 组补 `*/*` MIME 兜底，覆盖社交软件把安装包标成未知类型的情形。
  入口仍按归一化后的显示名过滤图片 / 文本 / 音视频等无关文件。
- **授权弹窗改由短时前台服务承载**：新增 `AuthForegroundService`
  （`shortService`）。入口 `SilentInstallActivity` 对普通调用会立即 `finish`，
  进程随即降为 cached，异步申请完 `SYSTEM_ALERT_WINDOW` 再 `addView` 往往
  来不及，国产 ROM 上表现为「弹窗根本没弹出来」。前台服务把进程钉在前台
  优先级，悬浮卡片因此能稳定浮在当前应用之上；用户决策或 90 秒超时后
  立即 `stopForeground` + `stopSelf`，通知同步移除，不做任何常驻。
- **横幅通知可当场决策**：悬浮窗权限拿不到时退化为一条 heads-up 通知，
  带「允许并继续 / 拒绝」两个按钮，按钮通过前台服务携带完整请求快照
  （staged URI + 原始 URI + 调用方），点按即完成授权并续装本次请求，
  无需切回本应用；不再降级成只有「待授权」文字的旧式通知。

### 优化

- **设置页应用列表不再被压缩**：「授权与安装行为」的五个开关与「高级白名单」
  改为默认折叠，应用授权列表卡片 `weight=1` 占满剩余空间，内部列表再加
  `minHeight=180dp` 兜底，任何屏幕下都能显示多条并正常滚动。
- **关于页开源许可结构化**：从两段纯文本改为逐项列出实际使用的开源组件
  （serde、serde_json、thiserror、jni、Tokio、Rust 标准库、Android SDK /
  AGP），每项标注许可、用途，点击可跳转上游仓库；`Cargo.toml` 的 `license`
  与 `repository` 修正为真实值，删除不准确的描述。
- **本地与云编译签名统一**：`android/app/signing/release.keystore` 随仓库分发，
  `build.gradle.kts` 让 `debug`（`scripts/build.sh` 走 `assembleDebug`）与
  `release` 都显式指向它。此前两端各自使用随机生成的 `~/.android/debug.keystore`
  （实测证书 SHA-256 分别为 `a38042a6…` 与 `61e339a9…`），产物无法互相覆盖安装。
  该密钥为公开自签名密钥，仅用于保证构建可复现与版本间签名一致。

### 注意

- v1.0.4 起签名证书变更，**从 v1.0.3 及更早版本覆盖安装会提示签名不一致**，
  需先卸载旧版再安装；此后各版本签名保持一致，可正常覆盖升级。

---

## v1.0.3

### 新增

- **跨应用授权卡片**：外部应用首次发起静默安装时，以系统悬浮窗直接叠在**当前应用上方**
  弹出「允许 / 拒绝」，用户不必切回本应用；点「允许」后立即续装本次请求。
  卡片使用 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`、无 dim，卡片外触摸全部穿透。
  拿不到悬浮窗权限时先经特权通道以 shell 身份自动授权，仍失败才回退为「待授权」通知。
- **关于页**：应用简介、可点击跳转的开源仓库地址、开发者信息与 GPL-3.0 开源许可说明。
- **卸载应用选择器**：主界面「卸载应用」改为读取已安装应用列表选择，不再要求手输包名；
  保留「输入包名」兼容入口。
- **应用列表筛选**：设置页授权列表与卸载选择器均支持
  「全部 / 用户应用 / 系统应用」筛选，系统应用以独立徽章与警示文案标出。
- **声明全部支持格式与系统分享**：`AndroidManifest.xml` 显式声明
  `.apk` / `.apks` / `.xapk` / `.akp` / `.apkm` / `.apkmx` / `.zip`（含大写），
  MIME 组覆盖 `ACTION_VIEW` / `INSTALL_PACKAGE`，独立的 `ACTION_SEND` /
  `SEND_MULTIPLE` 分组用 `*/*` 兜底，保证系统分享菜单与第三方应用分享都能拉起。
  入口按显示名过滤明显无关的文件类型，避免误分享触发安装。
- `CHANGELOG.md`：把原先散落在 README 里的版本演进与实机定位记录剪切归档。

### 优化

- **设置页排版重做**：标题栏加返回；五个开关改为「短标题 + 小字说明」两行结构，
  避免长文案换行撑高卡片；应用授权卡片独立占满剩余空间，筛选、搜索、计数、提示分区清晰。
- **已授权应用置顶**：授权列表排序改为「已授权 → 用户应用 → 系统应用 → 名称」，
  已授权项显示绿色徽章，切换开关后立即重新归组。
- **成功后删除安装包可用**：外部调用路径把调用方给的原始 URI 一路传到后台任务，
  开启开关后删除的是**原文件**而非本应用缓存副本；ContentResolver 删除失败时
  由特权通道以 shell 身份解析真实路径执行 `rm -f`，并写入日志说明删了几个、有无失败。
  悬浮卡片授权与通知授权页两条续装路径同样携带原始 URI。
- 主界面移除「本次执行日志」区域，完整记录统一由独立的「安装日志」页承担。
- 新增 `.apkm` / `.apkmx` 归档输入支持，归档分支统一按 zip 挑 `.apk` 条目。

---

## v1.0.2

### 修复：调用方透明弹窗与黑色系统栏（2026-11-07 二次实机定位）

早期版本曾把根因误判为「没有回传 `RESULT_OK`」。1.0.1 实机复测证明
`setResult(RESULT_OK)` 已执行（`finish result=-1 detail=ACCEPTED`，往返 2ms），
弹窗依旧，故该结论作废。以下为修正后的根因与方案。

- 现场证据（`dumpsys window` / `dumpsys activity activities` / `uiautomator dump` /
  `logcat`）：`mCurrentFocus` 是**调用方**窗口（Mihon
  `ExtensionInstallActivity`），`fmt=TRANSPARENT`、全屏、视图树只有空的
  `android:id/content`、零 `clickable`；`state=RESUMED finishing=false` 停留数分钟；
  `taskDescription.statusBarColor=ff000000` 且窗口缺
  `DRAWS_SYSTEM_BAR_BACKGROUNDS` → 黑系统栏、触摸被吞、只能按返回键退出。
- 真根因在调用方源码：检测到 `hasMiuiPackageInstaller` 时，`onCreate` 设
  `ignoreResult=true` 与 `ignoreUntil = System.nanoTime() + 1s`；`onActivityResult`
  若在 1 秒内到达，执行 `hasIgnoredResult = true; return` —— **不调用 `finish()`**，
  且判断发生在 resultCode 之前，`RESULT_OK` / `RESULT_CANCELED` 一律被吞。
  它只靠后续 `onStart()` 里的 `if (hasIgnoredResult) finish()` 兜底。
- 零窗口安装器 2ms 内结束，调用方在结果派发时已走完 `onStart/onResume`，此后再无
  `onStart` → 透明窗口永久停留。系统安装器之所以正常，是因为它显示真实界面让调用方
  进入 stopped，返回时 `onStart()` 会兜底 finish。
- 修复：把结果回推到忽略窗口之后。`RESULT_DELAY_MS = 1400ms` 留出 MIUI 调度余量，
  使 `onActivityResult` 走 `checkInstallationResult(resultCode); finish()` 正常分支。
  等待结果的调用方改用 `@style/SilentEntryTheme` 短窗口驻留，窗口在
  `onCreate` / `onResume` / `addView` 后三次申明为 1x1、全透明、
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`、无 dim、系统栏透明。
  普通调用（不等待结果）仍是零窗口。后台安装不等待延迟，在 `onCreate` 立即开始。
- 结论：**零窗口与正确回传结果都只是必要条件**。被 `startActivityForResult` 拉起的
  安装器还必须适配调用方自身的结果处理时序，否则「透明弹窗」会以调用方窗口的形式
  出现，且单改 resultCode 无效。

### CI

- GitHub Actions 第一方 action 升级到声明 `node24` 运行时的版本：
  `actions/checkout@v5`、`actions/setup-java@v5`、`actions/upload-artifact@v6`
  （注意 `upload-artifact@v5` 仍是 node20）。
- 每次 `push main` / `workflow_dispatch` 构建完成后**默认创建 GitHub Release 并附上 APK**，
  tag 形如 `v<version>-build.<run_number>`，由 runner 预装的 `gh` CLI 完成，
  重跑同一 run 改为 `--clobber` 覆盖同名资产。

---

## v1.0.1

- 回传安装结果（`setResult` + `EXTRA_RESULT_DETAIL`），消除调用方透明中转窗口残留。
- 入口改用 framework `Theme.NoDisplay`，安装链路零窗口；失败降级默认只发通知，
  不再自动 `startActivity` 拉起系统安装器（可显式开启，带风险提示）。
- 调用方授权：外部应用未获授权时只发一条「待授权」通知，安装过程中不弹任何窗口；
  设置页可勾选允许哪些应用，匿名来源永不记住。
- 左上角科幻风旋转指示器、中文应用名与错误文案、写入失败自动兜底为文件路径写入。
- 以 **GPL-3.0** 发布；启用 GitHub Actions 云编译产出 Release APK。

---

## v1.0.0

- 首个可用版本：Rust 核心 + JNI + Java 胶水，以 shell 身份执行
  `pm install-create / install-write / install-commit` 会话。
- 静默安装单 APK / 多 split / `.apks` / `.xapk`、静默卸载、免确认白名单、
  通知进度与结果、安装日志（记录 / 导出 / 清空）、四 ABI 与 16KB 页对齐。
