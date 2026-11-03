# 实施路线

状态：M1 已收尾（Android 仅完成构建链探针，运行时仍待 M3）；下一步为 M2 出生与世界同步
日期：2026-11-03

## 1. 执行原则

- 每个阶段必须形成可运行、可验证的结果，不以“类已创建”作为完成标准。
- Headless 是第一阶段；长期下游是独立仓库中的 libGDX 轻量客户端，本路线只交付其核心库能力。
- Minestom 在本仓库只承担联调测试。可选单人游戏由外部 Desktop/Java 25 客户端集成，不进入共享核心，也不要求移动端内嵌服务端。
- 平台优先级为 Desktop → Android → iOS；Web/HTML5 不纳入目标。移动端支持必须以真实运行时验证为准。
- 先验证风险最高的链路，再扩大功能面积：当前主要平台风险是 Android 的 Netty 与 iOS 的 RoboVM，而不是协议本身。
- 一个阶段只引入完成该阶段所需的新抽象和依赖。
- 协议版本随协议库锁定，升级时同时调整协议库与 Minestom，并重跑真实握手。
- 当前阶段未通过验收时，不通过提前建设后续模块绕过问题。

## 2. 当前进度基线（2026-11-03，M1 收尾）

- 模块为 `protocol-core`、`client-core`、`app`、`testing:minestom`。
- 调用方只依赖 `client-core` 即可完成状态查询与离线登录进入 play：`MinecraftClient` 提供
  `status` / `connect` / `close`，`Session` 提供有序事件流与幂等关闭。
- `protocol-core` 是协议库的唯一适配层，Netty 与 MCProtocolLib 不在 `client-core` 及以上模块的编译类路径
  （由 `verifyModuleBoundaries` 断言）。
- 联调用例全部经由公共 API：`ClientSessionTest` 覆盖状态查询、登录到 play、注册表接收、
  会话隔离、服务端断开、连接失败、关闭后线程回收、事件溢出。
- 协议库的行为契约（回调顺序、KeepAlive 归属、状态切换点、断开语义）记录在
  [协议适配契约](protocol-contract.md)。
- Android 只完成构建链探针（[Android 反糖探针](android-dex-probe.md)）：D8 可产出 dex、
  `minSdk` 下限 26、`org.cloudburstmc.math:api` 必须剔除。运行时仍未验证。
- 联调日志仍只有 `player.configuring`，`player.spawned` 尚未出现；出生、传送/区块确认与世界模型属于 M2。

## 3. M0：工程基线与协议后端（已完成）

### 交付内容

- 建立当阶段有真实内容的模块：`protocol-core`、`testing:minestom`、`app`。
- 锁定 Kotlin、Netty、Minestom、JUnit 版本，并锁定 MCProtocolLib 26.2（协议 776）。
- 接入 MCProtocolLib：Netty 依赖裁剪、Open Collaboration 仓库按 group 白名单、`protocol-core` 走 Java 21 字节码。
- 移除自研协议脚手架：schema、`tools:protocol-generator` 与 KMP/AGP 构建链。
- 建立 CI：JDK 25 + `./gradlew build`，失败上传测试报告。

### 验收标准

- `./gradlew build` 通过，配置缓存可复用。
- `protocol-core` 产物为 Java 21 字节码（major 65），桌面模块为 69，由构建任务自动断言。
- 业务模块的编译类路径不出现 Netty、MCProtocolLib、Minestom，由构建任务自动检查。
- `ClientInteropTest` 用真实握手证明：Status 返回 776，离线登录进入 play 并收到注册表数据。
- 新克隆工程可重复解析同一套依赖（含 Open Collaboration 仓库）。

## 4. M1：客户端核心骨架与连接链路（已完成，Android 运行时除外）

实施记录与偏差见 [M1 实施计划](plans/m1-implementation-plan.md)；协议库行为契约见
[协议适配契约](protocol-contract.md)；Android 构建链证据见 [Android 反糖探针](android-dex-probe.md)。

### 交付内容

- 新建 `client-core`：`MinecraftClient`、连接状态、事件流、会话 Actor、关闭语义。
- `protocol-core` 完成适配层职责：把协议库的连接与包回调转成项目自己的语义事件，向上不泄漏 `Packet`/`ByteBuf`/`Channel`。
- 先形成协议库行为契约：回调线程、状态切换、KeepAlive 归属、连接失败顺序与网络资源所有权均有测试或明确记录。
- CLI 能连接本地 Minestom，输出状态查询结果与会话事件，Ctrl+C 优雅退出。
- 错误体系：连接失败、服务端断开、协议异常分别给出带上下文的错误。
- 在协议适配层可查询 Status 后，提前执行一次 Android/ART 的 Status-only 探针；完整移动平台验证仍留在 M3。

### 验收标准

- 调用方只依赖 `client-core` 的公共 API 即可完成 Status 查询与离线登录到 GAME，不接触协议库类型。
- 同一进程两个客户端会话的状态、用户名和事件流不串用，关闭其中一个不影响另一个；注册表和世界隔离在 M2 有实际模型后验证。
- 关闭后线程、`EventLoopGroup` 与端口资源全部释放；重复关闭无副作用。
- `testing:minestom` 的联调用例改为通过自有 API 驱动，不再直接引用协议库。
- Android Status 探针形成可复现的成功或失败证据；失败时先评审处置路径，再扩大 M2 世界状态投入，不把 Desktop 结果当作 Android 支持证据。

  实际结论：D8 反糖通过（`minSdk` 26），但运行时未能验证——执行环境无硬件虚拟化、无可用设备。
  该缺口明确留给 M3 双路径 spike，不计入 M1 完成。

