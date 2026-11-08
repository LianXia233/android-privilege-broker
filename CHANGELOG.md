# 更新日志

本文件记录各版本的可见变更。按时间倒序排列，版本号与 `android/app/build.gradle.kts`
中的 `versionName`、`Cargo.toml` 中的 `version` 一致。

---

## v2.1.12

### 修复「激活成功但特权代理未运行」：统一判据、实时状态、进程保活、通知栏输码框

上一版的成功判据是「app 实测 **或** 启动日志确认」，而状态徽章独立地用
abstract socket 直连判定，两处判据不一致 —— 结果条报「激活成功·复用现有实例」，
状态区却显示「特权代理未运行」。同时 `selinux=enforcing` 下 app 域连不上 shell
域的 abstract socket，服务对 app 根本不可用。

- **端点改为 TCP loopback**：`BrokerClient.endpoint()` 默认返回按配对令牌派生的
  `tcp:127.0.0.1:<port>`（旧值 `abstract` 读取时自动迁移）。本机回环 TCP 不受
  SELinux 域限制，app 与代理两侧都能访问；`broker-server` / `BrokerClient` /
  `broker-ipc` 原本就支持 `tcp:` 端点，无需改协议。
- **全局唯一判据**：新增 `BrokerClient.probe()`，激活页状态徽章、结果条、通知栏
  自动激活三处统一用它，杜绝界面自相矛盾。启动日志的「监听成功 / 已在运行」
  降级为诊断线索，**不再**能单独判定激活成功。
- **状态实时同步**：配对页可见时每 2.5 秒刷新一次状态；服务被系统回收后立刻
  改回「未运行」并提示「点激活服务重新拉起」。`onResume` 刷新、`onPause` 停轮询。
- **进程保活**：启动命令改用 `setsid env CLASSPATH=… app_process …`，让代理从
  启动第一条指令起就处于独立会话，adbd 会话收尾按进程组发的信号再也碰不到它。
  仅靠 Java 侧 `Os.setsid()` 不够（后台 job 常已是进程组首进程而失败）。
- **通知栏输码框样式**：整条通知着色 + 品牌大图标 + 步骤副标题 + 结构化
  `BigTextStyle` + 图标化输码/查看日志按钮 + 点击主体直达激活页；保留系统原生
  `RemoteInput`（MIUI / HyperOS「原生样式」下才会显示内嵌输入框），不用自定义
  `RemoteViews` 覆盖。
- 版本升至 2.1.12 / versionCode 19。

---

## v2.1.11

### 修复「端点绑定失败：Address already in use (os error 98)」——完整对齐成熟实现的单实例机制

对照 同类开源实现的启动器与判定逻辑，定位到激活链路缺了最关键的**单实例**保证：

- **根因**：此前每次点「激活服务」都无脑再拉起一个 `app_process`，旧进程仍
  占着 `prvbrk.<token>` 这个 abstract socket，新进程 `bind` 必然
  `EADDRINUSE`（os error 98）并退出；界面拿到的是这次失败的日志，于是报
  「特权代理没有保持运行」。而 `Address already in use` 恰恰说明**服务其实
  已经在跑**。
- **启动前清理旧实例**（对齐成熟实现的「启动前清理旧进程」）：启动命令
  遍历 `/proc/*/comm`，按进程名 `prvbrk_svc`（并兼容旧名 `privilege-brok*`）
  `SIGKILL` 掉所有残留实例，再拉起唯一的新进程。进程名改用 15 字符以内的
  短名，避免 Linux `comm` 截断导致匹配不到。
- **native 侧幂等复用**：`broker-core::serve` 在 `bind` 返回 `EADDRINUSE` 时
  先探测该端点，能连上就判定「已有实例在运行」并正常退出，不再当致命失败。
- **成功判据改为双通道**（对齐成熟实现以启动器输出判定
  启动结果）：把「监听成功:」标记从 `bind` **之前**移到 `bind` **之后**打印，
  并以 native 启动日志的权威标记 + app 侧 socket 实测二者任一成立为准。
  部分 ROM 的 SELinux 会拦截 app→shell 域 abstract socket 直连，此时 app
  侧探测恒为 false 但服务已起来，旧逻辑会误报激活失败。结果条如实区分
  「已实测连通」「复用已有实例」「日志确认监听」，不宣称未做的实测。

