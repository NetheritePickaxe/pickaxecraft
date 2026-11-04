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
      协议适配：连接、状态、事件转换、区块解码、版本对账
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
  └─ 区块列（已解码、不可变）→ Session 的区块缓存：按坐标覆盖，不经过 Channel
  └─ 实体快照（不可变）→ Session 的实体缓存：按 id 覆盖，不经过 Channel
  └─ 玩家列表条目（不可变）→ Session 的玩家缓存：按 UUID 覆盖，事件按包合并
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
- 世界数据（区块、实体、玩家列表）是这条规则的唯一例外：它们高频或按人数增长、天然按坐标
  或 id 覆盖，走会话私有的 `ConcurrentHashMap`；缓存的是不可变对象，因此不需要跨线程同步，
  也不会挤掉控制事件。三者的区别只在增删是否需要通知：区块增删由批次计数覆盖；实体增删
  （`EntitySpawned` / `EntityRemoved`）走事件流，位置增量只写缓存；玩家列表由适配层把一个包
  合并成一条 `PlayerListChanged`，条目再多也不会退化成按条投递。
- 聊天与上面几类相反：一条消息是“发生过一次”的事实，不能被覆盖也不可重建，因此照常走
  Channel；会话 Actor 把它追加到有界聊天记录（默认 200 条）后再投递事件，回看与通知因此一致。
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

### 5.3 M4a 已落地的具体形态：区块内容

协议库只把区块的 `chunkData` 作为字节数组交出，section 结构与调色板由 `protocol-core` 的
`ChunkDecoder` 补齐；客户端拿到的 `ChunkColumn` 不可变且保持压缩形态（调色板 + long 数组）。

- **section 数量来自维度，不来自数据**：等于 `height / 16`，维度信息取自配置阶段的
  `minecraft:dimension_type`，下标与 Login 包中的维度 id 对齐。因此顺序固定为
  “注册表 → Login → 区块”；缺少维度时直接失败，不猜默认高度。
- **读取 API**：`Session.chunk(x, z)`、`blockStateAt(x, y, z)`、`biomeAt(x, y, z)`。
  未加载返回 `null`，已加载但 Y 越界抛错——“没有数据”和“数据不合法”是两件事。
- **方块 id 是协议的全局状态 id**。翻译成 `minecraft:stone` 需要版本数据表，那属于下游客户端的
  资产，不进入核心库。
- **未解码**：光照、高度图、方块实体（NBT）与群系增量更新。重生/维度切换见 5.5：
  换维度会换掉 section 数量，旧维度缓存必须整体作废。

实现记录与格式细节见 [M4a 实施记录](plans/m4a-chunk-model.md)。

### 5.4 M4b 已落地的具体形态：实体跟踪

协议库已经把实体包拆成完整对象，本层补的是它不做的事：**累积相对量**并给出中立模型。

- **增量叠加在适配层完成**：移动包的坐标是 `short / 4096` 的增量，只有叠加到上一状态才有意义。
  `McplConnection` 保留一份网络线程私有的实体表，发出的每个事件都带**叠加后的完整快照**，
  上层不需要重放增量。`ClientboundTeleportEntityPacket` 的坐标与朝向可按位标记为相对，
  与玩家传送用同一套叠加规则。
- **未知 id 的增量包被丢弃**：增量叠加不到已知状态上就没有语义，而“尚未收到过该实体”是合法状态。
  解析失败仍会在解码阶段抛错，不会被这里吞掉。
- **增删是事件，位置是状态**：`EntitySpawned` / `EntityRemoved` 走事件流（缓存先写、事件后发，
  消费者收到事件时一定查得到）；`EntityUpdated` 只写缓存——每秒上百条完整快照投递出去
  只填满缓冲，不带来新信息。
- **读取 API**：`Session.entity(id)`、`entities()`、`entityCount`。本端玩家不在实体表中，
  自己的位置由 `WorldSnapshot.position` 表达。
- **未处理**：装备、状态效果、属性、生命值。实体元数据只读自定义名称，UUID → 玩家名见 5.6。

实现记录见 [M4b 实施记录](plans/m4b-entity-tracking.md)。

### 5.5 M4c 已落地的具体形态：重生与维度切换

`ClientboundRespawnPacket` 携带与 Login 相同的 `PlayerSpawnInfo`，因此换维度是一次“重新开始”：

- **维度重新解析**：用新的维度下标查 `dimension_type`，换掉 `minY` 与 section 数量；
  查不到即失败，不猜默认高度。
- **缓存整体作废**：区块与实体都属于旧世界，全部清空；区块计数归零、`player_loaded` 复位，
  服务端重发首批区块后本端会再回一次 `player_loaded`。
