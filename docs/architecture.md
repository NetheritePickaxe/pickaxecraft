# 架构与技术决策

状态：已更新（长期目标为独立 libGDX 轻量客户端；Headless 为第一阶段）
日期：2026-11-03

## 1. 项目定位

PickaxeCraft 是可嵌入其他程序的 Minecraft Java 版客户端核心库，使调用者能够连接 Minestom、维持会话、接收结构化事件、读取世界状态并执行基础操作。**Headless 库是第一阶段交付形态，不是最终产品边界。**最终产品是在独立仓库开发、与 Minestom 配合的轻量化图形客户端，计划使用 libGDX；本仓库向其提供版本化的客户端核心产物。

核心库不直接集成 Minestom。本仓库的 `testing:minestom` 只是联调测试宿主；最终客户端的单人游戏可以作为外部 Desktop 宿主的可选能力，在 Java 25 进程中启动内嵌 Minestom，再让客户端核心通过正常协议连接它。单人模式的服务端生命周期、世界存档和配置均不属于核心库。

### 初版目标

- 以库形式交付，而不是只提供一个命令行机器人。
- 支持同一进程内多个相互隔离的客户端会话。
- 跑通 Handshake、Status、Login、Configuration、Play 的完整连接链路。
- 支持 Minestom 离线模式登录、KeepAlive、传送确认、区块批次确认和基础移动。
- Desktop、Android 与 iOS 宿主尽可能复用同一份 Java 21 共享实现；Android 与 iOS 必须分别通过运行时验证。
- 为独立 libGDX 客户端提供不依赖 UI 工具箱的事件、命令和只读世界模型。
- 为后续世界同步、在线认证和协议扩展保留清楚边界。

### 本仓库非目标

- libGDX 依赖、渲染、GUI、输入系统、官方资源包和音频；这些属于独立的图形客户端仓库。
- Web/HTML5 宿主；原因见第 6 节。
- 完整原版物理、寻路、自动战斗和反作弊等价行为。
- 同时支持多个 Minecraft 协议版本。
- 服务端实现或 Minestom 插件。
- 在首个连接链路跑通前建设通用插件系统、脚本系统或可视化工作台。

## 2. 技术栈

| 范围 | 决策 |
| --- | --- |
| 语言 | Kotlin/JVM。协议后端是 Java 库，跨平台共用不靠 KMP，而靠「同一份字节码在两端运行」 |
| 构建 | Gradle Kotlin DSL、Wrapper、Version Catalog、`buildSrc` 约定插件 |
| 构建、CLI 与测试运行时 | JDK 25，Gradle 9.6.0 |
| 共享发布产物 | `protocol-core`、`client-core` 使用 Java 21 字节码；下游 Desktop 可运行在 Java 21 或更高版本 |
| 协程 | `kotlinx-coroutines`，会话内顺序处理 |
| 二进制协议 | MCProtocolLib 26.2，不自行实现 Reader/Writer、数据包与状态机 |
| 协议数据 | 数据包定义、注册表 NBT、标签 NBT 由协议库随包提供，本仓库只维护版本常量并与之对账 |
| 网络 | MCProtocolLib 内置 Netty（4.2.x）；Desktop 使用，Android/iOS 复用前分别验证 |
| Minestom | 本仓库仅用于 Java 25 测试宿主；可选单人游戏由外部 Desktop/Java 25 客户端集成 |
| 图形客户端 | 独立仓库使用 libGDX；本仓库只提供无 UI 依赖的客户端核心 |
| 配置与自有序列化 | JSON 等文本格式可使用 `kotlinx.serialization`；二进制 Minecraft 协议不使用它 |
| 日志 | 公共核心使用结构化诊断事件，不绑定具体日志实现；协议库自身走 slf4j |
| 测试 | Kotlin Test、JUnit 5、真实 Minestom 联调、字节码与依赖边界门控 |

Java 21 字节码不代表完整 JDK 21 API 在 Android 上可用：`protocol-core` 与后续共用模块禁止依赖虚拟线程、结构化并发、`java.net.http.HttpClient`、反射扫描、Unsafe 等 JVM-only 能力。

## 3. 协议策略

### 3.1 采用范围

协议后端采用 MCProtocolLib，覆盖：

- 776 的全部数据包模型、方向与连接状态映射。
- VarInt/String/UUID/NBT/Slot 等类型编解码。
- 分帧、压缩阈值切换、AES-CFB8 加密。
- Handshake、Status、Login、Configuration、Play 的状态机。
- 注册表与标签数据（jar 内的 `networkCodec.nbt` / `networkTags.nbt`）。
- KeepAlive 自动应答、transfer、代理与微软认证入口。

本项目不实现上述任何一项，也不复制其源码。

