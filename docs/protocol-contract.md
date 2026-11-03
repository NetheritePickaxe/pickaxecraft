# 协议适配契约（MCProtocolLib 26.2 / 协议 776）

状态：M1 已生效
日期：2026-11-03
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

（M1 无自动用例覆盖这一条：触发它需要等服务端的 KeepAlive 周期。来源是
`ClientListener.packetReceived` 的分支；M2 玩家出生后会在长连接中自然覆盖。）

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

## 4. 明确不依赖的行为

- 上游 API 的稳定性：库方不承诺，升级必须重跑联调用例。
- 除注册表以外的具体包类型：M1 只识别 `ClientboundRegistryDataPacket`。
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
| 事件溢出终止而非丢弃 | `ClientSessionTest.事件缓冲写满时终止会话而不是静默丢弃` |

联调用例全部经由 `client-core` 公共 API，不再直接装配协议库；这些用例同时是
“协议实现没有穿透到上层”的证明。

## 6. 协议库升级检查清单

1. 同时升级 `mcprotocollib` 与 `minestom` 版本，保持协议号一致。
2. 重跑 `./gradlew clean build`，特别是 `testing:minestom` 的 `ClientSessionTest`。
3. 核对本文第 2 节每一条是否仍成立（线程模型、KeepAlive、状态切换点、断开语义）。
4. 若上游改了默认执行器或 `EventLoopGroup` 归属，确认适配层的覆盖仍然生效。
5. 更新本文与 `docs/architecture.md` 中受影响的描述。
