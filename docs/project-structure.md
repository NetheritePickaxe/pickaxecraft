# 项目结构

状态：M0–M2 已完成（连接链路与出生），M4a–M4e 已完成（区块内容、实体跟踪、重生换维度、玩家身份、聊天）；Headless 为第一阶段，独立 libGDX 客户端为长期下游
日期：2026-11-04

## 1. 结构原则

- 只创建具有明确责任和真实调用方的模块，不为未来概念建立空模块。
- 协议实现来自 MCProtocolLib；本项目只在 `protocol-core` 里做适配，别处不得直接依赖它。
- 应用入口只承担组合根、配置和命令，不承载协议或世界逻辑。
- 本仓库只发布客户端核心库；libGDX 图形客户端及 Desktop/Android/iOS 应用宿主位于独立仓库，通过版本化产物依赖本库。
- 公共 API 不依赖 libGDX、OpenGL、Android、RoboVM 或 Minestom 类型，避免核心库反向绑定某个宿主或服务端实现。
- Minestom 只出现在 `testing:minestom`。可选单人游戏集成属于外部客户端的 Desktop/Java 25 组合模块，不在本仓库创建生产依赖。
- 测试服务不参与运行时发布。
- 当前模块为 `:protocol-core`、`:client-core`、`:app`、`:testing:minestom`；`client-core` 在 M1 随首个真实调用方创建。

## 2. 目标目录树

```text
pickaxecraft/
├── protocol-core/                  # Java 21 字节码：MCProtocolLib 的唯一适配层
│   └── src/
│       ├── main/kotlin/
│       │   └── org/pickaxe/protocol/
│       │       ├── ProtocolVersion.kt      # 版本常量（与协议库对账）
│       │       ├── ServerAddress.kt
│       │       ├── ServerStatus.kt
│       │       ├── ProtocolEvent.kt        # 事件、阶段、断开原因、失败分层
│       │       ├── ProtocolConnection.kt   # 对上层暴露的唯一入口
│       │       ├── world/
│       │       │   ├── ChunkColumn.kt      # 不可变区块列与 section（按坐标读方块/群系）
│       │       │   ├── TrackedEntity.kt    # 不可变实体快照（位置、朝向、类型、自定义名称）
│       │       │   └── RemotePlayer.kt     # 玩家列表条目（UUID、用户名、模式、延迟）
│       │       ├── chat/
│       │       │   └── ChatMessage.kt      # 聊天消息、来源、格式（键 + 参数顺序）
│       │       └── internal/               # McplConnection、ComponentText、
│       │                                   # ChunkDecoder、DimensionRegistry、ChatTypeRegistry
│       └── test/kotlin/
│           └── org/pickaxe/protocol/
│               ├── ProtocolVersionTest.kt
│               └── internal/               # ChunkDecoderTest、ChatTypeRegistryTest
│
├── client-core/                    # Java 21 字节码：公共客户端 API、会话、事件
│   └── src/main/kotlin/org/pickaxe/client/
│       ├── MinecraftClient.kt      # 入口：status / connect / close
│       ├── Session.kt              # 会话 Actor、有界缓冲、区块/实体/玩家缓存、关闭语义
│       ├── SessionState.kt
│       ├── ClientEvent.kt
│       ├── ClientException.kt
│       ├── ClientOptions.kt
│       └── WorldSnapshot.kt        # 维度、本端实体 id、位置与区块数
│
├── app/                            # JVM 25：Desktop CLI 与服务端联调入口
│   └── src/
│       ├── main/kotlin/
│       └── test/kotlin/
│
├── testing/
│   └── minestom/                   # JVM 25：可重复启动的 Minestom 联调服务 + 握手用例
│       └── src/
│           ├── main/kotlin/        # Harness：启动、停止、服务端里程碑
│           └── test/kotlin/        # ClientSessionTest、HarnessLifecycleTest、ShutdownHookTest
│
├── buildSrc/                       # 只放构建约定，不放业务代码
├── docs/                           # 架构、结构、路线与决策记录
├── gradle/                         # Wrapper、Version Catalog、Daemon JVM 配置
├── settings.gradle.kts
└── gradle.properties
```

