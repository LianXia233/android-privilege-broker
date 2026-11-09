# 仓库协作规范 (Agent & Contributor Guidelines)

本文件是 `android-privilege-broker` 仓库内的核心协作规范与工作约定。所有自动化 Agent、开发者提交、构建与发布工作流均须严格遵循此规范。

---

## 1. 语言与提交规范

- **全中文交互**：Git 提交信息（Commit Message）、工作流名称、代码注释、设计与技术文档一律使用规范中文，确保可读性与一致性。
- **强制预检**：每次提交与推送前必须按 [第 3 节](#3-推送前脱敏检查清单) 与 [第 4 节](#4-本地构建与验证规范) 逐项验证，未通过者坚决禁止推送。
- **错误不得吞没**：编译、构建或测试失败必须输出完整堆栈与错误信息，严禁捕获并静默降级为成功。

---

## 2. 目录规范与换行格式

| 核心路径 | 职责与用途说明 |
| :--- | :--- |
| `crates/` | Rust Cargo Workspace（业务核心 `installer-core`、JNI 桥接与特权代理各模块） |
| `android/app/src/main/java/` | Android 客户端 Java/Kotlin 胶水层、系统服务、悬浮窗与 UI 交互 |
| `android/app/src/main/res/` | Android 矢量图标、多语言本地化与布局资源 |
| `android/app/signing/` | 可复现构建签名配置文件引用位置 (`release.keystore`) |
| `.github/workflows/` | GitHub Actions 云编译、测试与自动化 Release 发布工作流 |
| `scripts/` | 本地一键构建脚本 (`build.sh`) 与图标/测试样本生成脚本 |
| `CHANGELOG.md` | 版本全量演进历史与更新日志 |
| `README.md` | 项目主文档与开发者快速接入指南 |

> [!IMPORTANT]
> **换行符约束**：仓库内所有提交的文本文件一律使用 **LF** 换行符，严格禁止注入 CRLF。敏感配置信息坚决不得写入源码、资源、脚本、工作流或文档中。

---

## 3. 推送前脱敏检查清单

按顺序逐项核验，任一项命中必须立即中断推送并处理：

- [ ] **1. 暂存区审查**：运行 `git status --short` 与 `git add -n`，确认无任何多余临时文件。
- [ ] **2. 完整 Diff 审查**：执行 `git diff --cached` 逐行审阅，确认无敏感口令、令牌或隐私泄露。
- [ ] **3. 凭据检索**：检查是否含有任何访问令牌（PAT）、API Key、Secret 或类似格式字符串。
- [ ] **4. 口令检索**：排查明文口令、keystore 口令、key 口令是否泄露在非白名单区域。
- [ ] **5. 私钥检索**：确认无外部私钥、自建证书或未受控的 `*.keystore` / `*.jks` 二进制误入暂存区。
- [ ] **6. 个人信息检索**：排查设备唯一序列号、真实手机号、内网敏感 IP 地址与开发者私人路径。
- [ ] **7. 调试输出清理**：排查本地调试日志、临时测试配置、临时 benchmark 报告与冗余 print。
- [ ] **8. 产物隔离**：严格检查无构建产物（`.apk` / `.so` / `.aab` / `target/` / `build/`）被意外追踪。
- [ ] **9. 环境信息隔离**：无私有服务器域名、内部 CI 节点信息或私密仓库反向代理配置。
- [ ] **10. 凭据注入规范**：检查工作流与脚本中的 `secrets.*` 仅作为环境变量按需注入，严禁回显或落盘。

---

## 4. 本地构建与验证规范

```bash
# 1. 运行 Rust 全 Workspace 单元测试
cargo test --workspace

# 2. 执行端到端构建与静态检查
bash scripts/build.sh
```

- **产物校验准则**：严格确认具备 v1+v2 签名、四 ABI 动态库完整性、**16KB 内存页对齐**、版本号一致性及证书 SHA-256 指纹一致。
- **固定 Keystore 规则**：`android/app/signing/release.keystore` 随仓库公开分发；`build.gradle.kts` 中的 `storePassword`、`keyAlias`、`keyPassword` 为该 keystore 的内置常量，禁止因产品名变动而篡改，避免破坏证书指纹连续性。
- **重大版本迁移声明**：更换签名密钥必须提前评估对可复现构建的影响；`v2.0.0` 起 `applicationId` 已重构为全新包名，发版说明必须明确提示用户卸载旧包。

---

## 5. 发布与发版工作流

- **云编译构建**：由 `.github/workflows/build.yml` 在 `push main` 及手动 `workflow_dispatch` 时自动触发。
- **正式 Release 交付**：官方发布一律使用 GitHub Actions 云编译生成的 `PrivilegeBroker.apk`；Release 页面中的版本号、文件体积与 SHA-256 必须与回读数据一致。
- **仓库元信息同步**：涉及仓库迁移或地址变更时，须联动更新 `README.md`、文档 Shields 徽章、CI 工作流配置及各脚本内的仓库常量。

---

## 6. 禁止事项 (Strict Prohibitions)

> [!CAUTION]
> 1. **严禁硬编码敏感信息**：绝对禁止在代码中硬编码任何真实私有签名口令、访问令牌（PAT）或加密私钥。
> 2. **严禁调试痕迹入库**：禁止将本地临时报告、中间分析产物、崩溃转储文件纳入 Git 版本控制。
> 3. **严禁破坏既有业务**：禁止为了简单通过某项静态检查或单测而直接删除仍有效的业务逻辑。
> 4. **严禁虚标验证结论**：未经真机或沙箱环境完整验证的功能，严禁标注为「已通过测试」。
