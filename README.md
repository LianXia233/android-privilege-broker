<p align="center">
  <img src="icons/app_icon_source.png" width="128" height="128" alt="PrivilegeBroker Logo" style="border-radius: 26px; box-shadow: 0 8px 24px rgba(0,0,0,0.15);" />
</p>

<h1 align="center">Android Privilege Broker</h1>

<p align="center">
  <b>⚡ 基于 Rust 核心与自主 ADB 特权代理的高性能 Android 静默安装与生命周期套件 ⚡</b><br/>
  <i>零感交互 · 免电脑无线自启 · 16KB 内存页对齐 · 跨应用弹性授权 · FlClash 灵动设计 · 原子审计日志</i>
</p>

<p align="center">
  <a href="https://github.com/LianXia233/android-privilege-broker/releases/latest"><img src="https://img.shields.io/badge/Release-v2.2.2-blue.svg?style=flat-square&logo=android" alt="Release Version" /></a>
  <a href="https://github.com/LianXia233/android-privilege-broker/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/LianXia233/android-privilege-broker/build.yml?branch=main&style=flat-square&logo=github&label=Build" alt="CI Status" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-orange.svg?style=flat-square" alt="License" /></a>
  <a href="https://www.rust-lang.org/"><img src="https://img.shields.io/badge/Rust-2021%20(12%20Crates)-DEA584.svg?style=flat-square&logo=rust&logoColor=white" alt="Rust" /></a>
  <a href="https://developer.android.com/about/versions/16"><img src="https://img.shields.io/badge/Android-minSdk%2033%20%7C%20targetSdk%2036-3DDC84.svg?style=flat-square&logo=android&logoColor=white" alt="Android SDK" /></a>
  <img src="https://img.shields.io/badge/Page%20Size-16KB%20Aligned-success.svg?style=flat-square" alt="16KB Aligned" />
  <img src="https://img.shields.io/badge/Backend-ADB%20Shell%20(uid%202000)-8A2BE2.svg?style=flat-square" alt="Backend" />
  <img src="https://img.shields.io/badge/UI%20Style-FlClash%20%2F%20Prism%20M3-007AFF.svg?style=flat-square" alt="UI Style" />
  <img src="https://img.shields.io/badge/Themes-Light%20%7C%20Dark%20%7C%20AMOLED-000000.svg?style=flat-square" alt="Themes" />
</p>

<p align="center">
  <a href="#-项目基本概览">📌 项目概览</a> •
  <a href="#-核心亮点">✨ 核心亮点</a> •
  <a href="#-界面设计与开源致谢">🎨 界面美学</a> •
  <a href="#-快速开始无需电脑">🚀 快速开始</a> •
  <a href="#-四大核心页面与独立交互架构">📱 页面架构</a> •
  <a href="#-系统架构">🏗️ 系统架构</a> •
  <a href="#-五级阶梯安装包自动清理机制">🧹 自动清理</a> •
  <a href="#-支持格式与交互协议">📦 支持格式</a> •
  <a href="#-深度工程机制与真机踩坑">🔬 深度机制</a> •
  <a href="#-编译构建与签名">🛠️ 编译构建</a> •
  <a href="#-仓库工程结构">📂 仓库结构</a> •
  <a href="CHANGELOG.md">📝 更新日志</a>
</p>

---

## 📌 项目基本概览