源码包按职责划分，不按“每层一个接口”机械拆分。

## 3. Gradle 模块与产物属性

| Gradle 路径 | 类型 | 字节码目标 | 是否发布 | 状态 |
| --- | --- | --- | --- | --- |
| `:protocol-core` | Kotlin JVM | Java 21（major 65） | 是 | 已创建 |
| `:client-core` | Kotlin JVM | Java 21（major 65） | 是 | 已创建（M1） |
| `:app` | Kotlin JVM 应用 | Java 25（major 69） | 否 | 已创建 |
| `:testing:minestom` | Kotlin JVM 测试工具 | Java 25（major 69） | 否 | 已创建 |

共享产物一律用 Java 21 字节码：既符合 libGDX 当前 JDK 17/21 开发基线，也避免 Desktop“跑得起来”但 Android/RoboVM 无法消费。构建、CLI 与测试模块可以使用 Java 25，但不能把 major 69 泄漏到共享发布产物。

### 3.1 为什么不再有 transport 模块

协议库自带 Netty 传输，`ClientNetworkSession` 只接受 `ChannelFactory`，没有可替换的传输 SPI。因此不存在“桌面用 Netty、Android 用 Socket”的分叉：两端跑同一套 Netty 栈，Android 的可行性由 M3 spike 判定，而不是靠另写一个传输层绕过。

### 3.2 Android 的现状

Android 尚未验证。已知风险与待验证项见 `docs/architecture.md` 第 6 节；在 spike 通过前不引入 AGP、KMP 或 Android 专属模块。

## 4. 依赖方向

```text
独立 libGDX 客户端（外部仓库） ──┐
                                  ├─► client-core ──► protocol-core ──► MCProtocolLib ──► Netty
app（CLI） ───────────────────────┘
外部 desktop-singleplayer（可选，Java 25） ──► client-core + Minestom

testing:minestom ──► client-core + protocol-core（版本对账）+ Minestom
```

`desktop-singleplayer` 是外部客户端按需创建的组合模块，不是当前承诺的模块名。它通过客户端公共 API 连接本地 Minestom，不把服务端对象或 Minestom 类型传入核心库。

具体规则：

1. `protocol-core` 不依赖 `client-core`、Minestom 或应用模块。
2. `client-core` 只依赖 `protocol-core` 与语言级库，不直接依赖 MCProtocolLib 或 Netty。
3. `protocol-core` 对 MCProtocolLib 一律用 `implementation`，不向编译类路径外泄。
4. `app` 是桌面组合根，只依赖 `client-core`，不接触协议库与 Netty。
5. `testing:minestom` 可以依赖公开 API，但生产模块不能依赖测试服务。
6. `testing:minestom` 的联调用例只走 `client-core` 公共 API；它同时是“协议实现没有穿透到上层”的证据。
7. 任何共享发布模块都不得依赖 Minestom。外部客户端的可选单人组合模块可以依赖 Minestom，但只能以 Java 25 Desktop 产物发布，并通过客户端公共 API 交互。

## 5. 公共 API 归属

`client-core` 是初版唯一公共客户端入口，包含：

- `MinecraftClient`：`status` / `connect` / `close`，一个实例至多一个活动会话。
- `ClientOptions`：连接、登录、状态查询超时、事件缓冲容量与聊天记录条数。
- `Session`：`state`（StateFlow）、`events`（单消费者 Flow）、`world` / `registries`
  （会话级只读快照）、区块查询（`chunk` / `blockStateAt` / `biomeAt` / `loadedChunks`）、
  实体查询（`entity` / `entities` / `entityCount`）、玩家查询（`players` / `player` /
  `playerCount` / `nameOf`）、聊天记录（`chat` / `chatCount`）与幂等 `close`。