### 执行日志支持点击展开查询详情

- 每条记录点击即可**展开完整原文**（不限行数、不省略、不截断），再点收起；
  收起态仍保留头尾摘要，末尾的失败原因不会被吃掉。
- 展开态显示「可复制」提示，**长按整条**即复制完整启动日志 / 失败原因到剪贴板。
- 列表刷新后展开态自动重置，避免位置错乱。

---

## v2.1.10

### 诊断呈现：不再倒原始日志，失败原因不再被截断

- **去掉内部术语与阶段前缀**：不再把 `started:`、uid / backend / selinux /
  abstract 端点名原样倒给用户；结果条改为「结论 + 原因 + 过程要点 + 下一步」。
- **完整错误行不再被吃掉**：之前结果条把启动日志从头部截到 260 字符，恰好把
  末尾 `监听失败: …` 的真实 errno 切掉，只剩一个无从诊断的省略号。现在失败行
  由 native 侧单独结构化回传（`failureReason`）并完整显示；启动日志全文写入
  「执行日志」，详情过长时保留头尾而不是只留前段。
- **日志去重**：自动激活与手动激活并发时会把启动日志串成两遍。启动命令改用
  带 shell PID 的唯一日志文件名，界面侧也只保留最后一次启动段。
- **纠正误导性标记**：运行时打印的「监听:」其实发生在端点绑定**之前**，改为
  「准备监听:」；是否常驻仍以实测 abstract socket 为唯一判据。

### 通知栏样式

- 常驻输码通知与结果通知改用 `BigTextStyle` 展开多行内容，失败诊断不再被单行
  截断；通知里新增「查看日志」直达入口，一键到「执行日志」看完整原始日志。
- 输码通知的 `RemoteInput` 主路径保持不变（配对弹窗不能退出/返回）。

### 激活记录写入执行日志

- 每次「激活服务」把结果（成功/失败、连接端口、WLAN 地址、耗时、完整启动日志）
  追加进「执行日志」；日志页新增「激活 · 无线调试」类型及端口、耗时展示。
  应用内按钮与通知栏通道两条激活路径都会记录。

版本 2.1.10 / versionCode 17。

---

## v2.1.9

### 修复：native 库未解压导致特权代理进程无法启动（激活链路最后一处根因）

v2.1.8 修好了启动命令的 stdio / 进程组与成功判据，但真机仍可能「配对成功、
激活后服务依旧未运行」。定位到一个更底层的打包问题：

- **AGP 默认 `extractNativeLibs=false`**：so 以未压缩、对齐方式留在 APK 内，
  系统直接从 APK 映射，安装后 `nativeLibraryDir` 下**没有真实文件**。而特权代理
  由无线调试 shell 经 `app_process` 拉起，运行在没有应用 ClassLoader 上下文的
  shell(2000) 进程里，只能用 `System.load(nativeLibraryDir + "/libinstaller_core.so")`
  绝对路径加载 —— 该路径必然抛 `UnsatisfiedLinkError`，进程在启动阶段就退出。
  应用内配对不受影响（应用进程可用 `loadLibrary`），所以症状恰好是
  「配对成功、服务起不来」。
- **把 so 解压为真实文件**：`packaging.jniLibs.useLegacyPackaging = true` 令 AGP
  产出 `android:extractNativeLibs="true"`，安装器把 so 解压到 `nativeLibraryDir`，
  绝对路径加载恢复可用。
- **shell 侧多路径加载 + 可诊断日志**：`WirelessCore.ensureLoaded(nativeLibDir, apk)`
  依次尝试解压后的绝对路径、各 ABI 的 APK 内路径、`loadLibrary` 兜底，每次失败都
  打到 stderr；`ServiceStarter` 从 `--apk=` / `CLASSPATH` 取 APK 路径，并在加载失败
  时明确打印原因。启动日志会回显到界面结果条，个别 ROM 安装行为异常时也能看到
  真实原因，而不是只剩一个无从诊断的「激活失败」。

