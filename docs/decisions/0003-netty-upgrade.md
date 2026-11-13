# 决策 0003：Netty 升级到 4.2.20.Final 并用 BOM 对齐

状态：已采纳
日期：2026-11-13
涉及：`gradle/libs.versions.toml`、`protocol-core/build.gradle.kts`、`buildSrc/src/main/kotlin/pickaxe/tasks/VerifyNettyVersion.kt`、`pickaxe.jvm.shared` / `pickaxe.jvm.library`
关联审查：`docs/reviews/2026-11-12-orientation-and-deliverability-review.md`
历史记录：`docs/decisions/0002.md` 保留 2026-11-03 的评估，不在本次升级中改写。

## 1. 原因

审查把 Netty 版本列为公开可发布的首要阻断项：

- 版本目录固定在 `4.2.1.Final`，已经落后于 4.2.x 的安全修复窗口。
- Netty 是 MCProtocolLib 的传输实现，也是 Android/iOS 候选路径的硬依赖，不能从客户端剥离。
- 只升级构建脚本里直接声明的四个模块不够：`netty-resolver`、`netty-codec-base`、
  `netty-transport-native-unix-common` 由传递依赖带入，会继续停在旧版本。安全公告按模块发布，
  混合版本意味着修复不完整。

## 2. 目标版本与对齐方式

- 目标版本：`4.2.20.Final`，写在版本目录的 `netty` 项。
- `protocol-core` 增加 `implementation(platform(libs.nettyBom))`；`netty-bom` 的约束传播到
  `client-core`、`app`、`testing:minestom` 与示例模块的运行时类路径。
- 直接依赖仍按实际用到的模块声明（`netty-buffer` / `common` / `transport` / `handler`），
  并用 `exclude(group = "io.netty", module = "netty-all")` 排除聚合 POM。
- 新增 `verifyNettyVersion`（`buildSrc/src/main/kotlin/pickaxe/tasks/VerifyNettyVersion.kt`），
  在 `pickaxe.jvm.shared` 与 `pickaxe.jvm.library` 中挂到 `check`：解析本模块的
  `runtimeClasspath`，任何 `io.netty` 模块版本与版本目录不一致即失败。检查的是解析结果，
  不是构建脚本里写了什么。

## 3. 验证结论

- `dependencyInsight --dependency netty-common --configuration runtimeClasspath`：
  `protocol-core`、`client-core`、`testing:minestom` 三处均为 `4.2.20.Final`；
  `netty-resolver`、`netty-codec-base`、`netty-transport-native-unix-common` 同样在 4.2.20。
- `.\gradlew.bat build --console=plain`：通过，包含 `verifyNettyVersion`、字节码版本与模块边界门控。
- 全量联调 `:protocol-core:test :samples:libgdx-viewer:test :testing:minestom:test --rerun-tasks`：
  通过，23 个 `TEST-*.xml`、164 项、0 失败、1 项跳过（Windows 无 SIGTERM，由 Linux CI 覆盖）；
  覆盖离线登录、出生、区块与光照、压缩包边界、插件消息、关闭与网络线程回收。
  MCProtocolLib 26.2 在 4.2.20 上没有观察到协议行为变化。
- 「编译通过」不作为兼容性证据：上述结论来自真实 Minestom 握手与完整 E2E。

## 4. 对 Android / iOS 候选路径的影响

- 没有新增 Netty 模块；4.2.x 的 Android no-Unsafe 分支仍在 `netty-common`，但 ART 上的
  类加载、NIO 与线程回收仍未验证。
- 本次升级不改变平台口径：Android/iOS 仍是候选，M3 不阻断 0.1.0。
- 若后续在设备上发现 4.2.20 的 ART 回归，按第 5 节回退并记录具体不兼容点。

## 5. 回滚条件

满足任一条件即回退到仍包含目标安全修复的 4.2.x 版本（例如审查建议的 `4.2.16`），
记录证据后再决定后续升级：

- MCProtocolLib 26.2 的真实握手或 E2E 出现可复现的协议行为变化。
- `verifyNettyVersion` 无法在全部模块对齐，例如 Minestom 强制更高版本且冲突不可解。
- Android/iOS 候选路径出现 4.2.20 特有、无法绕过的不兼容。

回滚只改版本目录中的 `netty`，BOM、校验任务与本文档的解释保持不变；
不得通过放宽检查或忽略失败来“回滚”。
