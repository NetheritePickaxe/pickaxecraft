# M2 实施记录：Minestom 离线登录 MVP

状态：已实施（2026-11-04）
范围：[roadmap.md](../roadmap.md) 第 5 节
结论：出生链路打通，四条验收标准全部满足；区块内容解码与世界模型留给 M4

## 1. 起点与真正的阻塞点

M0 与 M1 的记录里都有一条未解释现象：客户端能进入 play、能收到注册表，但服务端日志只有
`event=player.configuring`，没有 `event=player.spawned`，客户端也收不到 `ClientboundLoginPacket`。

一开始的假设是协议实现有问题（配置阶段没走完、缺注册表、版本不匹配）。实际排查顺序：

1. 在适配层的收包回调上打印包名，确认客户端**最后一个收到的包是 `ClientboundFinishConfigurationPacket`**，
   之后服务端再没发过任何东西，客户端在 read timeout 后被判为网络失败。
2. 读 Minestom 字节码：`ConnectionManager.transitionConfigToPlay()` 只是把玩家放进
   `playWaitingPlayers` 队列；真正的出生发生在 `ConnectionManager.tick()` →
   `updateWaitingPlayers()` → `Player.UNSAFE_init()`。
3. 再读启动入口：`ServerProcessImpl.start()` 只做 socket 监听与离线模式校验；
   **`ThreadDispatcher.start()` 与 `TickSchedulerThread` 是由 `MinecraftServer.start()` 启动的**。
   harness 用的是 `MinecraftServer.process().start()`，因此整个进程从未 tick 过。

结论：这是 harness 的生命周期 bug，与协议库、Minestom 版本、客户端实现都无关。

## 2. 改动

### 2.1 harness

- 改用 `MinecraftServer.init(Auth.Offline())` 返回的实例调用 `start(address)`，
  tick 线程与线程调度器随之启动。
- 新增 `PlayerMilestone`（`SPAWNED` / `LOADED`）与 `awaitMilestone(username, milestone, timeout)`：
  把服务端侧事实变成用例可以断言的里程碑，而不是只靠人看日志。

### 2.2 protocol-core（适配层）

进入 play 后，协议要求本端回应的东西全部在这里完成，调用方不需要知道它们存在：

| 包 | 处理 | 理由 |
| --- | --- | --- |
| （配置阶段）`ServerboundClientInformationPacket` | 进入 `CONFIGURATION` 时发送 | 服务端用它确定视野距离与语言；缺这一包时 Minestom 读取玩家设置会失败 |
| `ClientboundLoginPacket` | 转成 `ProtocolEvent.JoinedWorld` | 实体 id、维度、游戏模式、视野距离是会话级事实 |
| `ClientboundPlayerPositionPacket` | 回 `ServerboundAcceptTeleportationPacket`，转成 `PositionUpdated` | 不回确认时服务端认为玩家未就位 |
| `ClientboundChunkBatchFinishedPacket` | 回 `ServerboundChunkBatchReceivedPacket(8)`，累计区块数，首批后补发 `player_loaded` | 不回确认时服务端在若干批之后停止发送区块 |
| `ClientboundLevelChunkWithLightPacket` | 只累计数量 | 方块解码属于 M4，当前不建世界模型 |

相对坐标（`PositionElement.X/Y/Z/Y_ROT/X_ROT`）按上一次已知坐标叠加，避免多次传送后位置漂移。

### 2.3 client-core

- 新增 `WorldSnapshot` 与 `Position`，通过 `Session.world: StateFlow<WorldSnapshot?>` 暴露
  会话级维度、实体 id、位置与累计区块数。
- 新增 `Session.registries: StateFlow<Map<String, Int>>`，把配置阶段的注册表沉淀成会话状态。
- 新增事件 `ClientEvent.JoinedWorld` / `PositionUpdated` / `ChunksReceived`。
- 状态机不变：进入 play 后状态始终是 `PLAY`；出生进度由 `world` 与事件表达，
  不给协议状态另造一个“已出生”。

## 3. 与计划的偏差

| 计划 | 实际做法 | 原因 |
| --- | --- | --- |
| “处理 Set Center Chunk 等包” | 只处理需要回应的包与能形成会话事实的包 | `Set Center Chunk` 是服务端单向通知，无应答义务；把它做成事件没有消费方 |
| 世界模型 | 只累计区块数，不解码方块 | 方块解码、调色板与光照属于 M4，当前建立会引入没有调用方的数据结构 |
| 诊断日志 | 用 `Session.world` + 事件表达进度，不加日志框架 | 库内打日志会把诊断责任推给字符串；结构化状态更容易被断言 |

## 4. 验证

- `ClientSessionTest` 8 个用例通过（`forkEvery = 1`，整个类共用一次 Minestom 实例）。
  新增用例同时断言两侧事实：
  - 客户端：`JoinedWorld(entity=2, dimension=minecraft:overworld, mode=SURVIVAL)`、
    `PositionUpdated`、`ChunksReceived(total > 0)`；
  - 服务端：`PlayerSpawnEvent` 与 `PlayerLoadedEvent` 都触发。
- CLI 实测（harness 端口 25607）：

  ```text
  state=PLAY user=SpawnDemo
  event=joined entity=2 dimension=minecraft:overworld mode=SURVIVAL
  event=chunks batch=9 total=9
  event=position x=0.0 y=0.0 z=0.0 yaw=0.0 pitch=0.0
  event=chunks batch=8 total=17
  ...
  ```

  服务端同时打印 `event=player.configuring` → `event=player.spawned` → `event=player.loaded`。
- 区块批次持续以 8 个/批到达，说明批次确认确实被服务端接受（否则会在若干批后停住）。

## 5. 留给后续阶段

- 区块内容解码、实体与背包（M4）。
- 服务端 `Set Center Chunk` / 卸载区块的跟随逻辑：需要世界模型后才有意义。
- Android 运行时验证（M3）：出生链路只在 Desktop 上验证过。