版本 2.1.9 / versionCode 16。

---

## v2.1.8

### 修复：激活失败 / 提示成功但「特权代理服务未运行」

定位到两个叠加的根因，均在此版本修复：

- **后台启动命令没有重定向标准输入输出**：常驻的 `app_process` 进程继承了
  ADB shell 通道的 stdout/stderr 文件描述符，adbd 会一直等这些 fd 关闭才发
  `A_CLSE`；客户端读超时断开后，adbd 又会终止整个进程组，把刚拉起的代理一起
  杀掉 —— 表现为「激活转半天然后失败」，或「提示成功但服务从未运行」。现在启动
  命令改为 `</dev/null >>日志 2>&1 &`，主 shell 能立即结束、通道正常关闭；Java
  侧 `ServiceStarter` 再调用 `Os.setsid()` 脱离 adbd 会话，避免通道收尾被连带终止。
- **`shell:` 通道死等 `A_CLSE`**：新增 `shell_detached`，命令一旦写入通道即视为
  已投递，收到 `A_CLSE` / 读超时 / EOF 都返回已收集的启动日志，不再因个别 ROM
  迟迟不关闭通道而误报失败。
- **把「服务是否真起来」作为激活成功的唯一判据**：native 返回新增 `listening`
  标志与启动日志回显；应用侧在投递命令后轮询探测 abstract socket，只有实测到
  监听才算激活成功。彻底消除「假成功」，失败时结果条回显启动日志（类加载、
  so 加载、uid 校验等真实原因）并给出明确的下一步。通知栏配对通道同样加入实测
  校验，未实测到服务时不再直接宣告成功并停止服务。

### UI：通知栏输码入口移到激活按钮旁并高亮

- 「在通知栏输入配对码」从页面底部的次要按钮，提升为与「激活服务」紧邻的一等
  入口：暖橙渐变卡片 + 描边 +「必做」标签 + 独立高亮按钮，点击立即把常驻输码
  通知置顶并给出下拉通知栏输码的分步引导。
- 服务未运行或激活失败时，该入口自动切换为更醒目的红橙强调态；激活成功后恢复
  常态，形成「先输码 → 再激活」的直观动线。

版本 2.1.8 / versionCode 15。

---

## v2.1.7

### 新增：「激活服务」独立按钮 + 配对页现代化重构

把「配对」与「激活服务」明确拆成两件独立的事，不再绑在一次自动流程里：

- **激活服务成为一等公民主按钮**：配对成功后公钥已持久化在设备 adbd 与本机
  `adb_key.pk8`，此后只要无线调试开启并连接 WLAN，随时点「激活服务」即可用
  已登记公钥连接 `_adb-tls-connect` 端口、以 shell 身份重新拉起特权代理——
  设备重启、代理进程被回收后都无需重新配对。按钮随时可点、可重复、结果明确。
- **端口可自动发现也可手填**：连接端口输入框由 mDNS 自动回填，也可直接填
  无线调试页「IP 地址和端口」显示的端口；地址候选沿用 v2.1.6 的
  mDNS 解析地址 + 本机网卡 IPv4 + loopback 逐个尝试。
- **状态徽章实时反映代理是否运行**：仅探测 abstract socket 是否被监听
  （连上即在跑，不做握手），独立线程池执行，绝不阻塞激活/配对与界面；
  激活成功后自动刷新为「已激活」。
- **配对降级为「首次一次性」步骤**：通知栏 RemoteInput 仍是配对码主入口
  （弹窗不能退出/返回），页面内输码作为分屏/小窗备用；配对完成后明确引导
  点「激活服务」，并在拿到重分配后的新连接端口时自动激活一次（按钮仍可手动点）。

### UI 现代化重构

- 渐变 Hero + 状态徽章、圆形步骤徽章、结果条（信息/成功/错误三态）、
  三段渐变主 CTA、卡片 elevation 层次、统一圆角与间距，深色模式沿用现有语义色。

### 支持范围

