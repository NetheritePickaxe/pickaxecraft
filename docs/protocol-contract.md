# 协议适配契约（MCProtocolLib 26.2 / 协议 776）

状态：M2 已生效
日期：2026-11-04
适用模块：`protocol-core`

## 1. 这份文档解决什么

`protocol-core` 是唯一接触 MCProtocolLib 的模块。它与协议库之间有一批**隐含假设**：
回调在哪个线程、谁负责应答 KeepAlive、状态在哪一刻切换、关闭由谁发起。
这些假设一旦随上游版本变化，编译仍然通过、单测仍然通过，只有真实握手会失败。

本文把假设写成契约：**哪些行为由库保证、哪些由本项目实现、靠什么证据守住**。
协议库升级时必须重跑第 5 节的用例，并回头修订本文。

## 2. 由协议库保证、本项目依赖的行为

### 2.1 一个协议实例只服务一个会话

`MinecraftProtocol.newClientSession()` 会调用 `resetStates()`，把入站/出站状态写回
`HANDSHAKE`；同一个实例被两个连接共用时会互相覆盖状态。

**本项目的做法**：`ProtocolConnection` 每次创建新的 `MinecraftProtocol`，不复用实例。

### 2.2 线程归属

`NetworkSession.channelRead0()` 对 `shouldRunOnGameThread()` 为 true 的包走
`packetHandlerExecutor`，其余直接在 Netty 事件循环上处理。库的默认执行器是**静态共享**的
`DefaultEventLoopGroup`；默认 `EventLoopGroup` 同样是静态字段，并注册了 JVM 关闭钩子。

**本项目的做法**：

- 传入直接执行器（`Runnable::run`），于是所有包回调都在该连接自己的网络线程上，
  不会出现“两个线程交错处理同一个会话”的情况。
- 覆盖 `getEventLoopGroup()`，每个连接自持一个单线程组，关闭时回收，不使用进程级共享组。

**对上层的影响**：`ProtocolListener.onEvent` 发生在网络线程上，只能做入队这类短操作。
`client-core` 正是这么用的（有界 Channel），业务逻辑全部在会话 Actor 里执行。

### 2.3 回调顺序与状态可见性

`ClientListener` 在 `newClientSession()` 时加入会话，早于任何调用方注册的监听器。
因此**调用方的 `packetReceived` 看到的是处理前的状态**：收到
`ClientboundFinishConfigurationPacket` 时，`inboundState` 仍是 `CONFIGURATION`。

**本项目的做法**：观察点放在 `McplConnection.callPacketReceived()` / `callEvent()` 里
`super` 调用**之后**，读到的是库处理完的结果：

| 库的事件 | 处理后的状态 |
| --- | --- |
| `ConnectedEvent`（握手包已发出） | `LOGIN` 或 `STATUS` |
| `ClientboundLoginFinishedPacket`（已回 ack） | `CONFIGURATION` |
| `ClientboundFinishConfigurationPacket`（已确认） | `GAME` |

### 2.4 KeepAlive 归属

`ClientListener` 在 `GAME` 与 `CONFIGURATION` 阶段对 `ClientboundKeepAlivePacket`
自动回 `ServerboundKeepAlivePacket`，开关是 `AUTOMATIC_KEEP_ALIVE_MANAGEMENT`（默认 true）。

**本项目的做法**：**不应答、不转发、不暴露**。适配层与 `client-core` 都不发送 KeepAlive。
若将来需要自定义应答，必须先关掉这个标志，且只能有一处实现，避免重复应答。

（没有专门的用例：触发它要等服务端的 KeepAlive 周期。来源是
`ClientListener.packetReceived` 的分支；出生后的长连接会自然经过它，读到异常时会以
`NETWORK` / `PROTOCOL` 失败结束，不会静默。）

### 2.5 状态查询是一次性连接

STATUS 意图下，库的执行顺序是：

```text
Intention → StatusRequest → StatusResponse → PingRequest → PongResponse → disconnect("multiplayer.status.finished")
```

`StatusResponse` 触发 `SERVER_INFO_HANDLER_KEY` 回调，`PongResponse` 触发
`SERVER_PING_TIME_HANDLER_KEY` 回调，**随后库自行断开**。

**本项目的做法**：在 Pong 回调里产出 `ProtocolEvent.StatusReceived`（带实测往返耗时），
并把随后的断开视为正常收尾（`ProtocolCloseReason.Local`）。

### 2.6 断开语义

- `disconnect()` 是幂等的（内部 `disconnected` 标志），会 `flush().close().awaitUninterruptibly()`
  再回调 `DisconnectedEvent`。
- `channelInactive`（对端关连接）走同一路径，原因是翻译键 `disconnect.endOfStream`。
- `exceptionCaught` 带上 `cause` 再断开，因此 **cause 非空 = 出错**。
- 断开原因是 Adventure 组件，不是字符串。