- **清理发生在网络线程**：若放到 Actor 里，新维度的区块可能先写入、再被迟到的事件抹掉。
- **位置置空**：旧坐标属于另一个维度，保留它等于给调用方错误数据；新的同步包紧随其后。
- **事件**：`ClientEvent.Respawned(dimension, gameMode)`，世界快照同时换维度。

实现记录见 [M4c 实施记录](plans/m4c-respawn.md)。

### 5.6 M4d 已落地的具体形态：玩家身份

实体包只带 UUID，用户名在玩家列表里。两者由**查询时关联**连起来，而不是在快照里存副本：

- **玩家列表按 UUID 合并**：服务端把同一个人拆成多次更新（档案、延迟、游戏模式各一次），
  `McplConnection` 只更新本包 `actions` 声明的列，其余沿用上一状态。缺失即清空会让
  “先到延迟、后到档案”的常见顺序把用户名抹掉。
- **一个包一个事件**：`PlayerListChanged(updated, removed)`。按条目发事件会让大服的一次登录
  打满有界缓冲，而缓冲写满在本项目里等于终止会话。
- **读取 API**：`Session.players()`、`player(uuid)`、`playerCount`、`nameOf(entityId)`。
  `nameOf` 在查询时把实体 UUID 映射到列表条目，因此两个包的到达顺序不影响结果。
- **元数据只读下标 2**（自定义名称）：下标 0–7 是所有实体共有的字段，生物层的字段从这个基址
  往后排（例如生命值 9）。协议不携带层级信息，本层也没有实体类型表，读其余下标会得到
  另一个字段的值——那不是数据缺失，是错误数据。
- **玩家列表是会话级事实**：换维度不清空，与区块、实体不同。

实现记录见 [M4d 实施记录](plans/m4d-player-identity.md)。

### 5.7 M4e 已落地的具体形态：聊天

三个来源包（系统、玩家、伪装）收敛成 `ChatMessage`，用 `ChatKind` 区分——混成一个纯文本
会丢掉“谁说的”和“能不能撤回”，而这两件事都不可逆：

- **格式是引用不是文本**：聊天包只给 `Holder<ChatType>`（注册表下标或内联定义），模板键与
  参数顺序来自配置阶段的 `minecraft:chat_type`。本层给出 `ChatFormat(key, parameters)`，
  不内置翻译表——模板文本不在协议里。查不到格式时为 `null`，消息照常上报。
- **观察者也要确认**：每条玩家消息后回 `ServerboundChatAckPacket`（步长 1）。不回时服务端的
  待确认窗口只增不减，真实服务端会因此拒绝后续带签名的消息。
- **可撤回的只有带签名的玩家消息**：删除包按服务端分配的编号引用；没有签名的消息不给编号，
  系统与伪装消息没有编号。删除时既发事件也从聊天记录里摘掉那一条。
- **文本是纯文本**：颜色、粗体与点击事件不保留；可翻译组件（如死亡消息）得到键名，
  因为客户端没有语言文件。
- **存储**：`Session.chat()` / `chatCount`，按到达顺序保留最近 `ClientOptions.chatHistorySize` 条，
  关闭与溢出都会清空。

实现记录见 [M4e 实施记录](plans/m4e-chat.md)。

### 5.8 M4f 已落地的具体形态：聊天上行

上行与下行不是镜像关系，有四件事只在发送方向存在：

- **阶段前置检查**：只有 `PLAY` 阶段可发。协议库的 `send` 在未连接时会把包放进待发队列，
  等连接建立后再发——对聊天来说那等于“在错误的阶段发出去了”，必须在适配层拦住。
- **无签名是合法状态**：`signature = null` 的消息在离线模式服务端上有效；启用安全聊天的
  服务端要求会话密钥，属于 M5。本层不伪造签名——伪签名会以校验失败的形式断在服务端。
- **last-seen 窗口**：上行包自带偏移、20 位位集与校验，本层按“什么都没确认”填写。它与接收
  方向回送的 `chat_ack` 是两套独立机制：后者是“我收到了几条”，前者是“随这条消息带上我的
  确认快照”。
- **不做本地回显**：发送成功与否由服务端广播回来的那条消息决定。本地插入会让界面显示一条
  服务端并不认可（被过滤、改写或拒收）的消息。

校验集中在 `ChatInput`：256 字符上限（与服务端解码时的限制同源）、单行（协议允许换行，但下游
一律按单行渲染）、命令剥一层前导斜杠。命令走独立的 `chat_command` 包，不参与聊天签名。