- **最低版本提升到 Android 13（minSdk 33 / targetSdk 36）**：新配对页据此按
  13+ 直接编写，通知权限恒需运行时请求、前台服务恒直接启动，去掉针对低版本的
  `SDK_INT` 早退判断；通知栏服务、mDNS 探测等已验证实现沿用原逻辑，不改动其行为。

版本 2.1.7 / versionCode 14。

---

## v2.1.6

### 修复：通知栏始终没有、端口识别不到、填了端口也不识别

真机（REDMI K80 / HyperOS）验证暴露了三个同源偏差，逐项对照成熟实现后修正：

- **缺 `INTERNET` 权限**：Android 上创建任何 TCP socket 都需要它。此前清单从未声明，
  所有连接直接 `Permission denied` —— 这就是「手动填了端口也不识别」的直接原因。
  同时补 `ACCESS_NETWORK_STATE`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- **连接地址硬编码 `127.0.0.1`**：AOSP 把 adbd 的 TLS 端口绑在 loopback，但部分 ROM
  绑定在 **WLAN 接口 IP**（设置页「IP 地址和端口」显示的地址，如
  `192.168.88.158:39099`）。现在把 mDNS 解析地址、本机全部网卡 IPv4 与 loopback
  组成候选列表传给 native，逐个尝试、任一连通即用；连接均带 3 秒超时避免串行阻塞。
- **mDNS 过滤误杀**：旧逻辑在 `127.0.0.1` 上 bind 判断端口是否被占用，在 adbd 只绑
  WLAN 接口的 ROM 上端口必然「空闲」，于是每一个服务都被丢弃 —— 「无法自动识别」。
  现改为只做基本合法性判断，能否连通交由连接层实测，不再猜测端口占用。

### 修复：通知栏输码路径被降级成可选

系统「无线调试」页里的配对码弹窗**不能退出、不能返回**，一离开该页就消失，用户
无法切回应用输码。下拉通知栏不会让设置页失去焦点，因此**通知栏 RemoteInput 是
唯一可行路径**，v2.1.4 / v2.1.5 把主路径改成应用内弹窗是方向性错误。

- 教程页一打开（只要通知可用）就 `startForegroundService`，服务立即 `startForeground`
  常驻「搜索中」通知；发现配对服务后把通知替换成带输入框的通知。
- 页面内端口/配对码输入降级为多窗口、小窗场景的备用入口。
- 教程文案明确「不要切回应用，直接下拉通知栏输码」，并说明必须已连接 WLAN。
- 新增电池优化设置入口：部分 ROM 在应用不可见时禁止联网，即使有前台服务。
- 前台服务启动被系统拒绝时按参考实现降级为普通 `startService` 并引导白名单。

### 其它

- `broker-wireless` 新增 6 个多候选地址单测，共 39 个全部通过。
- 前台服务 `startForeground` 失败时改用 `STOP_FOREGROUND_DETACH`，保留普通通知
  同时撤销前台启动，避免 5 秒超时终止进程。

---

## v2.1.5

### 修复：点「开始配对」立即闪退

根因是点按钮时无条件 `startForegroundService`，而后 `startForeground` 在部分
设备上抛异常失败；系统规定此时服务必须在 5 秒内成功进入前台，否则抛
`ForegroundServiceDidNotStartInTimeException` 直接终止**整个应用进程**。旧实现
失败时只降级成普通通知、没有撤销这次前台启动，于是必然被杀。

- 配对页不再自动启动任何前台服务；通知栏通道改为用户显式点按钮才开启
- `startForeground` 失败立即 `stopForeground` + `stopSelf`，撤销前台启动
- 前台服务只声明 `shortService`，`startForeground` 传
  `FOREGROUND_SERVICE_TYPE_MANIFEST` 由系统按清单取值

### 移植：无线调试链路按成熟实现完整对齐

逐项比对参考实现的 ADB 协议栈后，确认 TLS 1.3 exporter（`adb-label\0`）、
SPAKE2、AES-128-GCM 序列 nonce、6 字节配对包头、8192 字节 PeerInfo、
24 字节小端 ADB 帧、`A_STLS` 升级、`android_pubkey` 编码与 PKCS#1 v1.5 签名
均已字节级一致，无需改动。**补齐的是两处流程层缺口**：

