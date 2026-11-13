# 变更记录

本文件记录面向使用者的变更。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)，但 0.x 阶段按下面的兼容政策执行。

## [未发布]

## [0.1.0] - 2026-11-13

首个发布版本：标准 JVM 上的离线（offline-mode）Minecraft Java 版客户端核心库，
联调目标为 Minestom 26.2（协议 776）。

### 范围

- `io.github.netheritepickaxe:protocol-core`：协议适配层。把 MCProtocolLib 的数据包转成
  语义事件与只读世界模型；Netty、MCProtocolLib、Adventure 的类型不会出现在它的公共 API 上。
- `io.github.netheritepickaxe:client-core`：会话层。离线登录、状态迁移、有界事件流、
  区块/实体/背包/聊天/命令/转移等只读缓存与上行操作。
- `pickaxecraft` 命令行（`:app` 的 `distZip` / `distTar`）：状态查询、持续会话、
  动作探针，以及 `--json` 的 JSONL 输出。
- `testing:minestom`：联调服务端（不在发布物内），用于端到端测试与发布冒烟。

### 新增

- `Session` 的只读世界模型与上行操作：区块内容与方块状态、光照、高度图、方块实体，
  实体跟踪与属性/状态效果/血量，玩家列表，聊天读写，背包与容器点击、拖拽，
  方块挖掘与放置，移动与骑乘，命令树与补全，跨服转移跟随，插件消息收发。
- `MinecraftClient.status`：服务端列表查询，返回版本、人数、MOTD 与延迟。
- 分类失败模型 `ClientException`（连接失败、网络错误、服务端断开、协议错误、超时、
  事件溢出、已关闭），事件流以 `Closed` / `Failed` 恰好一次收尾。
- CLI `--json`：每行一个 JSON 对象的 JSONL 输出，`connect` 流式输出事件并在结束时给出
  结果对象，`status` 输出一个结果对象；退出码与 `ok` 字段一致。
- 发布产物：POM 的 `name` / `description` / `url` / `scm` / `developers` / `licenses`，
  jar 内 `META-INF/LICENSE` 与 `META-INF/THIRD-PARTY-NOTICES.md`，
  manifest 的 `Implementation-Title` / `Implementation-Version`，源码 jar 与
  Dokka 生成的 javadoc jar。
- 签名能力：`pickaxe.publish` 可从环境变量或 Gradle 属性读取内存 PGP 密钥，
  默认不启用（`signing.isRequired = false`）。
- 本地 staging 仓库（`build/staging-repo`）与 `tools/release-smoke` 双消费者冒烟：
  Gradle 与纯 Maven 工程各完成一次真实的 `status` 查询。

### 变更

- 发布坐标统一为 `io.github.netheritepickaxe`；共享产物固定为 Java 21 字节码，
  CLI 与构建使用 JDK 25。
- Netty 升级到 4.2.20.Final，并用 `netty-bom` 对齐 MCProtocolLib 与 Minestom 带进来的
  传递模块；`verifyNettyVersion` 在构建期校验全链路版本一致。
- 区块缓存增加修订号（`chunkRevision`），卸载与关闭时推进，便于下游判断缓存失效。
- **API 变更**：`PluginMessage.data` 改为 `data()`，每次返回独立副本；内部字节不再可写。
- **API 变更**：光照新增只读 `LightLayer`（`levelAt` / `copyInto` / `sizeBytes`），
  `LightPatch.sky` / `block` 改为 `Map<Int, LightLayer>`；解码结果仍零拷贝共享。
- **API 变更**：`ProtocolConnection` 标注 `PickaxeLowLevelApi`，直接使用需显式 `@OptIn`；
  常规路径仍是 `client-core` 的 `MinecraftClient` / `Session`。
- 版本资产（物品键名/原型、属性、效果、生物分层）改为一个可重跑导出器，
  `:testing:minestom:exportRegistries` 一次生成五张表，二次导出零 diff。

### 修复

- 会话 `finish()`（服务端结束会话）清理区块时未推进修订号，导致下游把旧快照当成新数据。
- CLI `connect` 在终止事件为失败时仍以 0 退出；现在返回非零退出码并在 `--json` 结果中
  给出错误分类。

### 平台

| 平台 | 状态 | 证据与边界 |
| --- | --- | --- |
| 标准 JVM（Windows / Linux / macOS） | 已验证 | 构建、CLI 与联调使用 JDK 25；共享产物为 Java 21 字节码，发布冒烟按 Java 21 消费。 |
| Android | 候选，未验证 | 构建链探针通过（D8 可消费、`minSdk` 26）；ART + Netty NIO + 线程/资源回收未验证。 |
| iOS | 候选，未验证 | 需要 macOS/Xcode + RoboVM 环境验证；未验证前不作支持声明。 |
| Web / HTML5 | 不做 | Kotlin/JVM + Netty 与浏览器运行时不匹配。 |

### 已知限制

- 只支持离线（offline-mode）登录：没有 Microsoft 认证、加密与压缩状态机，不能连接
  开启在线验证的正版服务器。
- 每个会话各自持有 `EventLoopGroup`，多会话时线程数线性增长；共享事件循环计划在 0.2 处理。
- 不包含物理、碰撞、寻路或任务调度；`Session` 是单一入口，能力视图与拆分计划在 0.2。
- 客户端不发送聊天签名：`sendChat` 只适用于未启用强制安全聊天的服务端。
- 挖掘进度与服务端的最终认可仍需联调确认；`--dig` 只报告“已发送”和观察到的方块变更。
- `--json` 是 0.x 实验契约：字段可随小版本调整，调用方应按 `type` 分支处理并对未知字段宽容。
- 通过纯 Maven 消费时，POM 已包含运行期依赖（协议库、Netty 等），但 MCProtocolLib
  位于 `https://repo.opencollab.dev/main/`，消费方必须自行声明该仓库。

### 0.x 兼容政策

- 0.x 阶段不承诺源码或二进制兼容：公共 API 可能在小版本内变更且不提前通告。
- 请锁定精确版本（不使用动态版本或 `latest.release`）；首个下游消费者出现后冻结 API 基线
  并接入二进制兼容校验，1.0 起遵循语义化版本。

### 升级方式

Gradle：

```kotlin
dependencies {
    implementation("io.github.netheritepickaxe:client-core:0.1.0")
}
```

Maven：

```xml
<dependency>
  <groupId>io.github.netheritepickaxe</groupId>
  <artifactId>client-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

两者都需要在仓库列表中包含 `https://repo.opencollab.dev/main/`（MCProtocolLib 的发布仓库）。
升级到 0.1.0 时，下游代码若直接使用 `protocol-core` 的 `ProtocolConnection`，
需按 0.2 的 opt-in 计划关注后续变更通告；`client-core` 的常规用法无需调整。

[未发布]: https://github.com/NetheritePickaxe/pickaxecraft/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/NetheritePickaxe/pickaxecraft/releases/tag/v0.1.0