**本项目的做法**：`ProtocolEvent.Closed` 恰好报告一次；原因归为 `Local` / `Server` / `Failure`，
其中 `Failure` 再按“连接是否建立过 + 异常类型”分为 `CONNECT` / `NETWORK` / `PROTOCOL`。

### 2.7 转移包会绕过会话生命周期

`FOLLOW_TRANSFERS` 默认为 true 时，`ClientboundTransferPacket` 会让库**在回调里新建一个
`ClientNetworkSession` 并 `connect(true)`**，那个会话不归本项目管理，也无法被上层关闭。

**本项目的做法**：显式设置 `FOLLOW_TRANSFERS = false`。转移表现为一次普通断开，
是否支持由后续版本决定，而不是留一个失控的会话。

### 2.8 地址解析

`NettyHelper.resolveAddress` 默认会做 SRV 查询。M1 只接受显式 `host:port`，
因此设置 `ATTEMPT_SRV_RESOLVE = false`，避免一次隐式 DNS 查询带来的额外延迟与不可预测性。

### 2.9 play 阶段必须由本端回应的包

库不会替应用回这些包：它们与业务无关，只有在真正进入世界时才出现，因此上游把它们留给了调用方。

| 包 | 本端应答 | 不回应的后果 |
| --- | --- | --- |
| `ClientboundPlayerPositionPacket` | `ServerboundAcceptTeleportationPacket(id)` | 服务端认为玩家未就位，移动与传送都不生效 |
| `ClientboundChunkBatchFinishedPacket` | `ServerboundChunkBatchReceivedPacket` | 服务端在若干批之后停止发送区块，视野只有出生点一小片 |
| `ClientboundLoginPacket` 之后的首批区块 | `ServerboundPlayerLoadedPacket` | 服务端的 `PlayerLoadedEvent` 不触发，依赖“玩家已就绪”的逻辑不会开始 |

另外还有一条**配置阶段的上行包**：`ServerboundClientInformationPacket`。它不属于任何
`Clientbound` 包的应答，但必须在进入 `CONFIGURATION` 时主动发送——服务端用它确定视野距离，
Minestom 在玩家设置为空时会在出生流程里读取失败。

**本项目的做法**：全部在 `McplConnection` 内就地完成，`client-core` 与调用方不参与。
观察点仍是 `super.callPacketReceived` 之后，因此应答总是在库完成状态处理之后发出。

### 2.10 区块内容不由协议库解析

`ClientboundLevelChunkWithLightPacket` 只提供 `chunkData: byte[]`，section 与调色板都在字节里。
库里有 `MinecraftTypes.readChunkSection`，但客户端解包路径不调用它——不能指望库给出方块内容。

解码时必须遵守两条来自上游实现的事实，两者都容易记错：

1. **调色板的长整型数组没有长度前缀**。长度由“元素数 / 每个 long 能放几个”算出。
   证据：`MinecraftTypes.readFixedSizeLongArray` 直接读定长 long；Minestom 的
   `Palette` 序列化器写 `RAW_LONGS`，也不写长度。按旧格式（varint 长度 + long 数组）实现时，
   联调会在第一列区块上出现“尾部多出 2 字节”。
2. **section 数量不在包里**，等于当前维度 `height / 16`；维度高度来自
   `minecraft:dimension_type` 注册表（`min_y`、`height`），`ClientboundLoginPacket` 只给下标。
   因此解码顺序是“注册表 → Login → 区块”，顺序被打乱时应当失败而不是猜默认高度。

另外：服务端重发同一坐标的区块表示整列替换；`ClientboundForgetLevelChunkPacket` 表示丢弃。
两者都必须落到世界状态里，否则缓存只增不减。

### 2.11 聊天：格式在注册表，确认在本端

聊天包只提供**格式引用**（`Holder<ChatType>`：注册表下标，或包内内联定义），模板键与参数顺序
来自配置阶段的 `minecraft:chat_type` 注册表，条目顺序即下标。包里既没有拼好的整行，也没有
模板文本——翻译在客户端语言文件里，因此本层只给出“键 + 参数顺序”，不内置翻译表。

两条必须自己承担的义务：

1. **确认**：每条玩家消息后回 `ServerboundChatAckPacket`（步长 1）。不回时服务端的待确认窗口
   只增不减，真实服务端会因此拒绝后续带签名的消息。Minestom 没有注册该包的处理逻辑，
   所以它的日志会提示 `ClientChatAckPacket:PLAY does not have any default listener!`——
   这条警告正好是“确认确实发出去了”的证据。
2. **删除**：`ClientboundDeleteChatPacket` 按服务端为消息分配的编号引用（`player_chat` 的
   `index`），且只对带签名的玩家消息生效。系统消息与伪装消息没有编号，删不到。