- **mDNS 发现过滤**：新增两道校验——解析出的地址必须属于本机网卡，
  且该端口在 127.0.0.1 上已被监听（bind 抛异常）。此前会误收局域网内其它
  设备广播的同名服务或已失效的缓存记录，导致连接必然失败。
  服务消失时同步把端口置回 -1。
- **配对与启动解耦**：配对服务只在「使用配对码配对设备」弹窗打开期间存在，
  配对成功后系统会重分配 `_adb-tls-connect` 端口。现在 `pair` 阶段只用配对
  端口与 6 位码完成公钥注册，随后重新发现连接端口再进入 `start` 阶段连接
  并启动。两阶段各自独立校验、可独立失败与重试；配对不再要求连接服务已就绪。

### 其它

- 新增独立配对页，教程、发现、输码、结果同页完成，去掉跨页面 Dialog 生命周期问题
- 全流程异常收敛为结构化 JSON，配对页不再依赖通知权限/通知样式/ROM 后台策略
- 两阶段编排新增 6 个单元测试，`broker-wireless` 共 33 个测试全部通过

---

## v2.1.4

### 修复：切到系统设置期间误在后台弹窗 / 探测回调持有已销毁界面

- mDNS 回调只在界面处于前台时弹输码框；用户去开发者选项开配对弹窗期间端口只做缓存，
  回到前台由 `onResume` 接手，避免后台弹窗与 `BadTokenException`。
- 界面销毁时断开探测回调（保留发现本身），不再让静态单例持有已销毁的 Activity。
- 10 秒无服务提示同样只在前台触发。

---

## v2.1.3

### 修复：MIUI / HyperOS 上通知栏始终没有配对输码入口

对照同类成熟实现的公开行为逐层比对后，定位到此前三次修复都绕开的真正原因：

- **MIUI 的「MIUI 样式」通知不渲染 RemoteInput 内联回复**。通知在通知栏里只显示
  标题，展开也没有输入框，点开只会跳到应用——这不是权限问题，通知权限全给也一样。
  同类实现在其配对引导页对 MIUI 用户有一条单独的红色警告：必须到
  「通知管理 → 通知显示设置」把通知样式改成「原生样式」，否则「无法从通知输入配对码」。
- **改为应用内输码为主路径**：mDNS 探测不再依赖前台服务存活，发现配对服务端口后
  直接在应用内弹输入框，输完立刻配对。通知栏输入降级为可选增强。
- **前台服务类型收敛为单一的 `shortService`**：`connectedDevice` 在 Android 14+
  有运行时前置条件，被拒后进程会在 5 秒内被杀，通知随之消失；`shortService`
  没有前置条件。`startForeground` 改传 `FOREGROUND_SERVICE_TYPE_MANIFEST`，
  由系统按清单取值，不再手工拼类型组合。清单同步移除 `specialUse` 及其 subtype 属性。
- **新增 ROM 判定**：MIUI / HyperOS 上引导文案与输码弹窗追加说明，明确告知
  应用内输码不需要改通知样式；想用通知栏输码则给出设置路径。
- **等待 10 秒仍无配对服务时主动提示**：配对服务只存在于「使用配对码配对设备」
  弹窗打开期间，用户没点这一项时 mDNS 永远等不到。现在给出说明并附跳转按钮，
  不再无限等待。
- 从开发者选项切回应用时自动接手已发现的端口，无需重新搜索。

---

## v2.1.2

### 修复：状态栏仍无配对通知（对齐成熟实现的前台服务形态）

- **服务类型改为 shortService 优先**：该类型无运行时前置条件，Android 14+
  上 `startForeground` 必然成功；配对是短操作，约 3 分钟时限由 `onTimeout`
  处理并提示重新发起。清单同步声明 `connectedDevice|shortService|specialUse`，
  `connectedDevice` 在时限内优先使用（无超时限制），被严格 ROM 拒绝时逐级回退。
- **发起配对时直接请求通知权限**：Android 13+ 通知权限被拒时前台服务通知会被
  系统静默隐藏（服务在跑、通知不显示），此前仅引导去设置页；现在直接弹出
  系统授权对话框，授予后自动继续启动配对服务。