实现记录见 [M4f 实施记录](plans/m4f-chat-upstream.md)。

### 5.9 M4g 已落地的具体形态：移动上行

移动不是“把坐标发过去”，而是**在一组只带部分字段的包里挑一个**：

- **四个包，缺的字段由服务端沿用上一状态**：`pos`、`rot`、`pos_rot`、`status_only`。选错不会
  报错，只会表现为“朝向对了但人没动”或“人被拽回原朝向”，因此在 `Movement.packet(current, target)`
  里做成纯函数并单测——它只依赖两个 `LocalPose`，不碰网络。
- **本地姿态是一个整体**：`LocalPose`（坐标、朝向、落地标记）不可变，用 `AtomicReference`
  整体替换。网络线程在服务端同步/传送时写，调用线程在上行时写；写成五个各自更新的字段会
  在两端交错时算出错的比较基准。最后写入者胜出，与真实客户端遇到延迟时的行为一致。
- **接受移动没有回执**：服务端认可时什么都不发。因此发送后立即把本地姿态推进到目标值并上报
  `PositionSent`；服务端不同意时用 `PositionUpdated` 覆盖。`Session.world.position` 因此表示
  “本端声明的坐标”，而不是“服务端确认过的坐标”。
- **状态位是声明**：`onGround` 由调用方决定（默认 `false`，无头客户端不判碰撞）；
  `horizontalCollision` 恒 `false`——报 true 等于请服务端替本端减速。
- **只挡非有限值**：NaN/无穷会让服务端的距离校验得出错误结论；坐标远近、速度是否合理都由
  服务端判断，本层不设任意上限，也不做本地物理与预测。

### 5.10 M4h 已落地的具体形态：背包与容器

背包是**两套编号加一个状态机**，这两件事都不该让调用方记住：

- **窗口槽位是唯一对外编号**：`container_set_content` / `container_set_slot` 用的就是它，窗口 0
  的 0 是合成结果、5–8 盔甲、9–35 主背包、36–44 快捷栏、45 副手。26.2 新增的
  `set_player_inventory` 用另一套（0–8 快捷栏、36–39 靴/腿/胸/头、40 副手），护甲段与窗口槽位
  **反序**——换算在 `PlayerSlotMapping` 里做，靠单测钉住。
- **可变累积、不可变快照**：服务端大量使用单槽更新，`MutableContainer` 在上一份内容上改一格，
  向上只交不可变 `Container`；整包内容整体替换，旧的长列表不能残留。
- **内容变化只报窗口 id**：`InventoryChanged(windowId)`，内容从缓存读。箱子 27 格、背包 46 格，
  一次打开连着几包，逐槽投递会把有界缓冲打满，那在本项目里等于终止会话。
- **窗口 0 是背包的唯一来源**：Minestom 打开容器的整包只带容器自己的槽位（原版会连背包一起发），
  把背包当成容器窗口的尾段在两种服务端上都不成立。
- **成功没有回执同样是两台机器共有的特征**：`set_carried_item` 只在被拒绝或改写时才收到
  `set_held_slot`，因此选中格在发送后本地推进，服务端纠正时覆盖——与移动包同一套处理。

实现记录见 [M4h 实施记录](plans/m4h-inventory.md)。

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
- `ClientSessionTest` 通过公共 API 真实连接 Minestom：Status 查询返回 776，离线登录进入 play 并收到注册表数据；另覆盖会话隔离、服务端断开、连接失败、关闭后线程回收与事件溢出；出生后读到的方块状态与服务端 `stateId` 对账。依赖组合或版本错配时，编译仍会通过，只有这些用例会失败。
- `ChunkDecoderTest` 用手工构造的字节覆盖调色板三种形态、跨 long 边界与畸形数据：真服务端只会生成它自己使用的形态，边界只能这样造。
- `ChatTypeRegistryTest` 锁定服务端 `chat_type` 的 NBT 形状（缺字段、少参数、未知占位符）与下标解析：
  字段名对不上时不会报错，只会让所有消息失去格式，联调里很难看出来。
- 聊天联调用例逐项对账三类消息的身份、格式键与编号，并验证删除只移除被引用的那条。
- 移动联调用例只认服务端 `PlayerMoveEvent` 里的目标坐标：本地快照能说明“我发了什么”，
  说明不了“服务端收到了什么”；转向用例同时断言位置未变，因为 `rot` 包不带坐标。
- 背包用例按“服务端放在哪个槽位、客户端应从哪个槽位读到”逐格对账：槽位换算错了不会报错，
  只会把物品放到别的格子；快捷栏切换同时看服务端字段与客户端事件。
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