### 3.2 版本锁定与对账

- 锁定 `org.geysermc.mcprotocollib:protocol:26.2`，对应协议 776，与 Minestom `2026.10.07-26.2` 同步。
- `ProtocolVersion` 常量是外部契约；`ProtocolVersionTest` 直接读取协议库的 codec 常量做交叉校验，写错立即失败。
- 升级协议库或 Minestom 必须同时改两侧，并重跑 `ClientSessionTest` 与[协议适配契约](protocol-contract.md)的核对清单：编译通过不代表握手可用。
- 协议库不在 Maven Central，仓库地址与 group 白名单写在 `settings.gradle.kts`。

### 3.3 适配层边界

`protocol-core` 是唯一允许接触 MCProtocolLib 与 Netty 的模块：

- 对上层只暴露项目自己的类型；`io.netty.*` 与 `org.geysermc.mcprotocollib.*` 不得出现在 `client-core` 及以上的编译类路径。
- 协议库依赖一律用 `implementation` 引入，由构建门控确保没有穿透。
- 事件从协议库的收包回调转成本项目的语义事件；调用方不接触 `Packet`、`ByteBuf` 或 `Channel`。

### 3.4 现有项目的角色

- MCProtocolLib：运行时依赖，见上。
- PacketEvents：核对字段与行为的参照，不进入依赖。
- Minosoft：参考 Core/Headless/UI 分离与版本数据管理，不复制其 GPL 源码。
- Minecraft Wiki 与真实 Minestom 联调：互相补充，行为以实际握手为准。

## 4. 分层与数据流

```text
调用方 / CLI / 独立 libGDX 客户端（外部仓库）
                         |
                    client-core
         会话状态机、事件、注册表、世界模型
                         |
                   protocol-core
      协议适配：连接、状态、事件转换、版本对账
                         |
               MCProtocolLib + Netty
      分帧、压缩、加密、数据包编解码与传输
```

`client-core` 拥有会话生命周期与协议义务的归属（例如是否需要自己回应 KeepAlive）；`protocol-core` 只做协议事实的转换，不做业务判断。公共 API 不暴露 Netty、MCProtocolLib、libGDX 或宿主平台类型。

网络回调与协程 Actor 不承诺运行在 libGDX 渲染线程。核心库提供线程边界明确的事件与只读状态视图；独立客户端负责把所需更新调度到 libGDX 主循环，再执行 OpenGL、输入和资产操作。核心库不得直接调用 `Gdx`、GL context 或渲染资源，也不为逐帧渲染提前引入特定快照框架。

## 5. 会话与并发

- 每个 `MinecraftClient` 至多拥有一个活动 `Session`，每个 Session 独立持有连接状态、注册表、世界和事件流。
- 不使用全局可变单例保存玩家、会话、协议状态或世界数据。
- 网络读取最终进入单一会话 Actor，按到达顺序执行状态迁移和会话行为。
- 写出通过单一有序入口，避免不同协程交错写包。
- KeepAlive、传送确认等协议义务由核心主动完成，不能依赖调用者订阅事件；协议库已自动应答 KeepAlive 的部分需要在适配层显式声明归属，避免重复应答。
- 队列必须有界；背压、断开和事件溢出策略必须显式，不允许静默丢失影响会话正确性的消息。
- 关闭 Session 时依次停止接收新工作、取消协程、关闭传输并释放缓冲区；重复关闭保持安全。协议库的 `EventLoopGroup` 必须随会话关闭。
- 可恢复错误必须有明确恢复路径；遇到未知包、非法状态迁移或编码失败时，应抛出带上下文的错误，不猜测默认值。

### 5.1 M1 已落地的具体形态

线程与所有权：

```text
Netty 网络线程（每连接一个，线程名前缀 pickaxe-mcpl）
  └─ McplConnection：协议库回调 → 语义事件 → trySend 到有界 Channel
会话 Actor（client-core，单个协程）
  └─ 状态迁移、事件分发、终止处理；按到达顺序执行
调用方
  └─ state: StateFlow / events: Flow（单消费者），永不跑在网络线程上
```

- `EventLoopGroup` 由 `McplConnection` 持有，不建立进程级共享组；关闭时 `shutdownGracefully`
  并等待终止，因此“关闭后线程仍存活”是一个可断言的事实，而不是靠 JVM 退出兜底。
- 包处理执行器用直接执行器（`Runnable::run`），把回调固定在该连接的网络线程上；
  协议库默认会再引入一个静态共享的包处理线程池，那会让同一会话的回调出现在两个线程上。
- 网络线程到 Actor 之间是**有界** Channel；写满即终止该会话并报告 `ClientException.EventOverflow`。
  M1 选择“终止并报告”，而不是静默丢弃或无限缓冲；按事件类别细分留到有真实需求时再做。