`ClientboundSystemChatPacket.overlay` 只覆盖旧路径；26.2 另有独立的 `ActionBarPacket`，
本层尚未处理。

### 2.12 聊天上行：无签名是合法状态，安全聊天需要密钥

`ServerboundChatPacket` 的字段是 `message`、`timeStamp`、`salt`、`signature`（可空 256 字节）、
`ackOffset`、`ackList`（固定 20 位）与 `checksum`。

三条由本层承担的判断：

1. **不签名**：`signature = null`。离线模式的服务端接受这种消息；启用安全聊天的服务端要求
   每条消息由会话密钥签名，密钥来自在线认证，属于 M5。不做“半个签名”——伪签名会以校验失败
   的形式断在服务端，比明说不支持更难定位。
2. **last-seen 填空**：偏移 0、位集全空、校验 0。这不是占位符，而是准确事实：本端不为收到的
   消息生成签名确认。它与接收方向回送的 `ServerboundChatAckPacket` 是两套独立机制，
   不能互相替代。
3. **阶段前置检查**：只有 `PLAY` 阶段可发。协议库的 `send` 在未连接时会把包放进待发队列，
   等连接建立后再发——对聊天而言那正好是“在错误的阶段发出去了”，因此必须在适配层拦住。

`ServerboundChatCommandPacket` 只有一个字符串字段，**不带**前导斜杠，也不参与聊天签名，
因此在启用安全聊天的服务端上仍可用（能否执行取决于权限）。长度上限与聊天相同，都是 256 个
字符——这个数字来自服务端解码时的校验，超长会被直接断开。


## 3. 由本项目实现、不得记在上游账上的部分

| 职责 | 位置 | 说明 |
| --- | --- | --- |
| 语义事件 | `McplConnection` | 把包与库事件转成 `ProtocolEvent`，不向上泄漏 `Packet`/`Channel`/`ByteBuf` |
| 资源归属 | `McplConnection` | 每连接一个 `EventLoopGroup`；关闭时 `shutdownGracefully` 并等待终止 |
| 终止通知 | `McplConnection` | 无论谁先断开，`Closed` 只发一次 |
| 文本渲染 | `ComponentText` | Adventure 组件转纯文本；库自带的翻译键给出可读文本 |
| 失败归层 | `McplConnection.classify` | CONNECT / NETWORK / PROTOCOL，上层不用识别异常类 |
| 会话 Actor、背压、溢出 | `client-core` | 有界缓冲 + 单消费者事件流；溢出终止会话而不是静默丢弃 |
| 阶段词汇 | `client-core` | `SessionState` 与 `ProtocolPhase` 独立映射，便于将来更换后端 |
| 出生应答 | `McplConnection` | 客户端设置、传送确认、区块批次确认、`player_loaded`；调用方看不到这些包 |
| 区块解码 | `ChunkDecoder` | 调色板三种形态、位宽与截断校验；向上只给不可变的 `ChunkColumn` |
| 维度高度 | `DimensionRegistry` | 从 `dimension_type` 注册表读 `min_y` / `height`；顺序即 Login 的维度 id |
| 世界快照 | `client-core` | `Session.world` 汇总维度、实体 id、位置与区块数；不向上暴露包对象 |
| 区块缓存 | `client-core` | 按坐标保存不可变区块列，关闭时清空；查询 API 区分“未加载”与“越界” |
| 实体累积 | `McplConnection` | 移动包是 `short / 4096` 的相对量，叠加后以完整快照上报；未知 id 的增量丢弃 |
| 实体缓存 | `client-core` | 按 id 保存不可变快照；位置增量只写缓存，增删走事件流 |
| 重生换维度 | `McplConnection` | 重新解析维度、清空实体与区块计数、复位 `player_loaded`；玩家列表保留；上层只看到 `Respawned` |
| 玩家列表合并 | `McplConnection` | 只更新本包 `actions` 声明的列，其余沿用上一状态；合并后无变化不上报 |
| 元数据白名单 | `McplConnection` | 只读下标 2（自定义名称）；其余下标依赖实体层级，没有类型表就不读 |
| 聊天格式 | `ChatTypeRegistry` | 解析 `chat_type` 注册表与 `Holder` 引用；未知占位符跳过，查不到格式返回 `null` |
| 聊天确认 | `McplConnection` | 每条玩家消息后回 `ServerboundChatAckPacket`；调用方看不到这个包 |
| 聊天记录 | `client-core` | 有界保存最近的消息（默认 200 条）；删除按编号移除那一条 |
| 聊天上行 | `ChatInput` + `McplConnection` | 校验 256 字符上限与单行、剥一层前导斜杠；无签名、last-seen 留空；只有 play 阶段可发 |
| 命令上行 | `McplConnection` | `chat_command` 不带斜杠、不参与聊天签名；执行结果以普通消息回到事件流 |

