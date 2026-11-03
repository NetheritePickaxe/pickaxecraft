# 架构与技术决策

状态：已更新（协议后端改为 MCProtocolLib，见 [决策 0001](decisions/0001-protocol-backend.md)）
日期：2026-11-03

## 1. 项目定位

PickaxeCraft 初版提供可嵌入其他程序的 Minecraft Java 版 Headless 客户端库，使调用者能够连接 Minestom、维持会话、接收结构化事件并执行基础操作。

### 初版目标

- 以库形式交付，而不是只提供一个命令行机器人。
- 支持同一进程内多个相互隔离的客户端会话。
- 跑通 Handshake、Status、Login、Configuration、Play 的完整连接链路。
- 支持 Minestom 离线模式登录、KeepAlive、传送确认、区块批次确认和基础移动。
- 桌面与将来的 Android 宿主复用同一份 JVM 实现；Android 需先通过 Netty 可行性验证。
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
| 语言 | Kotlin/JVM。协议后端是 Java 库，跨平台共用不靠 KMP，而靠「同一份字节码在两端运行」 |
| 构建 | Gradle Kotlin DSL、Wrapper、Version Catalog、`buildSrc` 约定插件 |
| 构建运行时 | JDK 25，Gradle 9.6.0 |
| Desktop 目标 | JVM 25 |
| 共用产物目标 | Java 21 字节码（`protocol-core`），为 Android 保留可消费性 |
| 协程 | `kotlinx-coroutines`，会话内顺序处理 |
| 二进制协议 | MCProtocolLib 26.2，不自行实现 Reader/Writer、数据包与状态机 |
| 协议数据 | 数据包定义、注册表 NBT、标签 NBT 由协议库随包提供，本仓库只维护版本常量并与之对账 |
| 网络 | MCProtocolLib 内置 Netty（4.2.x），桌面与 Android 共用同一栈 |
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
- 升级协议库或 Minestom 必须同时改两侧，并重跑 `ClientInteropTest`：编译通过不代表握手可用。
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
调用方 / CLI / 未来 Android 宿主
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

`client-core` 拥有会话生命周期与协议义务的归属（例如是否需要自己回应 KeepAlive）；`protocol-core` 只做协议事实的转换，不做业务判断。公共 API 不暴露 Netty、MCProtocolLib 类型或 Android 类型。

## 5. 会话与并发

- 每个 `MinecraftClient` 至多拥有一个活动 `Session`，每个 Session 独立持有连接状态、注册表、世界和事件流。
- 不使用全局可变单例保存玩家、会话、协议状态或世界数据。
- 网络读取最终进入单一会话 Actor，按到达顺序执行状态迁移和会话行为。
- 写出通过单一有序入口，避免不同协程交错写包。
- KeepAlive、传送确认等协议义务由核心主动完成，不能依赖调用者订阅事件；协议库已自动应答 KeepAlive 的部分需要在适配层显式声明归属，避免重复应答。
- 队列必须有界；背压、断开和事件溢出策略必须显式，不允许静默丢失影响会话正确性的消息。
- 关闭 Session 时依次停止接收新工作、取消协程、关闭传输并释放缓冲区；重复关闭保持安全。协议库的 `EventLoopGroup` 必须随会话关闭。
- 可恢复错误必须有明确恢复路径；遇到未知包、非法状态迁移或编码失败时，应抛出带上下文的错误，不猜测默认值。

## 6. 桌面与 Android 约束

- `protocol-core` 以 Java 21 字节码产出，由 `verifyBytecode`（major 65）自动断言；桌面模块可以用到 Java 25。
- Netty 是协议库的一部分，无法从 Android 路径剥离。`netty-common` 显式识别 Android 并退到 no-Unsafe 路径，但**尚未在设备上验证**。
- 已知待验证项：Netty 在 Android 上的 NIO 可用性、`java.lang.Record` 与 `java.util.HexFormat` 的 D8 反糖、`minSdk` 取值。
- 在 spike 通过前，Android 不写入支持矩阵；不为其提前引入 AGP、KMP 或独立传输实现。
- Android 宿主负责生命周期、网络安全配置、权限和账户秘密存储；核心不持有 Activity、Context 或 UI 对象。
- 密码学使用协议库与平台经过验证的实现，不自研 RSA/AES/SHA 算法。

## 7. 测试与质量门控

- 版本常量与协议库 codec 常量对账，注册表 NBT 可加载。
- `ClientInteropTest` 用协议库真实连接 Minestom：Status 查询返回 776，离线登录进入 play 并收到注册表数据。依赖组合或版本错配时，编译仍会通过，只有这个用例会失败。
- Minestom 测试覆盖真实状态迁移，不用自有 Mock 替代全部联调。
- 构建门控：字节码主版本、模块依赖边界（Netty / MCProtocolLib / Minestom 不得出现在业务模块）。
- 并发测试使用确定性协调，不依赖 `sleep`；JVM 内单例（Minestom）通过按类分叉规避。
- 每次阶段收尾运行 `./gradlew build`，新增模块后同步更新门控。

## 8. 扩展条件

只有出现真实需求时才增加以下复杂度：

- 第二个协议版本：先引入版本目录与独立适配，不把 `if (version...)` 扩散到会话逻辑。
- 在线认证：独立认证适配，OAuth 设备流程、令牌存储与加密会话分离。
- Android 支持：以 spike 结果为前提，通过后再决定是否需要独立的传输层或降级方案。
- 渲染或 GUI：只能依赖客户端公共模型，不得进入 `client-core` 或 `protocol-core`。
- 性能优化：以分配、吞吐、延迟或内存测量为依据，并保留可理解的安全实现作为行为参照。