| 维度 | 规格 / 说明 |
| :--- | :--- |
| **应用包名** | `io.github.lianxia233.privbroker`（v2.0.0 起启用全新包名，与历史版本不互通） |
| **系统兼容** | minSdk **33**（Android 13+）/ targetSdk **36**（Android 16）/ compileSdk **36**，核心机制覆盖向下适配 |
| **特权边界** | 自主 Broker，**ADB Shell / uid 2000 唯一特权后端**；零 Root 依赖，不引入任何第三方特权框架 |
| **架构组成** | 纯业务逻辑在 `installer-core`（Rust，host 可单测）；特权执行在独立 `broker-server`（Rust + Tokio，12 个 Workspace Crate）；无线配对在 `broker-wireless`；原生 Android 现代化交互层 |
| **界面体系** | 参考 [FlClash (prism_ui)](https://github.com/chen08209/FlClash) 交互设计：M3 动态 Tonal 四层表面、Squircle 连续圆角卡片、单 Activity 悬浮胶囊 Dock、AMOLED 纯黑夜间模式、滑动与淡入切页动效 |
| **产物交付** | 全 ABI 支持（`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`）、**16KB 内存页对齐**、v1+v2 可复现构建签名 |
| **开源许可** | **GNU General Public License v3.0（GPL-3.0-only）**，第三方与设计参考归属详见 [NOTICE](NOTICE) 与 [LICENSE](LICENSE) |
| **当前版本** | **2.2.2**（versionCode 28，build 38）；完整演进历史参见 [CHANGELOG.md](CHANGELOG.md) |

---

## ✨ 核心亮点

| ⚡ 真正的零感静默 | 📡 免电脑自主配对 | 🛡️ 跨应用弹性授权 |
| :--- | :--- | :--- |
| **不弹界面 · 1x1 隐形窗口适配**<br/>杜绝国产 ROM 灰色蒙层遮罩，真正实现 0ms 零弹窗静默安装 | **mDNS 自动发现 + TLS 1.3 握手**<br/>无需电脑或数据线，通知栏一键输入配对码完成自主激活 | **shortService 短时前台保活**<br/>高优先级卡片决策；未授权悬浮窗时自动降级为可交互通知横幅 |

| 🎨 FlClash 灵动美学 | 📊 实时性能与内存看板 | 🧹 五级安全自动清理 |
| :--- | :--- | :--- |
| **M3 四层表面 · Squircle 卡片**<br/>悬浮胶囊 Dock · 弹性动效 · AMOLED 纯黑夜间模式，兼具美感与极低功耗 | **应用 PSS + Java 堆 3 秒刷新**<br/>14 天 Sparkline 走势图，配套 1GB 满格可视化缓存清理卡片 | **应用侧 ➔ Shell 路径 ➔ 媒体库 ➔ 哈希寻回**<br/>严格比对文件体积与 SHA-256 指纹，确保彻底清理且绝不误删 |

| 📦 全格式与恶意后缀还原 | 🔒 安全隔离与全量审计 | 🦀 模块化 Rust 特权后台 |
| :--- | :--- | :--- |
| **APKS / XAPK / 分片通用归档**<br/>链路前端自动剥离 `.apk.1` 等社交软件备份后缀，防拦截还原真实安装 | **Shell uid 2000 权限严格隔离**<br/>本地原子 JSONL 结构化日志，展开呈现完整全景元数据与诊断详情 | **12 个 Workspace Crate 模块化解耦**<br/>Tokio 异步引擎、动态 Nonce 时间窗口鉴权与端点故障自愈重拉 |

### 功能清单 (Feature Matrix)

| 编号 | 核心功能 | 说明与技术特性 | 实现组件 / 源码位置 |
| :---: | :--- | :--- | :--- |
| **F-01** | **应用内无线自启** | 设备内自主启用无线调试与配对，**全程无需电脑或 USB 线** | `broker-wireless`, `WirelessPairingService`, `PairingActivity` |
| **F-02** | **零感静默安装** | 单 APK / 多 split / 归档分片包，自动处理社交软件追加的 `.apk.1` 等备份后缀 | `installer-core/install.rs`, `archive.rs`, `FileNames.java`, `SilentInstallActivity.java` |
| **F-03** | **静默安全卸载** | 支持保留数据卸载，提供已安装应用列表选择与高危系统应用二次警示 | `installer-core/uninstall.rs`, `AppPickerActivity.java`, `Uninstaller.java` |
| **F-04** | **免确认白名单** | 允许为可信调用方设置免确认白名单；安装前确认弹窗支持全局开关与独立应用管理 | `crates/core/src/config.rs`, `SettingsPage.java`, `AllowAppsActivity.java` |
| **F-05** | **动态通知交互** | 通知栏动态气泡展示安装进度与结果，支持一键拉起新应用或跳转查看日志 | `Notifier.java`, `SilentTask.java` |
| **F-06** | **结构化安装日志** | 本地自动记录安装时间、应用名、分片数、耗时、原始输出，展开详情呈现全景结构化元数据 | `logbook.rs`, `InstallLogStore.java`, `LogsPage.java` |
| **F-07** | **调用方弹性授权** | 首次调用由短时前台服务保活拉起悬浮卡片；无悬浮权限时自动退化为可交互横幅通知 | `auth.rs`, `AuthPrompt.java`, `AuthForegroundService.java`, `CallerAuthActivity.java` |
| **F-08** | **轻量后台指示器** | 屏幕左上角科幻风轻量旋转指示器，全屏点击穿透、不染黑系统状态栏 | `FloatingIndicator.java`, `SciFiSpinner.java` |
| **F-09** | **优雅失败降级** | 安装失败时默认仅发送一条低打扰通知，仅在用户点击时才交接给系统安装器 | `SilentTask.java`, `Notifier.java` |
| **F-10** | **本地化中文展示** | 通知、日志、错误堆栈精准显示 APK 内部 Label 中文名与易读错误原因 | `AppNames.java`, `apk_meta.rs`, `logbook.rs` |
| **F-11** | **双模写入与安全中转** | stdin 流式写入失败自动兜底为路径写入；中转落盘规避 FUSE 域 SELinux 拦截 | `install.rs`, `BrokerBridge.java`, `StagedFiles.java` |
| **F-12** | **安装后自动清理** | 暂存阶段采集路径/大小/SHA-256，按「应用侧 ➔ shell 路径 ➔ content query ➔ content delete ➔ 内容指纹」五级阶梯执行，强校验绝不误删 | `SourceFacts.java`, `SilentTask.java`, `BrokerBridge.java` |
| **F-13** | **关于与合规信息** | 提供完整构建版本、证书指纹、源码仓库直达与 GPL-3.0 许可说明 | `AboutPage.java`, `NOTICE`, `LICENSE` |
| **F-14** | **FlClash 风格界面** | 复刻 FlClash（prism_ui）设计系统：同源 M3 色板、四级表面、Squircle 卡片、悬浮胶囊 Dock、滑动 / 淡入切页、弹性按压与弹簧开关 | `Prism.java`, `NavigationDock.java`, `PageSwitcher.java`, `PrismSwitch.java`, `SquircleDrawable.java` |
| **F-15** | **实时内存看板** | 仪表盘展示应用 PSS、Java 堆占用单行数值与进度条（3 秒刷新）；缓存卡支持一键即时清理与 1GB 满格可视化 | `DashboardPage.java`, `Sparkline.java`, `ic_action_memory.xml` |
| **F-16** | **主题属性与纯黑模式** | 抽象 16 项 Theme Attribute，支持浅色、深色及针对 OLED 屏幕优化的 AMOLED Pure Black 纯黑色板 | `Prism.java`, `pb_palette.xml`, `attrs.xml`, `themes.xml` |
| **F-17** | **逐应用独立授权** | 独立管理已授权应用，支持即时模糊搜索、全部 / 用户 / 系统应用分类筛选与一键弹簧动效撤销/授予 | `AllowAppsActivity.java`, `item_app.xml`, `PrismSwitch.java` |

---

## 🎨 界面设计与开源致谢

本项目自 `v2.2.0` 起全面重构前端视觉体验，界面、配色方案与交互动效深度参考了优秀开源代理客户端 [FlClash](https://github.com/chen08209/FlClash) 及其核心设计子模块 [prism_ui](https://github.com/chen08209/prism_ui) （均为 GPL-3.0 协议）。

| 设计维度 | 技术规范与原生重构实现 |
| :--- | :--- |
| **🌈 M3 动态色板** | 基于 Google 官方 `material-color-utilities` 离线算法（通过 `scripts/gen_palette.py`）计算饱和强调色，输出亮暗两套精美色板 |
| **📐 连续曲率圆角** | 自研 `SquircleDrawable` 基于超椭圆公式离散采样，带来符合现代人机工学的连续平滑圆角卡片视觉 |
| **🛸 灵动胶囊 Dock** | 悬浮与停靠双形态自由切换，选中项提供 16% 镜头放大感与 260ms 物理过冲回弹动画 |
| **🌌 AMOLED 纯黑** | 专为 OLED / AMOLED 屏幕打造的深邃纯黑（Pure Black）表面色板，极致纯粹且大幅降低屏幕功耗 |
| **💫 灵动切页动效** | 支持 300ms easeOut 整页位移滑动与 220ms easeInOut 原地交叉淡入双模平滑切换 |

> [!NOTE]
> **设计与代码实现说明**：
> - 本项目**未链接亦未分发任何 FlClash / prism_ui 的 Dart 或 Flutter 代码**，而是提炼其设计令牌（Design Tokens，包括色板生成规则、四级表面深度、字号阶梯、弹性按压阻尼与转场时长），以 Android 原生 View、自定义 Drawable 及属性动画体系从零重写。
> - 配色算法源自 Google 官方的 [material-color-utilities](https://github.com/material-foundation/material-color-utilities) （Apache-2.0），开发期通过其 Python 端口 [materialyoucolor](https://pypi.org/project/materialyoucolor/) （MIT）离线生成静态色板（参见 `scripts/gen_palette.py`），色值随源码分发。完整致谢与许可归属详见 [NOTICE](NOTICE)。

---

## 📱 四大核心页面与独立交互架构

应用采用现代化 **Single-Activity + 常驻多页挂载 + 悬浮胶囊 Dock** 架构。各核心功能页面常驻内存，切换无闪烁、无状态丢失，极致轻快流畅：

| 页面组件 | 核心定位 | 关键能力与交互细节 |
| :--- | :--- | :--- |
| **📊 仪表盘 (`DashboardPage`)** | 状态总览与日常控制 | 状态胶囊、一键激活、安装模式分段滑块、内存监控（PSS/Java 堆）、14 天走势图 |
| **📜 审计记录 (`LogsPage`)** | 全量记录与审计导出 | 安装/卸载/激活分段筛选、结构化全景元数据展开、控制台原始日志、一键导出 |
| **⚙️ 通用设置 (`SettingsPage`)** | 个性化与行为策略 | 浅色/深色/纯黑 AMOLED、滑动/淡入切页、确认弹窗、安装后自动清理、鉴权总控 |
| **ℹ️ 关于本软 (`AboutPage`)** | 版本溯源与合规公示 | 版本号、构建序列、SHA-256 签名证书指纹、开源代码仓库直达与完整开源许可 |
| **🛡️ 逐应用授权 (`AllowAppsActivity`)** | 跨应用授权管理 | 独立全屏界面、即时模糊搜索、全部/用户/系统应用分类、PrismSwitch 弹性弹簧开关 |
| **📡 无线配对 (`PairingActivity`)** | 无线调试专属引导 | 步骤指引、mDNS 服务自动嗅探、配对端口监听与配对码即时提交 |
| **🗑️ 应用选择器 (`AppPickerActivity`)** | 安全静默卸载入口 | 已安装应用完整枚举、系统核心应用二次确认警示、保留数据卸载支持 |
| **⚡ 零感静默中枢 (`SilentInstallActivity`)** | 外部调用零感入口 | 0ms 立即 finish() 零窗口执行、1x1 隐形浮窗避让 MIUI 结果忽略窗口 |

### 1. 仪表盘 (DashboardPage)
- **Hero 状态横幅**：顶部大圆角 Hero 卡片集成状态胶囊（未激活 / 检测中 / 已就绪）、24sp 醒目标题与 84dp 强交互主操作按钮（一键激活服务或开始配对）；
- **安装模式三选一**：基于 `SegmentedControl` 平滑滑块提供「智能挑选（默认推荐）」、「全部安装」与「仅基础包」三种安装分段策略；
- **实时内存看板**：3 秒定时采样展示应用实际物理内存（PSS）与 Java 堆占用量，配备单行清晰数值与百分比进度指示条；
- **缓存卡片与维护**：实时统计当前应用暂存中转与图片缓存大小，提供一键即时清理入口，进度条按 1GB 满格比例直观呈现；
- **14 天动态走势**：内置 `Sparkline` 贝塞尔平滑迷你面积图，直观展示最近两周的安装频次与成功率；
- **最近安装列表**：卡片式收拢展示最近执行的安装任务，支持快速重试或直达详情。

### 2. 审计记录 (LogsPage)
- **多维度分段筛选**：支持按「全部」、「安装」、「卸载」及「服务激活」快速归类过滤；
- **结构化元数据展开**：在 `v2.2.2` 中全新升级，展开任何一条记录时，无论长短均呈现格式化全景字段：
  - **基础信息**：记录唯一 ID、操作类型（`op`）、安装包来源路径（`source`）；
  - **业务信息**：目标包名（`package`）、触发调用方应用（`caller`）、触发时间点（`time`）、全链路执行耗时（`durationMs`）；
  - **结果状态**：执行状态（成功 / 失败原因及对应错误码 `code`）；
  - **原始输出**：底端完整回显 Android 底层 `pm` 命令原始控制台输出；
- **便捷管理**：提供单项长按快速复制、一键导出分享至外部及本地日志全量清空功能。

### 3. 通用设置 (SettingsPage)
- **外观与个性化**：
  - 主题切换：跟随系统 / 浅色模式 / 深色模式 / **AMOLED 纯黑（Pure Black）**；
  - 切页动画：整页位移滑动（300ms easeOut） / 原地交叉淡入（220ms easeInOut）；
  - 导航形态：悬浮胶囊 Dock（悬浮于内容之上） / 底部停靠 Dock（传统贴底样式）；
- **安装行为管控**：
  - 安装前确认：开启后每次接收到外部安装请求均强制弹出二次确认卡片；
  - **安装成功后自动删除安装包**：开启后触发五级阶梯式安全清理，彻底释放本地存储空间；
- **调用方鉴权与安全**：
  - 鉴权总控：设置调用方权限检查策略（始终询问 / 仅首次询问 / 全部放行）；
  - 免确认白名单管理：管理系统级和常用文件管理器免弹窗信任名单；
  - **逐应用独立授权入口**：点击直达 `AllowAppsActivity`。

### 4. 逐应用授权管理 (AllowAppsActivity)
- **即时模糊搜索**：支持按应用名称或包名字段实时响应过滤；
- **三段式分类筛选**：支持「全部应用」、「用户应用」与「系统应用」一键切换；
- **弹性弹簧开关**：每项配备 `PrismSwitch` 阻尼弹簧开关，点击即时写入特权白名单配置，杜绝误授权风险。

---

## 🚀 快速开始（无需电脑）

普通 Android 应用无法自行获取特权，本项目创新性地将 **「无线调试服务发现 + TLS 配对 + ADB 协议握手 + 代理拉起」** 做成了无需电脑的自助闭环。

```mermaid
flowchart LR
    A["📱 1. 开启无线调试<br/>(系统开发者选项)"] --> B["🔑 2. 获取配对码<br/>(点使用配对码配对设备)"]
    B --> C["💬 3. 通知栏填入<br/>(下拉输入 6 位配对码)"]
    C --> D["⚡ 4. 自动激活 Broker<br/>(TLS 1.3 + SPAKE2)"]
    D --> E["✅ 5. 就绪可用<br/>(仪表盘显示已就绪)"]

    classDef step fill:#1e88e5,stroke:#1565c0,stroke-width:2px,color:#fff;
    classDef finish fill:#43a047,stroke:#2e7d32,stroke-width:2px,color:#fff;
    class A,B,C,D step;
    class E finish;
```

### 操作步骤

1. **开启无线调试**：
   - 打开系统「设置」➔「开发者选项」➔「无线调试」，打开总开关，并连接至 Wi-Fi 或开启自身热点。
2. **获取 6 位配对码**：
   - 在系统无线调试页面中，点击 **「使用配对码配对设备」**，界面将弹出 6 位数字配对码及配对端口。
3. **通知栏快速配对**：
   - 切换回本应用，点击 **「开始配对」**；
   - 系统通知栏将出现常驻前台服务通知，下拉通知栏，**直接在通知输入框内输入这 6 位配对码并点击发送**。
4. **自动激活与验证**：
   - 应用底层自动完成 mDNS 发现、RSA 证书注册、SPAKE2 密钥协商与 ADB TLS 握手，以 `shell(uid 2000)` 身份启动特权代理；
   - 回到应用首页，仪表盘状态区自动更新为 **「已就绪」**，即可开始静默安装。

> [!TIP]
> **私钥安全保证**：配对所用的 2048 位 RSA 密钥由应用在本地安全生成，存放于私有保护目录 `filesDir/adb_key.pk8`，并在系统备份规则中显式排除，绝不上传云端，确保单机凭证安全。

---

## 🏗️ 系统架构

本项目采用清晰的三层解耦架构：**Android 交互与 I/O 适配层**、**Rust 业务核心逻辑层**、**独立特权执行代理（Tokio 异步运行时）**。

<details>
<summary><b>📜 展开查阅：v2 长度前缀通信协议白皮书与密码学细节</b></summary>

### 1. 二进制长度前缀帧规范 (Wire Format)
每个数据包由固定 **4 字节大端长度前缀** 和后续紧随的 **UTF-8 JSON Payload** 组成：
```
+-----------------------------------+---------------------------------------+
|  Length Prefix (4 Bytes, Big-End) |         Payload (UTF-8 JSON)          |
|           u32 (0 .. 1MB)          |            Envelope JSON              |
+-----------------------------------+---------------------------------------+
```
- **单帧上限**：`MAX_FRAME_BYTES = 1024 * 1024`（1MB），防止异常数据流耗尽代理内存。
- **协议版本**：当前为 `PROTOCOL_VERSION = 2`。版本不匹配直接返回 `VERSION_MISMATCH`。

### 2. 消息信封结构 (Envelope)
```json
{
  "version": 2,
  "seq": 1001,
  "client_id": "io.github.lianxia233.privbroker:client_01",
  "session_id": 2048,
  "timestamp_ms": 1731150000000,
  "ttl_ms": 10000,
  "body": {
    "type": "EXEC",
    "argv": ["pm", "install-create", "-r"],
    "stdin": { "type": "PATH", "path": "/data/local/tmp/prvbrk-stage/uuid/base.apk" },
    "env": [],
    "timeout_ms": 30000,
    "cancel_group": 1001
  }
}
```

### 3. SPAKE2 与 ADB TLS 密码学保障
- **密码协商**：采用 Edwards25519 曲线的 SPAKE2 算法，基于用户输入的 6 位瞬态数字完成强鉴权，免除证书导入门槛；
- **传输安全**：建立 TLS 1.3 隧道后，通过 24 字节小端 `A_*` 帧完成 `A_STLS` 协议升级并拉起 Shell 独立代理。

</details>

```mermaid
flowchart TD
    subgraph UI_Layer ["📱 Android 客户端交互层 (Java / AndroidX)"]
        MainActivity["MainActivity<br/>(NavigationDock + 四页常驻架构)"]
        Dashboard["DashboardPage / LogsPage<br/>SettingsPage / AboutPage"]
        AllowApps["AllowAppsActivity<br/>逐应用授权管理"]
        ST["SilentTask<br/>后台安装全流程编排"]
        AuthComp["AuthPrompt & AuthForegroundService<br/>短时保活与卡片弹窗"]
        WPS["WirelessPairingService<br/>mDNS 发现 & 通知交互"]
        Bridge["BrokerBridge / StagedFiles / SourceFacts<br/>I/O 适配与来源事实记录"]
    end

    subgraph Core_Layer ["🦀 installer-core (Rust 纯业务逻辑 / Host 可单测)"]
        JNI["libinstaller_core.so<br/>(crates/android-ffi JNI 边界)"]
        StateMachine["状态机调度 / 会话流程"]
        ArchiveEngine["归档解包与分片挑选<br/>(APKS / XAPK / APKM / ZIP)"]
        NameNormalizer["后缀净化与规整<br/>(*.apk.1 -> 标准分片)"]
        MetaParser["AXML 元数据与 Label 解析"]
    end

    subgraph Broker_Layer ["⚡ broker-server (独立特权进程 / Shell uid 2000)"]
        TokioServer["Tokio 异步运行时入口<br/>(启动即验证 getuid == 2000)"]
        BrokerCore["broker-core<br/>调度状态机 / 会话管理 / TTL 清理"]
        BrokerAuth["broker-auth<br/>时间窗口挑战响应 (Nonce 鉴权)"]
        BrokerExec["broker-exec<br/>参数规格抽取与安全校验"]
        BrokerProcess["broker-process<br/>进程创建 / SIGKILL / 超时管理"]
        BrokerIPC["broker-ipc<br/>Unix Abstract / TCP Loopback 帧读写"]
    end

    MainActivity --> Dashboard
    MainActivity -.-> AllowApps
    WPS -->|"mDNS 配对 / SPAKE2 / ADB TLS"| TokioServer
    Dashboard --> ST
    ST --> AuthComp
    ST --> Bridge
    ST --> JNI
    JNI --> StateMachine
    StateMachine --> ArchiveEngine
    StateMachine --> NameNormalizer
    StateMachine --> MetaParser
    StateMachine -.->|"长度前缀帧协议: Hello ➔ Challenge ➔ Proof ➔ Ack"| BrokerIPC
    BrokerIPC --> BrokerCore
    BrokerCore --> BrokerAuth
    BrokerCore --> BrokerExec
    BrokerExec --> BrokerProcess
    BrokerProcess -->|"pm install-create / write / commit"| SystemPM["⚙️ Android system_server (Package Manager)"]

    classDef android fill:#e3f2fd,stroke:#1e88e5,stroke-width:2px,color:#0d47a1;
    classDef rust fill:#efebe9,stroke:#8d6e63,stroke-width:2px,color:#3e2723;
    classDef broker fill:#ede7f6,stroke:#7e57c2,stroke-width:2px,color:#311b92;
    classDef os fill:#e8f5e9,stroke:#43a047,stroke-width:2px,color:#1b5e20;
    class MainActivity,Dashboard,AllowApps,ST,AuthComp,WPS,Bridge android;
    class JNI,StateMachine,ArchiveEngine,NameNormalizer,MetaParser rust;
    class TokioServer,BrokerCore,BrokerAuth,BrokerExec,BrokerProcess,BrokerIPC broker;
    class SystemPM os;
```

### 完整静默安装执行时序

```mermaid
sequenceDiagram
    autonumber
    actor Caller as 📂 外部应用 / 文件管理器
    participant Entry as 🚪 SilentInstallActivity
    participant Auth as 🛡️ AuthForegroundService
    participant Facts as 📋 SourceFacts (事实记录)
    participant Rust as 🦀 installer-core
    participant Broker as ⚡ broker-server (uid 2000)
    participant PM as ⚙️ system_server (pm)
    participant UI as 🔔 Notifier (通知与指示器)

    Caller->>Entry: 发送安装意图 (ACTION_VIEW / SEND)
    Entry->>UI: 启动轻量左上角旋转指示器 (SciFiSpinner)
    Entry->>Auth: 查询调用方授权规则 (ALLOW / ASK / DENY)
    
    alt 未授权调用方 (ASK)
        Auth-->>Caller: 保持前台优先级，弹出悬浮决策卡片 / 横幅
        Caller->>Auth: 用户点击「允许并记住」
    end

    Entry->>Facts: 采集来源事实 (真实路径/大小/SHA-256 计算)
    Entry->>Rust: 暂存文件并交给 Rust 核心解析
    Rust->>Rust: 解包归档 / 过滤分片 / 剥离 .apk.1 备份后缀
    Rust->>Broker: 建立连接并发送 Hello 帧
    Broker-->>Rust: 返回 AuthChallenge (包含动态 Nonce)
    Rust->>Broker: 回传计算后的 AuthProof
    Broker-->>Rust: 鉴权通过 (AuthAck + SessionToken)
    
    Rust->>Broker: 下发 InstallCommand (参数数组与输入规格)
    Broker->>PM: 执行 pm install-create / install-write
    PM-->>Broker: Session Committed / Success
    Broker-->>Rust: 返回执行结果与原始控制台输出
    
    opt 开启「安装成功后删除安装包」
        Entry->>Facts: 执行五级阶梯式安全清理 (严格核验大小与哈希)
    end

    Rust->>Entry: 生成结构化记录并持久化至 install-log.jsonl
    Entry->>UI: 移除指示器，更新结果通知 (带「一键打开」与中文说明)
    Entry->>Entry: 零窗口立即 finish() / 回传 RESULT_OK
```

---

## 🧹 五级阶梯安装包自动清理机制

在现代 Android 存储机制（Scoped Storage / SAF / FileProvider）限制下，应用直接通过 `File.delete()` 或 `ContentResolver.delete()` 往往无权删除第三方传递的安装包。为此，本项目构建了一套**基于特权代理（Shell uid 2000）的五级阶梯删除机制**：

```mermaid
flowchart LR
    S1["1. 应用侧删除<br/>(Documents / Content)"] --> S2["2. Shell 路径删除<br/>(按已解析物理路径 rm)"]
    S2 --> S3["3. Shell 媒体库查删<br/>(content query 取 _data)"]
    S3 --> S4["4. Shell URI 删除<br/>(content delete 按 URI)"]
    S4 --> S5["5. 全盘指纹寻回<br/>(/storage 下哈希核验删除)"]

    classDef step fill:#e3f2fd,stroke:#1565c0,stroke-width:1.5px,color:#0d47a1;
    class S1,S2,S3,S4,S5 step;
```

### 关键技术保障

1. **暂存期采集来源事实 (`SourceFacts.java`)**：
   在拥有临时读取权限的暂存期，提取 SAF 文档 ID（`primary:Download/x.apk`）、反查 `/proc/self/fd` 物理路径、规整 `/mnt/user/...` 视图、通过 `fstat` 获取精准物理大小，并在流式复制的同时计算完整 SHA-256 哈希值。
2. **删除前强制内容指纹核验**：
   在执行任何底层删除操作前，必须严格比对文件体积；有哈希时比对 SHA-256 内容指纹。**核验不通过坚决放弃删除，宁可留存也绝不误删同名文件**。
3. **参数化执行规避注入风险**：
   特权 Shell 命令统一通过 `sh -c script sh <位置参数>` 传递，路径和文件名完全不参与脚本字符串拼接，杜绝任何命令注入漏洞。
4. **精细化审计说明**：
   若因系统保护导致无法删除，日志明确输出具体受限原因（如「自 Android 11 起受系统保护目录」），杜绝模糊报错。

---

## 📦 支持格式与交互协议

应用通过在 `AndroidManifest.xml` 中配置精细的 Intent 过滤器，实现了对各大应用市场、社交软件分发包的全面覆盖。

| 扩展名 / 格式 | 文件类型与来源 | 内部处理策略 |
| :--- | :--- | :--- |
| **`.apk`** | 标准单个 Android 安装包 | 直接建立会话并流式写入安装 |
| **`.apks`** | Android App Bundle (AAB) 导出的分片归档 | 自动解析 ZIP 目录，挑选有效 `.apk` 分片，优先挂载 `base.apk` |
| **`.xapk` / `.akp`** | APKPure 等三方平台的分片打包格式 | 按分片归档处理，提取内部全部分片 APK |
| **`.apkm` / `.apkmx`** | APKMirror 分片安装包格式 | 同上，自动识别架构并组合分片会话 |
| **`.zip`** | 包含多个 `.apk` 的通用压缩归档 | 同上，免解压流式过滤有效 APK 实体 |
| **`*.apk.1` / `*.apk.bin`** | **微信、QQ 等社交软件恶意改名的防拦截后缀** | **链路最前端统一剥离恶意后缀**，按原真实格式还原安装 |
| **多选文件** | 系统文件管理器多选分享 | 合并为单次 Multi-Split 协同安装会话 |

### 社交软件追加后缀处理机制

> [!NOTE]
> 微信、QQ 等社交平台在接收和转发 APK 时，会将 `app.apk` 强行重命名为 `app.apk.1`、`app.apk.2` 或追加 `.bin`、`.download`，从而阻止触发系统安装器。
> 
> 本项目在链路最前端通过 `FileNames.normalize()`（Java 端）与 `util::normalize_name()`（Rust 核心）实现双重净化：
> 1. `sample.apk.1` 会被精准剥离为 `sample.apk` 并按单包处理；
> 2. `bundle.apks.1` 会被正确剥离为 `bundle.apks` 并按分片归档解析；
> 3. 暂存文件名与日志中的显示名同步净化，彻底避免出现 `sample.apk.1.apk` 这种畸变命名。

---

## 🔬 深度工程机制与真机踩坑

本项目在真机（包括 HyperOS、MIUI、原生 Android 14/15/16 等系统）的实际调试中，攻克了大量系统底层限制与特殊场景。

<details>
<summary><b>1. 真正的零感静默：MIUI 结果忽略窗口与 1x1 隐形浮窗</b></summary>

- **零窗口执行**：多数文件管理器和分享操作不需要接收回执，入口 `SilentInstallActivity` 在 `onCreate()` 内立即触发 `finish()`。Android Framework 检测到 `mFinished = true` 会直接跳过 `wm.addView`，达成真正意义上的 **0 毫秒零窗口**。
- **1x1 隐形窗口适配**：对于通过 `startActivityForResult` 或带有 `EXTRA_RETURN_RESULT` 请求回执的调用方，入口使用定制的 `@style/SilentEntryTheme` 保持存活 1400ms，以绕过 MIUI/HyperOS 上著名的 **「1 秒结果忽略窗口」**。窗口被精确限制为 `1x1` 像素、全透明、不可聚焦、不可触摸、无 Dim、系统栏透明。
- **杜绝全屏灰色蒙层**：主题坚决不配置 `windowIsFloating`。在大量国内厂商 ROM 上，`floating + translucent` 会被窗口管理器错误铺成整屏半透明蒙层（早期版本偶尔出现的透明遮罩的根因）。

</details>

<details>
<summary><b>2. 代理单实例拉起：ash 内建 read 性能飞跃与进程名匹配坑</b></summary>

特权代理在每次激活时，必须先将历史残留实例清理干净，确保同一端点无端口冲突。在真机实测中解决了三个致命问题：

- **判据必须取 `/proc/pid/cmdline` 而非 `comm`**：
  `app_process --nice-name=xxx` 只会修改进程的 cmdline，Linux 内核中的 `comm` 依然会被 Android 运行时强制覆盖为 `main`（Java 主线程名）。若依据 `comm` 过滤，真机命中率永远为 0。因此必须解析 `/proc/pid/cmdline` 并对首参数做前缀锚定匹配（同时避开执行清理自身的 `sh -c`）。
- **Shell 内建 `read` 取代外部进程 fork（26 秒 ➔ 0.13 秒）**：
  启动命令是在无线调试 Shell 中异步下发的，投递后等待超时设定为 6 秒（`DETACHED_READ_TIMEOUT`），超时后 adbd 会强杀整个进程组。
  - **严重缺陷**：若使用 `$(tr -d ' ' < "$p/cmdline")` 遍历 `/proc`，在有 882 个进程的真机上会反复 fork 外部进程，耗时高达 **26.1 秒**，整条命令 28.6 秒才返回，必定触发超时被 adbd 强制清理，导致服务永远拉不起来！
  - **优化方案**：改用 Shell 内建的 `read` 命令直接读取 `/proc/pid/cmdline`，真机扫描时间直接骤降至 **0.13 秒**，整条启动命令 3.4 秒即可平稳返回！
- **握手全量验证判据**：
  端点端口是根据令牌派生的，若上个版本遗留了陈旧实例占住端口，纯端口探测会恒为真（导致假激活）。现全部统一为 `BrokerClient.handshake` 完整握手鉴权，杜绝假成功。

</details>

<details>
<summary><b>3. 跨应用授权：shortService 前台保活与无缝横幅降级</b></summary>

- **shortService 前台保活**：由于入口 Activity 追求零感静默会在拉起后立即 `finish`，Android 进程会瞬间降级为 `cached` 状态。在国产 ROM 上异步申请悬浮窗往往来不及弹出即被系统冻结。本项目通过拉起类型为 `shortService` 的 `AuthForegroundService`，将进程优先级钉在前台，决策完成或 90 秒超时后立即自我销毁，安全且合规。
- **通知横幅决策降级**：当设备未授予悬浮窗权限时，应用不会强行跳转主页打断用户，而是自动退化为一条**带有「允许并继续 / 拒绝」Action 按钮的即时横幅通知**。按钮携带完整请求上下文，用户直接在通知栏点击即可完成授权并无缝继续安装。
- **匿名调用安全防御**：无法识别调用方包名的匿名请求**绝对禁止勾选「永久记住」**，防止一次恶意放行导致系统安全防线失守。

</details>

<details>
<summary><b>4. 数据生命周期与 SELinux FUSE 域避坑</b></summary>

- **SELinux FUSE 域阻断**：应用私有外部存储虽然能被特权代理读取，但 `pm install-write <path>` 是由系统核心进程 **`system_server`** 打开文件的。`system_server` 访问属于 FUSE 域（`u:object_r:fuse:s0`）的文件时会被 SELinux 强制阻断（`avc: denied { read }`，`pm` 返回 255）。
- **随机临时中转机制**：兜底时应用将文件写到缓存后，特权代理以 shell 身份将文件复制到 `/data/local/tmp/prvbrk-stage/<随机目录>`。目录权限严格设为 `0711`，文件权限设为 `0644`，保证 `system_server` 可以按具体路径读取，其他普通应用无权枚举该目录。安装结束或异常退出时立即完整清空回滚。

</details>

<details>
<summary><b>5. 幻影进程防杀与端点自愈重拉 (BrokerReviver)</b></summary>

- **修正幻影进程配置**：早期指令中针对 Android 12+ 幻影进程监控的关闭命令存在命名空间错误。现已正规化为：
  ```bash
  settings put global settings_enable_monitor_phantom_procs false
  device_config put activity_manager max_phantom_processes 2147483647
  ```
- **安装中途端点自愈**：当安装或卸载链路偶发遇到 `BROKER_NOT_READY` 时，内置 `BrokerReviver` 会自动通过 mDNS 重新嗅探可用端口，并发起重拉与全握手校验。自愈成功后自动重试当次任务，全程无需用户重新输入配对码。

</details>

---

## 📱 平台兼容性矩阵

| 平台版本 | 对应 API | 重点适配特性与安全机制 |
| :--- | :---: | :--- |
| **Android 16** | API 36 | 严格 16KB 内存页对齐；显式声明 `android:exported`；适配最新编译工具链 |
| **Android 15** | API 35 | 全局 Edge-to-Edge 沉浸与 `fitsSystemWindows` 配色；1x1 隐形浮窗避让状态栏 |
| **Android 14** | API 34 | 跨应用授权采用 `shortService` 类型前台服务保活；所有 Intent 显式添加 `FLAG_IMMUTABLE` |
| **Android 13** | API 33 | 适配运行时 `POST_NOTIFICATIONS` 权限申请；通知分组与 Themed Icon 原生着色 |
| **Android 11+** | API 30~32 | 原生 ADB 无线调试通信通道；配置 `<queries>` 声明；`QUERY_ALL_PACKAGES` 枚举支持 |
| **Android 8~10** | API 26~29 | 系统悬浮窗 `TYPE_APPLICATION_OVERLAY` 兼容；自适应图标适配与旧版后台启动限制规避 |

### 关键权限声明说明

- `POST_NOTIFICATIONS`：用于展示安装进度气泡、结果通知以及在无悬浮窗权限时的横幅授权交互。
- `INTERNET` & `ACCESS_NETWORK_STATE`：无线调试与特权代理建立本地环回（Loopback TCP / Socket）通信的法定权限。
- `CHANGE_WIFI_MULTICAST_STATE`：用于 mDNS 组播发现局域网内的无线调试配对与连接服务。
- `SYSTEM_ALERT_WINDOW`：用于弹出优雅的跨应用授权悬浮卡片与左上角轻量进度指示器（可选，支持横幅通知降级）。
- `QUERY_ALL_PACKAGES`：仅用于在「设置」与「卸载应用」页面中枚举设备本地应用以供筛选配置，**绝不进行任何数据上传**。

---

## 🛠️ 编译构建与签名

### 构建环境依赖

- **Rust 工具链**：`stable` 版本，需安装 4 个 Android Target：
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
  ```
- **构建工具**：`cargo-ndk` (`cargo install cargo-ndk`)
- **Android SDK & NDK**：
  - Android SDK Platform 36 / Build-Tools 36
  - Android NDK `27.2.12479018`
- **Java / Gradle**：JDK 17+，Gradle 8.14（仓库内置 `gradlew`）
- **Python 环境**：Python 3 + Pillow + materialyoucolor（用于图标、色板及测试数据生成）

### 一键构建指令

```bash
# 1. 配置环境变量
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018

# 2. 运行一键构建脚本
bash scripts/build.sh

# 产物输出于: PrivilegeBroker.apk
```

<details>
<summary><b>分步手动编译流程</b></summary>

```bash
# 1. 运行全 workspace 单元测试 (含协议/鉴权/会话/IPC 测试)
cargo test --workspace

# 2. 交叉编译 4 个 ABI 的 Native 动态库 (严格 16KB 页对齐)
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64     -o android/app/src/main/jniLibs build --release -p installer-android-ffi

# 3. 编译打包 Android APK
cd android && ./gradlew assembleDebug

# 4. 可选：资源与色板生成脚本
python3 scripts/gen_icons.py        # 重新生成各分辨率图标
python3 scripts/gen_palette.py      # 按 prism 规则重新生成 M3 色板
python3 scripts/gen_axml_fixture.py # 重新生成测试样本
```

</details>

### 构建可复现性与固定签名保证

仓库在 `android/app/signing/release.keystore` 中分发了固定的自签名密钥，`build.gradle.kts` 将 `debug` 与 `release` 构建均强制指向该文件：

> [!IMPORTANT]
> - **沙箱本地构建与 GitHub Actions 云编译产出的 APK 签名证书完全一致（逐字节指纹相同）**，跨环境构建产物可直接相互覆盖安装。
> - 该密钥属于**公开的自签名可复现构建凭据**，专用于保证开源分发一致性与版本无缝升级，请勿当作私有商业发布证书使用。
> - 自 `v2.0.0` 起启用了全新 `applicationId`，如从旧版升级，请先卸载旧版本应用。

---

## 📂 仓库工程结构

```
android-privilege-broker/
├── .github/workflows/          # GitHub Actions 云编译 (Node 24) 与 Release 发布工作流
├── android/                    # Android 原生模块 (Gradle、Java、Manifest、资源)
│   ├── app/
│   │   ├── signing/            # 固定的可复现签名密钥 (release.keystore)
│   │   └── src/main/
│   │       ├── java/.../privbroker/
│   │       │   ├── MainActivity.java       # 主壳层：4 页常驻挂载与 NavigationDock 调度
│   │       │   ├── DashboardPage.java      # 仪表盘：Hero 状态卡、内存看板、14天 Sparkline
│   │       │   ├── LogsPage.java           # 记录页：分段筛选、结构化元数据展开与导出
│   │       │   ├── SettingsPage.java       # 设置页：主题外观、安装策略与鉴权总控
│   │       │   ├── AboutPage.java          # 关于页：版本信息、证书指纹与开源许可清单
│   │       │   ├── AllowAppsActivity.java  # 逐应用授权独立管理界面 (搜索/过滤/弹簧开关)
│   │       │   ├── PairingActivity.java    # 无线配对与服务激活专属向导页
│   │       │   ├── AppPickerActivity.java  # 已安装应用选择器 (安全卸载入口)
│   │       │   ├── SilentInstallActivity.java # 零感静默安装分发入口 (0ms 零窗口)
│   │       │   ├── NavigationDock.java     # 灵动胶囊导航底栏 (镜头放大与过冲回弹)
│   │       │   ├── PageSwitcher.java       # 双模页面切换容器 (位移滑动 / 交叉淡入)
│   │       │   ├── Prism.java              # 动态配色系统与 Theme Attribute 解析工具
│   │       │   ├── PrismSwitch.java        # 弹性物理弹簧动效开关控件
│   │       │   ├── SegmentedControl.java   # iOS 风格分段平滑滑块选择器
│   │       │   ├── SquircleDrawable.java   # 超椭圆连续曲率圆角背景绘制
│   │       │   ├── Sparkline.java          # 贝塞尔平滑迷你面积走势图
│   │       │   ├── SourceFacts.java        # 来源事实采集与五级阶梯删除执行器
│   │       │   └── ...                     # SilentTask / Notifier / BrokerBridge 等核心
│   │       └── res/                        # 多语言字符串、矢量图标、M3 色板与主题属性
├── crates/                     # Rust 核心 Workspace (12 个独立 Crate)
│   ├── core/                   # installer-core: 业务状态机、命令规划、分片解包
│   ├── android-ffi/            # JNI cdylib 桥接层 (libinstaller_core.so)
│   ├── broker-protocol/        # v2 帧协议、4 字节大端长度前缀与数据结构定义
│   ├── broker-auth/            # 挑战响应鉴权、Nonce 管理与常量时间校验
│   ├── broker-exec/            # 特权执行规则定义、安全白名单参数审计
│   ├── broker-process/         # 异步进程引擎、SIGKILL 与超时销毁
│   ├── broker-ipc/             # Unix Abstract Socket / TCP Loopback 帧读写
│   ├── broker-android/         # UID 校验、SELinux 域嗅探与端点派生
│   ├── broker-core/            # Tokio 特权代理核心状态机与会话生命周期
│   ├── broker-client/          # Rust 客户端 SDK 与端到端集成测试套件
│   ├── broker-server/          # 独立特权后台进程主入口 (独立二进制/app_process)
│   └── broker-wireless/        # 无线调试协议栈 (mDNS / SPAKE2 / TLS 1.3 / ADB TLS)
├── icons/                      # 原始图标矢量素材与源图像
├── scripts/                    # 一键构建脚本 (build.sh) 与色板/图标生成脚本
│   ├── build.sh                # 跨架构一键编译与 APK 打包脚本
│   ├── gen_palette.py          # M3 HCT 算法离线色板生成工具
│   ├── gen_icons.py            # 全套分辨率 mipmap 图标生成工具
│   └── gen_axml_fixture.py     # 测试 AXML 二进制样本生成工具
├── AGENTS.md                   # 仓库协作、编码与提交脱敏规范
├── CHANGELOG.md                # 完整版本演进历史记录
├── NOTICE                      # 第三方开源组件与设计参考法定归属说明
└── LICENSE                     # GNU GPL-3.0 开源许可协议
```

---

## 📜 开源许可与免责声明

- 本项目采用 **GNU General Public License v3.0（GPL-3.0-only）** 协议开源，完整协议文本请查阅 [LICENSE](LICENSE)。
- 本项目关于界面结构、色板设计与动效时长的开源参考致谢见 [NOTICE](NOTICE)。
- 仓库内附带的全部图标设计、图形资源与测试用例同样遵循 GPL-3.0 协议。
- **免责声明**：本项目通过 Android 官方无线调试规范获取 `shell(uid 2000)` 权限以执行常规的包管理操作，不提供 Root 越权能力。请确保安装来源合法可靠，开发者不对因安装第三方未知来源应用所导致的任何软硬件异常或数据丢失承担责任。

---

<p align="center">
  <b>Android Privilege Broker</b> • 由开源社区共同维护构建<br/>
  <sub>Copyright © 2025-2026 LianXia233. All rights reserved.</sub>
</p>