## 4. 明确不依赖的行为

- 上游 API 的稳定性：库方不承诺，升级必须重跑联调用例。
- 具体包类型清单：目前只识别注册表、Login、位置同步、区块（含批次与卸载）、实体（生成、
  移除、相对/绝对移动、头部朝向）、实体元数据（仅自定义名称）、玩家列表（更新与移除）、
  聊天（系统、玩家、伪装、删除）与重生这几类；其余包一律不解释，也不假设其字段。
- 聊天的样式与翻译：组件只渲染成纯文本，颜色/粗体/点击事件不保留；可翻译组件得到键名。
- 聊天签名与安全聊天的 last-seen 语义：本端不发带签名的消息，因此不实现签名算法，也不依赖
  服务端如何解释空位集；启用安全聊天的服务端会拒绝上行，这是明确的“不支持”而不是静默丢弃。
- 元数据下标的层级归属：下标含义取决于实体是否属于生物层，而协议不携带该信息。
  本层只读所有实体都成立的下标 2，不按“生物的下标 9 是生命值”之类的推断取值。
- 库内部的线程数量、线程名、事件循环实现：只依赖“同一连接的回调在单一网络线程上”。
- `Session.getChannel()` 之外的底层干预能力：M1 不需要；若将来需要，先评审 fork 策略。

## 5. 证据与回归入口

| 契约 | 证据 |
| --- | --- |
| 状态查询返回 776 与实测延迟 | `ClientSessionTest.状态查询返回锁定的版本与实测延迟` |
| 登录走完 LOGIN → CONFIGURATION → PLAY 并收到注册表 | `ClientSessionTest.离线登录进入 play 并收到注册表` |
| 服务端断开被归类为服务端原因 | `ClientSessionTest.服务端主动断开被报告为服务端原因` |
| 连接失败被归类且不留残留会话 | `ClientSessionTest.连接失败给出可区分的错误并允许再次连接` |
| 关闭后网络线程回收、重复关闭安全 | `ClientSessionTest.重复关闭无副作用且网络线程被回收` |
| 会话隔离 | `ClientSessionTest.两个客户端互不影响` |
| 实体生成/移动/移除与坐标对账 | `ClientSessionTest.实体的生成、移动与移除对客户端可见` |
| 重生换维度后按新维度解码 | `ClientSessionTest.重生切换维度后世界状态与缓存一起更新` |
| 玩家列表合并、UUID → 名字关联、自定义名称 | `ClientSessionTest.玩家列表与自定义名称可从会话查到` |
| 三类聊天消息的身份、格式键与删除 | `ClientSessionTest.聊天消息可读且能按编号删除` |
| 聊天与命令上行：服务端收到原文、回显进入记录、非法输入与关闭后发送被拒 | `ClientSessionTest.发送的聊天与命令服务端可见` |
| 上行输入的长度、单行与斜杠规则 | `ChatInputTest`（8 例） |
| `chat_type` 注册表的 NBT 形状与下标解析 | `ChatTypeRegistryTest`（9 例） |
| 事件溢出终止而非丢弃 | `ClientSessionTest.事件缓冲写满时终止会话而不是静默丢弃` |
| 出生：自动应答传送到位并收到区块 | `ClientSessionTest.登录后完成出生并进入世界`（客户端事件） |
| 出生：服务端确认 `PlayerSpawnEvent` 与 `PlayerLoadedEvent` | 同上（harness 的 `awaitMilestone`） |
| 区块调色板三种形态与截断数据 | `ChunkDecoderTest`（手工构造字节，7 例） |
| 区块内容与服务端一致 | `ClientSessionTest.出生后读到的方块状态与服务端一致`（双侧 `stateId` 对账） |

联调用例全部经由 `client-core` 公共 API，不再直接装配协议库；这些用例同时是
“协议实现没有穿透到上层”的证明。

## 6. 协议库升级检查清单

1. 同时升级 `mcprotocollib` 与 `minestom` 版本，保持协议号一致。
2. 重跑 `./gradlew clean build`，特别是 `testing:minestom` 的 `ClientSessionTest`。
3. 核对本文第 2 节每一条是否仍成立（线程模型、KeepAlive、状态切换点、断开语义）。
4. 若上游改了默认执行器或 `EventLoopGroup` 归属，确认适配层的覆盖仍然生效。
5. 若上游开始自带区块解码，或调色板线格式变化（例如重新带回 long 数组长度前缀），
   先跑 `ChunkDecoderTest` 与 `ClientSessionTest.出生后读到的方块状态与服务端一致`。
6. 更新本文与 `docs/architecture.md` 中受影响的描述。
