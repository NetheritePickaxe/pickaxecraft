# 架构与技术决策

状态：已确定  
日期：2026-11-02

## 1. 项目定位

PickaxeCraft 初版提供可嵌入其他程序的 Minecraft Java 版 Headless 客户端库，使调用者能够连接 Minestom、维持会话、接收结构化事件并执行基础操作。

### 初版目标

- 以库形式交付，而不是只提供一个命令行机器人。
- 支持同一进程内多个相互隔离的客户端会话。
- 跑通 Handshake、Status、Login、Configuration、Play 的完整连接链路。
- 支持 Minestom 离线模式登录、KeepAlive、传送确认、区块批次确认和基础移动。
- Desktop 与 Android 共用协议、会话和世界模型。
- 为后续世界同步、在线认证和协议扩展保留清楚边界。

### 初版非目标

- 渲染、GUI、输入系统、官方资源包和音频。
- 完整原版物理、寻路、自动战斗和反作弊等价行为。
- 同时支持多个 Minecraft 协议版本。
- 服务端实现或 Minestom 插件。
- 在首个连接链路跑通前建设通用插件系统、脚本系统或可视化工作台。

## 2. 技术栈

| 范围 | 决策 |
| --- | --- |
| 语言 | Kotlin，公共代码放在 Kotlin Multiplatform `commonMain` |
| 构建 | Gradle Kotlin DSL、Wrapper、Version Catalog、`buildSrc` 约定插件 |
| 构建运行时 | JDK 25，现有 Gradle 9.6.0 |
| Desktop 目标 | JVM 25 |
| Android/共享目标 | Java 21 字节码，固定支持该目标的 AGP/D8/R8 版本并持续构建验证 |
| 协程 | `kotlinx-coroutines`，会话内顺序处理，平台 I/O 使用合适调度器 |
| 二进制协议 | 自有 Reader/Writer、数据包模型和编解码，不依赖 Java 序列化 |
| 协议生成 | JVM 25 构建期代码生成器，输入为版本化 Schema |
| Desktop 网络 | Netty NIO，仅位于 `transport-desktop` |
| Android 网络 | `java.net.Socket` + `Dispatchers.IO`，仅位于 `transport-android` |
| 配置与 Schema | JSON 等文本格式可使用 `kotlinx.serialization`；二进制 Minecraft 协议不使用它 |
| 日志 | 公共核心使用最小日志门面或结构化诊断事件，不绑定 Logback、`java.util.logging` |
| 测试 | Kotlin Test、Desktop JVM 测试、Android 测试、黄金样本和 Minestom 集成测试 |

Android 使用 Java 21 字节码不代表完整 JDK 21 API 在设备上可用。公共代码禁止依赖虚拟线程、结构化并发、`java.net.http.HttpClient`、反射扫描、Unsafe、LWJGL 或其他 JVM-only 能力。标准库 API 是否可用由 Android `compileSdk`、`minSdk` 与 desugaring 配置共同决定。

## 3. 协议策略

### 3.1 自研范围

`protocol-core` 负责：

- VarInt、VarLong、String、UUID、Identifier、Position、BitSet 等基础类型。
- NBT、Slot 和注册表相关协议类型；复杂类型优先保证语义正确，不强行套用生成器。
- Packet Reader/Writer、数据包 ID 映射、方向与连接状态感知。
- 数据包分帧、压缩和加密的协议状态切换。
- Handshake、Status、Login、Configuration、Play 所需的数据包子集。
- 严格的长度、边界、压缩阈值和畸形输入检查。

### 3.2 代码生成边界

Schema 是重复数据包结构的单一事实来源，包含协议版本、连接状态、方向、数据包 ID 和字段布局。生成器负责机械、可推导的 Kotlin 模型与编解码。

- 生成产物写入构建目录，不手工编辑。
- 相同 Schema 必须生成确定性结果。
- 无法清楚表达的复杂布局保留手写编解码，并记录例外原因。
- 初版只锁定一个协议号及对应 Minestom 版本，不在业务代码中散布版本条件。

### 3.3 现有项目的角色