- 事件流是单消费者；终止事件（`Closed`/`Failed`）恰好一次，之后流结束。
- 失败在适配层归层（`CONNECT`/`NETWORK`/`PROTOCOL`），上层按层选择处理策略，
  不需要识别协议库或 Netty 的异常类。

Kotlin 协程只出现在 `client-core` 与组合根；`protocol-core` 是纯回调适配层，
不引入协程运行时，也就不会把调度器选择强加给宿主。

协议库行为契约（回调顺序、KeepAlive 归属、状态切换点、断开语义）见
[协议适配契约](protocol-contract.md)；该文档同时是升级检查清单。

### 5.2 M2 已落地的具体形态：出生

进入 play 之后，协议要求本端回应的四件事全部由适配层完成，调用方只观察结果：

| 时机 | 本端动作 | 上层看到的事实 |
| --- | --- | --- |
| 进入 `CONFIGURATION` | 发送 `ServerboundClientInformationPacket` | —（服务端据此确定视野距离） |
| 收到 `ClientboundLoginPacket` | 无 | `ProtocolEvent.JoinedWorld` → `ClientEvent.JoinedWorld` |
| 收到 `ClientboundPlayerPositionPacket` | 回 `ServerboundAcceptTeleportationPacket` | `PositionUpdated`（相对坐标已叠加） |
| 收到 `ClientboundChunkBatchFinishedPacket` | 回 `ServerboundChunkBatchReceivedPacket`；首批后补发 `player_loaded` | `ChunksReceived(chunks, total)` |

`client-core` 把上述事实汇总成 `Session.world: StateFlow<WorldSnapshot?>`（实体 id、维度、
游戏模式、位置、累计区块数）。`SessionState.PLAY` 只表示协议状态，出生进度由 `world` 表达，
不为“已出生”另造状态——服务端并不会通知“出生完成”，本库也不猜测。

适配层不解析区块内容：`ChunksReceived` 只数数量。方块调色板、光照与实体属于 M4 的世界模型。

## 6. libGDX 与平台约束

共享发布产物使用 Java 21 字节码，由 `verifyBytecode`（major 65）自动断言；构建、CLI 与测试仍使用 Java 25。libGDX 官方当前推荐 JDK 17/21，因此下游图形客户端不被迫使用 Java 25；启用 Minestom 单人模式的 Desktop 组合根除外。

### 6.1 下游渲染路线：GLES + ANGLE，跟踪原版 Vulkan 演进

渲染实现全部位于外部 libGDX 客户端，本仓库不引入相关依赖。下游采用以下基线：

- 渲染代码以 libGDX `GL20` / OpenGL ES 2.0 为共同基线，优先保证 Desktop、Android 与 iOS 可复用；不得把 GLES、ANGLE 或 Vulkan 类型传入客户端核心。
- Desktop 使用 `gdx-lwjgl3-angle` + `GLEmulation.ANGLE_GLES20` 做接入验证，目标是在 Windows/Linux 上由 ANGLE Vulkan 后端执行。macOS 走 ANGLE Metal，Android 优先原生 GLES，iOS 评估 libGDX MetalANGLE；不能把“使用 ANGLE”等同于“正在使用 Vulkan”。
- ANGLE 上游虽支持更高版本 GLES 到 Vulkan 的翻译，但 libGDX 官方 ANGLE 扩展目前只承诺 GLES 2.0，并有平台 natives/加载问题。Vulkan 兼容必须通过运行时 renderer/vendor、实际后端和渲染测试结果证明，不能由依赖名称推断。
- MC Java 26.2 已提供实验性 Vulkan 后端，要求 Vulkan 1.2、dynamic rendering 与 push descriptors，但默认仍为 OpenGL。下游跟踪的是这一双后端与回退方向：保留 GLES/ANGLE 可用路径，验证 Vulkan 路径，不在初版强制原生 Vulkan。
- GLES 经 ANGLE 在 Vulkan 上运行不等于拥有原版 Vulkan 渲染器的功能或性能。若后续原版效果无法由 GLES 2.0 表达，再在外部客户端单独评估 GLES 3.x 或原生 Vulkan 后端，不重写核心库。

| 平台 | 当前决策 | 验证门控 |
| --- | --- | --- |
| Windows / Linux / macOS Desktop | 首要支持目标 | Java 21 共享库可嵌入 LWJGL3 宿主；M4 用独立 libGDX 消费工程验证；启用内嵌 Minestom 单人模式时宿主使用 Java 25 |
| Android | 明确目标，尚未承诺支持 | 构建链已探针通过（minSdk 26）；运行时（ART + Netty NIO + 资源回收）仍待 M3 |
| iOS | 尽力保留的候选平台 | macOS/Xcode + RoboVM spike；验证 Java 21、Kotlin、协程与 Netty |
| Web / HTML5 | 不做 | 当前 Kotlin/JVM + Netty 路线与 libGDX GWT 后端不匹配 |