- 配对服务启动后给出 Toast 反馈，用户可确认服务是否运行。
- 输码回复恢复 `getForegroundService` 投递：服务可能已被系统回收，须以前台
  服务方式重新拉起（通知栏点击属于用户交互豁免场景）。
- 引导文案明确：直接下拉通知栏输码即可，下拉不会关掉系统配对弹窗，
  不要按返回键/点取消关闭它。

---

## v2.1.1

### 修复：Android 11+ 上配对通知不出现 / 服务被系统终止

- **真因**：配对前台服务传入的前台服务类型在 Android 14+ 因 `connectedDevice`
  运行时前置条件未满足而被拒绝，`startForeground` 抛异常后服务 5 秒内被系统
  按「startForegroundService 后未 startForeground」终止，通知随之消失。
- 清单新增 `CHANGE_WIFI_MULTICAST_STATE` 权限：mDNS 发现真实需要组播，同时
  该声明满足 Android 14+ 对 `connectedDevice` 类型的前置条件。
- 服务类型显式传 `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`，被严格 ROM
  拒绝时回退 `specialUse` 类型（清单同步声明，含用途说明 property）。
- 配对码回复改用 `getService` 投递：服务已在前台运行，规避 Android 12+
  后台启动前台服务的限制。
- 引导文案明确：配对码弹窗必须保持打开，关闭弹窗后配对服务不再广播。

---

## v2.1.0

### 应用内无线调试启用与配对（无需电脑）

- **新增 `broker-wireless`（第 12 个 crate）**：覆盖 mDNS 拿到端口之后的全部字节级工作。
  `key.rs` 管理 RSA 2048 密钥、ADB 公钥结构编码与 TLS 客户端证书；
  `spake2.rs` 实现 Edwards25519 上的 SPAKE2（含 password scalar 低 3 位处理）；
  `crypto.rs` 提供 HMAC/HKDF-SHA256 与 AES-128-GCM 序列密码；
  `pairing.rs` 完成 TLS 1.3 握手、密钥导出、SPAKE2 交换与 PeerInfo 公钥注册；
  `adb.rs` / `tls.rs` 实现 24 字节小端 A_* 帧、`A_STLS` 升级、`A_AUTH` 回退与 `shell:` 通道；
  `orchestrate.rs` 把「配对 → 连接 → 启动」串成单次调用并返回稳定 JSON。
- **Java 侧自助链路**：新增 `WirelessPairingService`（前台服务 + mDNS 发现 +
  通知栏 RemoteInput 输入 6 位配对码）、`WirelessCore`（native 桥）与
  `ServiceStarter`（app_process 入口）。主界面「启用无线调试」改为引导开启无线调试
  并启动配对，不再需要电脑、USB 或任何第三方特权框架。
- **启动改为 app_process**：`broker-server` 抽出可复用的 `run()`；`android-ffi` 新增
  `WirelessCore.run` / `startBroker` 两个 JNI 入口。特权进程以 shell(2000) 在 APK 内
  加载 `libinstaller_core.so` 并常驻运行，仍保留启动即实测 uid、非 2000 退出码 3 的约束。
- **密钥与备份**：配对私钥保存在 `filesDir/adb_key.pk8`，备份规则与数据导出规则均排除。
- **文案与状态引导**：`state.rs` 的 guidance、关于页简介与通知文案统一为应用内自助流程；
  关于页开源组件清单补充 rustls / rcgen / rsa / aes-gcm / curve25519-dalek / base64。
- **测试**：`broker-wireless` 27 项单测，含 SPAKE2 固定向量、HKDF RFC 5869、
  本地 TLS 端到端配对与公钥注册、错误配对码失败路径、`A_STLS` 升级与 `shell:` 通道。
  全 workspace `cargo test` 通过，Android Java 与资源编译通过。

### 验证边界

真机的无线调试服务发现、通知栏配对码交互、设备端 TLS 实现差异、
`app_process` 加载 APK 内 native 库、SELinux 下的 abstract socket 连通性
与最终安装链路仍待设备验证，UI 与关于页已如实标注。

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