- PacketEvents、MCProtocolLib：核对字段、行为及测试参照，不作为公共运行时依赖。
- Minosoft：参考 Core/Headless/UI 分离和版本数据管理，不复制其 GPL 源码，不照搬包内多版本条件。
- Minecraft Wiki 与真实 Minestom 联调：互相补充；字段定义以锁定版本的协议数据、可靠参考实现和实际测试交叉确认。

## 4. 分层与数据流

```text
调用方 / CLI / 未来 Android 宿主
                |
          client-core
    会话状态机、事件、世界模型
                |
         protocol-core
  数据包、分帧、压缩/加密协议状态
                |
      Transport SPI（原始字节流）
          /             \
transport-desktop   transport-android
     Netty           Socket + 协程
```

`client-core` 定义传输端口，具体传输由应用组合根注入。公共 API 不暴露 Netty `ByteBuf`、PacketEvents Wrapper、Android 类型或特定认证实现。

压缩与加密属于协议链路，但底层 DEFLATE、AES/CFB8 等平台原语通过 `protocol-core` 内部 `expect/actual` 提供；传输模块只负责可靠、有序的字节流 I/O，不各自重新实现协议状态机。

## 5. 会话与并发

- 每个 `MinecraftClient` 至多拥有一个活动 `Session`，每个 Session 独立持有连接状态、注册表、世界和事件流。
- 不使用全局可变单例保存玩家、会话、协议状态或世界数据。
- 网络读取最终进入单一会话 Actor，按到达顺序执行状态迁移和会话行为。
- 写出通过单一有序入口，避免不同协程交错写包。
- KeepAlive、传送确认等协议义务由核心主动完成，不能依赖调用者订阅事件。
- 队列必须有界；背压、断开和事件溢出策略必须显式，不允许静默丢失影响会话正确性的消息。
- 关闭 Session 时依次停止接收新工作、取消协程、关闭传输并释放缓冲区；重复关闭保持安全。
- 可恢复错误必须有明确恢复路径；遇到未知包、非法状态迁移或编码失败时，应抛出带上下文的错误，不猜测默认值。

## 6. Android 与 Desktop 约束

公共代码只使用 Kotlin 标准库、协程和确认支持双目标的依赖。平台差异放在对应 source set 或适配模块中。

- Desktop 可以使用 Java 25 特性和 Netty，但不得反向渗入公共 API。
- Android 目标使用 Java 21 字节码，并在引入 Android 模块的早期阶段执行真实构建与连接测试。
- Android 宿主负责生命周期、网络安全配置、权限和账户秘密存储；核心不持有 Activity、Context 或 UI 对象。
- 密码学使用平台经过验证的实现，不自研 RSA/AES/SHA 算法。
- 初版不引入原生 Epoll/Kqueue、JNI、反射依赖注入或运行时类路径扫描。

## 7. 测试与质量门控

- 基础类型使用已知字节序列做编码/解码黄金测试，并检查超长 VarInt、截断输入和长度上限。
- 每个已实现数据包至少通过一项有意义的往返或黄金样本验证，避免只测试直接委托。
- Minestom 测试覆盖真实状态迁移和协议义务，不用自有 Mock 替代全部联调。
- Android 在协议大规模扩展前完成一次真机或模拟器连接，防止平台问题后移。
- 并发测试使用确定性协调，不依赖 `sleep` 猜测时序。
- Desktop 检查缓冲区释放和连接资源回收；Android 检查取消与宿主退出后的资源回收。
- 每次阶段收尾运行受影响测试及完整 `./gradlew build`，新增 Android 构建后再运行相应 Android 检查。

## 8. 扩展条件

只有出现真实需求时才增加以下复杂度：

- 第二个协议版本：先引入版本目录和独立 Codec 组装，不把 `if (version...)` 扩散到会话逻辑。
- 在线认证：独立认证适配，OAuth 设备流程、令牌存储与加密会话分离。
- 新传输实现：必须已有第二个明确使用场景和相同 Transport 契约。
- 渲染或 GUI：只能依赖客户端公共模型，不得进入 `client-core` 或 `protocol-core`。
- 性能优化：以分配、吞吐、延迟或内存测量为依据，并保留可理解的安全实现作为行为参照。