M1 只要求进入 GAME，不要求玩家出生、传送/区块确认或世界模型；这些仍是 M2 的独立门控。

## 5. M2：Minestom 离线登录 MVP

### 交付内容

- 打通到出生：Login、Configuration、Play 的完整链路，包括区块批次确认与传送确认。
- 会话级注册表、维度与玩家状态。
- 处理 Login、KeepAlive、Synchronize Player Position、Set Center Chunk 等进入世界所需的包。
- 结构化诊断：把“卡在哪个阶段”变成可观察的日志与错误。

### 验收标准

- 客户端能在 harness 上完成出生（`event=player.spawned`），而不只停在配置阶段。
- 核心自动完成传送确认与区块批次确认，调用方无需手工补救。
- 服务端主动断开、网络取消、客户端关闭三种路径均能释放资源。
- 该链路在 CI 中可重复执行，不依赖人工观察。

## 6. M3：移动平台可行性门控（spike）

### 交付内容

- Android：在模拟器或真机上跑通 `protocol-core` 的 Status 查询与离线登录。
- 验证 Netty 在 Android 上的 NIO 行为、no-Unsafe 降级、线程与资源回收。
- 验证 `java.lang.Record`、`java.util.HexFormat` 等 Java 17+ API 的 D8 反糖结果，确定 `minSdk` 下限。
- iOS：在 macOS/Xcode + RoboVM 环境验证 Java 21 字节码、Kotlin 标准库、协程与 Netty，至少完成 Status 查询。
- 记录真实失败点；若某平台不可行，给出针对该平台的降级方案，不影响已验证平台继续交付。

### 验收标准

- Android 真机或模拟器可对同一 Minestom 服务完成 M2 的链路，或给出明确的不可行结论与证据。
- iOS 形成 RoboVM 实测结论；未验证前只列为候选平台，不写成已支持。
- 结论写入架构文档后才更新支持矩阵；不得以 Desktop 成功推断移动端成功。

## 7. M4：可使用的客户端库

### 交付内容

- 稳定初版 `MinecraftClient`、连接状态、事件和错误 API。
- 支持基础位置/移动、聊天、插件消息和诊断日志。
- 实现最小只读区块、实体增删与位置同步、基础背包状态。
- 提供有界队列、写背压、超时和清晰的关闭语义。
- 编写接入示例，明确 API 尚处实验阶段的兼容政策。
- 在独立仓库建立最小 libGDX Desktop 消费工程，验证版本化产物、事件到主循环的桥接及基础世界状态展示；渲染实现不回流到本仓库。
- 下游以 GL20/GLES 2.0 为共同渲染基线，接入 `gdx-lwjgl3-angle` 并实测 ANGLE Vulkan 路径；保留可工作的回退后端。macOS/Android/iOS 分别按 ANGLE Metal、原生 GLES、MetalANGLE 路线验证，不能由 Desktop 结果直接推断。

### 验收标准

- 调用者无需接触数据包、Netty 或协议库即可完成基础机器人。
- 世界状态只来自所属 Session，断线后不会泄漏到下一次连接。
- 慢事件消费者不会破坏 KeepAlive 等协议义务，也不会导致无界内存增长。
- Minestom 集成测试覆盖已公开的稳定行为。
- libGDX Desktop 消费工程无需依赖协议库内部类型即可连接 Minestom 并展示基础状态。
- 下游用运行时信息与渲染结果证明 ANGLE 的实际后端；Vulkan 路径、回退路径和上下文重建均通过测试前，不宣称 Vulkan 已支持。

## 8. M5：生产化能力

### 交付内容

- 在线模式加密、Microsoft 认证和敏感令牌的安全存储适配。
- 明确的重连策略；自动重连必须可配置，不能默认无限重试。
- 畸形包、压缩边界的模糊测试。
- 多客户端压力测试、内存/分配测量和必要的局部优化。
- 完善诊断信息、发布产物和版本迁移说明。
- 发布时生成准确的平台支持矩阵，分别记录 Desktop、Android、iOS 的验证版本与限制；Web 保持不支持。

### 验收标准

- 在线模式不会将令牌或密钥写入普通日志。
- 加密、压缩和状态切换顺序经过真实服务端验证。
- 背压与重连不会造成无界队列、重复 Session 或资源泄漏。
- 性能优化有测量依据，且行为测试保持通过。
- 独立 libGDX 客户端能够锁定并消费发布产物，平台支持声明均有对应测试证据。

## 9. 后续扩展与明确边界

- 第二协议版本与版本适配目录。
- 完整世界、物理、寻路和高级机器人能力。
- 更多平台或传输实现。
- 稳定的二进制兼容承诺与插件生态。
- GUI、渲染、资产管理和输入系统属于独立 libGDX 客户端仓库，不是本库的后续模块。
- 可选单人游戏在外部 Desktop/Java 25 客户端中集成 Minestom：验证启动后连接、退出时先断 Session 再停服务端、端口冲突与异常关闭；不得为此让核心库依赖 Minestom 或改为 Java 25 字节码。Android/iOS 不默认包含该能力。
- Web/HTML5 当前明确不做，不作为隐含的平台承诺。

扩展前必须重新评估公共 API 和模块边界，但不以未来可能性为由提前增加当前复杂度。