具体约束：

- Netty 是协议库的一部分，无法从 Android/iOS 路径直接剥离。`netty-common` 显式识别 Android 并退到 no-Unsafe 路径，但**尚未在设备上验证**。
- Android 的构建链已经实测（见 [Android 反糖探针](android-dex-probe.md)）：d8 可把共享产物与全部依赖 dex 成功；`minSdk` 下限为 26（`MethodHandle` 只在 Android O 起可用，来源是 `netty-common` 与 MinecraftAuth 的 httpclient）；`java.lang.Record` 已被处理，但 `java.util.HexFormat` 仍留在 dex 里，需要 API 34，M5 在线认证前必须处理。
- Android 打包必须 `exclude(group = "org.cloudburstmc.math", module = "api")`：该构件的 57 个类全部包含在 `:immutable` 中，同时打包会让 d8 直接报重复类，而桌面 JVM 会静默容忍。
- 仍未验证：Netty 在 ART 上的类加载与 NIO 行为、运行时线程与内存回收、no-Unsafe 降级。在这些拿到证据前，Android 不算支持；通过前也不引入独立传输实现。
- libGDX 官方称 RoboVM 执行 Java 字节码、其他 JVM 语言“应该可用但未测试”。因此 iOS 不能由 Desktop 或 Android 的结果推断；若失败，先记录具体不兼容点，再评估平台适配或替代传输。
- libGDX 官方明确说明 Kotlin 不能使用 GWT HTML5 目标，Netty 也不适用于 Web。当前不引入 Kotlin/JS、TeaVM 或独立 Web 协议栈；只有出现新的明确需求并重新评审时才改变此决策。
- 各宿主负责生命周期、网络安全配置、权限和账户秘密存储；核心不持有 Activity、Context、UI、OpenGL 或 RoboVM 对象。
- LWJGL、libGDX natives、资产与渲染资源由独立客户端管理，不进入本库产物。
- Minestom 使用 Java 25，可选单人模式因此只由外部 Desktop/Java 25 组合模块承载。该模块管理服务端配置、世界存档、端口和启停；核心通过回环网络使用同一协议连接，不能为了“单人优化”直接共享服务端可变对象。本地直连传输只有经过独立验证后才可引入。
- Android/iOS 默认连接外部服务端，不承诺内嵌 Minestom；若未来提出该需求，必须单独验证对应运行时对 Minestom Java 25 及其依赖的支持。
- 密码学使用协议库与平台经过验证的实现，不自研 RSA/AES/SHA 算法。

## 7. 测试与质量门控

- 版本常量与协议库 codec 常量对账，注册表 NBT 可加载。
- `ClientSessionTest` 通过公共 API 真实连接 Minestom：Status 查询返回 776，离线登录进入 play 并收到注册表数据；另覆盖会话隔离、服务端断开、连接失败、关闭后线程回收与事件溢出。依赖组合或版本错配时，编译仍会通过，只有这些用例会失败。
- Minestom 测试覆盖真实状态迁移，不用自有 Mock 替代全部联调。
- 构建门控：字节码主版本、模块依赖边界（Netty / MCProtocolLib / Minestom 不得出现在业务模块）。
- 跨仓库门控：独立 libGDX 消费工程验证发布产物和线程桥接；Android/iOS 分别在真实运行时验证，不能用 JVM 单测替代。
- 并发测试使用确定性协调，不依赖 `sleep`；JVM 内单例（Minestom）通过按类分叉规避。
- 每次阶段收尾运行 `./gradlew build`，新增模块后同步更新门控。

## 8. 扩展条件

只有出现真实需求时才增加以下复杂度：

- 第二个协议版本：先引入版本目录与独立适配，不把 `if (version...)` 扩散到会话逻辑。
- 在线认证：独立认证适配，OAuth 设备流程、令牌存储与加密会话分离。
- Android/iOS 支持：以各自 spike 结果为前提，通过后再决定是否需要独立的传输层或降级方案。
- 渲染或 GUI：已确定由独立 libGDX 客户端实现，只能依赖客户端公共模型，不得进入 `client-core` 或 `protocol-core`。
- Web 支持：当前已否决；只有新的产品需求足以覆盖独立技术栈与验证成本时才重新评估。
- 性能优化：以分配、吞吐、延迟或内存测量为依据，并保留可理解的安全实现作为行为参照。