- `ClientEvent` / `ClientException`：有序事件（含 `JoinedWorld` / `PositionUpdated` /
  `ChunksReceived` / `EntitySpawned` / `EntityRemoved` / `PlayerListChanged` /
  `ChatReceived` / `ChatDeleted` / `Respawned`）与可分类的失败。
- `WorldSnapshot` / `Position`：出生过程的只读状态。方块内容走 `Session` 的区块查询，
  不塞进每次更新都要复制的快照。

`protocol-core` 只提供适配层事实（`ServerAddress`、`ServerStatus`、`ProtocolEvent`、
`ProtocolConnection`，以及 `ChunkColumn` / `ChunkSection` / `TrackedEntity` / `RemotePlayer` /
`ChatMessage` 这类中立数据），
作为 `client-core`
的 `api` 依赖；它的内部实现（协议库类型、Netty 会话）不是公共 API，调用方不应也无法看到
数据包对象。

公共 API 是跨仓库契约：不得暴露 libGDX、OpenGL、Android、RoboVM、Netty 或 MCProtocolLib 类型。网络线程到 libGDX 主循环的调度由下游客户端负责；本库只保证事件和只读状态的线程语义清楚。

## 6. 暂不创建的模块

| 模块 | 暂不创建的原因 | 何时再评估 |
| --- | --- | --- |
| `client-api` / `client-impl` | 当前只有一个实现，提前拆分只增加跳转 | 需要独立稳定 API 或有第二个实现时 |
| `transport-*` | 协议库内置 Netty，没有可替换的传输 SPI | 出现真实验证过的替代传输需求时 |
| `auth-microsoft` | 离线 Minestom 链路优先 | M5 在线认证开始时 |
| `world` | 初版世界状态仍由会话统一拥有 | 世界模型独立复用需求出现时 |
| `rendering` / `gui` / `libgdx-*` | 图形客户端已确定在独立仓库开发，本库不承载渲染与 UI | 永不进入本仓库；跨仓库 API 变化时调整 `client-core` 契约 |
| `singleplayer` / `server-minestom` | 核心库不集成 Minestom；单人游戏是外部 Desktop/Java 25 宿主能力 | 外部客户端确认单人模式需求后，在其仓库创建可选组合模块 |
| `android-app` / `ios-app` / `desktop-app` | 应用宿主属于独立客户端仓库；本仓库只保留最小平台探针 | 对应平台 spike 需要验证核心产物时 |

## 7. 构建约定

三个预编译约定插件位于 `buildSrc/src/main/kotlin`：

| 插件 | 作用 | 使用者 |
| --- | --- | --- |
| `pickaxe.jvm.shared` | Kotlin/JVM、Java 21 字节码、`explicitApi()`、JUnit 5、字节码与边界校验 | `:protocol-core`、`:client-core` |
| `pickaxe.jvm.library` | Kotlin/JVM、Java 25 字节码、`explicitApi()`、JUnit 5、字节码与边界校验 | 经 CLI 传递到其余模块 |
| `pickaxe.jvm.cli` | 在上者基础上应用 `application`，并强制 UTF-8 输出 | `:app`、`:testing:minestom` |

约定插件不写死模块信息：`mainClass`、禁止依赖项都由模块自己声明。`buildSrc` 预编译脚本没有 `libs` 类型安全访问器，版本统一通过 `VersionCatalogsExtension` 读取 `gradle/libs.versions.toml`。

`check` 里包含两类自动门控：

- `verifyBytecode`：断言 class 文件主版本为 65（shared）或 69（library）。
- `verifyModuleBoundaries`：扫描编译类路径；`pickaxe.jvm.library` 禁止 `io/netty/`、`org/geysermc/mcprotocollib/`、`net/minestom/`，`pickaxe.jvm.shared` 默认只禁止 `net/minestom/`（协议库允许出现在该模块内部），`client-core` 显式收紧到同样三项，`testing:minestom` 作为 Minestom 宿主显式放开。

版本来源必须唯一：依赖版本在 `gradle/libs.versions.toml`，协议号在 `protocol-core` 的 `ProtocolVersion` 并与协议库 codec 常量对账。
