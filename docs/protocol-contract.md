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

`FOLLOW_TRANSFERS` 默认为 true 时，`ClientboundTransferPacket`
会让库**在回调里新建一个 `ClientNetworkSession` 并 `connect(true)`**，那个会话不归本项目管理，
也无法被上层关闭。

**本项目的做法**：显式设置 `FOLLOW_TRANSFERS = false`。转移包于是只留下一条事实
（`ProtocolEvent.TransferRequested`），连接状态不变；要不要换服、什么时候换，由上层决定
（`MinecraftClient.followTransfer`）。

**转移包不结束会话。** 服务端可以只发一个包就不再有下文（Minestom 的
`PlayerConnection#transfer` 就是如此），原版客户端是收到后**自己**断开的。
所以「等断开事件再处理转移」这条路走不通：等不到。把转移当成一次普通的断开，
还会得到一个错误的结论——连接明明还在。

包在配置阶段与 play 阶段都会出现（`ClientListener` 两个分支各有一份相同的逻辑），
两处都要处理；登录时被代理转移正是常见场景。

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

### 2.13 移动上行：四个包，缺的字段由服务端沿用上一状态

服务端收到的不是“一个位置”，而是三种移动包之一：`pos`（x/y/z + 状态位）、`rot`（yaw/pitch +
状态位）、`pos_rot`（全部），以及 `status_only`（只有状态位）。**包里没有的字段，服务端沿用
上一状态**，因此发哪个包决定了“什么保持不变”。

由此产生三条本层义务：

1. **选择在本地完成**：必须与上一状态比较后才能决定发哪个包。选错不会报错，只会表现为
   “朝向对了但人没动”（该发 `pos_rot` 却发了 `rot`）或“人被拽回原朝向”（该发 `pos` 却发了
   `pos_rot` 且朝向是旧值）。本层把逻辑放在 `Movement.packet(current, target)`，由单测钉住。
2. **接受移动不回包**：服务端认可一次移动时什么都不发。所以“发出去了”与“服务端同意了”没有
   回执可等；本端发送后立即推进本地姿态（`LocalPose`）并上报 `PositionSent`，服务端不同意时
   用位置同步/传送（`PositionUpdated`）覆盖。这也是真实客户端的做法。
3. **状态位是声明，不是测量**：`onGround` / `horizontalCollision` 由客户端自己判断。无头客户端
   不做碰撞检测，因此恒报 `onGround=false`（除非调用方指定）、`horizontalCollision=false`。
   报 `horizontalCollision=true` 会让服务端替本端减速——不知道的事不能声明。

坐标与角度只挡 NaN/无穷：前者会让服务端的距离校验得出错误结论，后者没有定义。是否“走得太远”
由服务端判断（Minestom 在超过 `Entity.MAX_COORDINATE` 时踢人），本层不设任意上限。

### 2.14 背包与容器：两套槽位编号，成功同样没有回执

**编号有两套，混用不报错但会放错格子**：

- **窗口槽位**（`container_set_content` / `container_set_slot`）：窗口 0 的 0 号是合成结果、
  1–4 是合成格、5–8 是盔甲、9–35 主背包、36–44 快捷栏、45 副手。打开箱子时前 N 格是容器内容，
  其后才是玩家背包段。
- **背包槽位**（26.2 的 `set_player_inventory`，不带窗口 id）：0–8 快捷栏、9–35 主背包、
  36–39 依次是靴/腿/胸/头、40 副手。护甲段与窗口槽位**反序**：背包 36 是靴子，窗口 8 也是靴子，
  而窗口 5 是头盔。

本层对外只暴露窗口槽位（`PlayerInventoryLayout`），另一套在适配层换算并单测。

另外三点：

1. **内容包只保证它自己那部分**：Minestom 的打开容器整包只带容器自己的槽位，原版会连玩家背包
   一起发。因此“玩家背包”永远从窗口 0 读，不要假设它出现在容器窗口的尾部。
2. **窗口 0 不可关闭**：`container_close` 指向窗口 0 时忽略——把背包当成“已关闭”会让后续查询
   凭空失败。
3. **`set_carried_item` 成功没有回执**：服务端只在拒绝或改写时下发 `set_held_slot`。与移动包一样，
   本端在发送后立即推进选中格，服务端纠正时覆盖。

### 2.15 自身状态与装备：两个包各管一半，装备不走元数据

- **生命/饥饿**（`set_health`）一个包给生命、饥饿、饱和度；**经验**（`set_experience`）另一个包给
  进度、等级、总量。两者独立到达，因此 `PlayerStatus` 的六个字段全部可空——用 0 当缺省会把
  “还没收到”和“真的是 0”混在一起。
- **装备**（`set_equipment`）是独立包，槽位是显式枚举（主手、副手、四护甲，26.2 还有 `BODY`、
  `SADDLE`），因此不依赖实体元数据下标，所有实体都成立。包里的物品为空表示**清空该槽**，
  所以要删键，而不是写一个数量 0 的物品。
- 生物血量仍然读不到：它只在元数据里，而下标的含义取决于实体层级。

### 2.16 点击槽位：一个包覆盖所有点击，预测字段由服务端用来去同步

`container_click` 用「动作类型 + 一个裸字节参数」表达全部点击：

| 动作 | 参数 | 语义 |
| --- | --- | --- |
| `CLICK_ITEM` | 0 左 / 1 右 | 拿起或放下整堆 / 半堆、一个 |
| `SHIFT_CLICK_ITEM` | 0 左 / 1 右 | 快速转移，落到哪一格由服务端决定 |
| `MOVE_TO_HOTBAR_SLOT` | 0–8 | 与快捷栏某一格交换（40 是副手） |
| `DROP_ITEM` | 0 一个 / 1 整堆 | 丢弃；槽位 `-999` 才是丢光标上的物品 |
| `FILL_STACK` | 0 | 双击把同类物品收拢到光标 |

包里还有三处与服务端状态对账的字段，本层这样填：

1. **`stateId` 用本端快照里的值**：服务端据此判断客户端看到的是不是最新内容。本端不做本地内容
   缓存之外的任何推测，所以这个值就取自调用方手上那份 `Container`。
2. **`changedSlots` 恒为空**：重算点击结果需要物品组件（`max_stack_size`）与快速转移的合并规则，
   属于另一套注册表，本层不建。
3. **`carriedItem` 填点击前的光标**（哈希只带 id 与数量，不带组件）：如实声明“我手上是这些”。
   服务端对不上就重发权威内容。反过来填 `null` 是个会留下错误状态的谎——当点击把光标清空时，
   服务端会认为两端一致而不发任何东西，本端就会永远停在点击前的那堆物品上。

结论：**点击不做本地预测**，每次点击后本地视图由服务端重新下发的内容决定；代价是每次点击
多一个整包，换来的是绝不漂移。

`container_close` 上还有一个反向的例外：**客户端主动关窗时服务端不回 `container_close`**，
它只在服务端自己关窗（插件调用 `closeInventory`）时下发。因此本层在发出关闭包后立即移除窗口并
上报关闭事件；服务端随后若再发关闭包，找不到窗口，不会重复上报。

### 2.17 光标不属于任何窗口

`set_cursor_item` 不带窗口 id。原版客户端把光标存在当前打开的 menu 上，因此本层把它记在
**活动窗口**（有打开的容器时是它，否则是窗口 0）的快照上——这一点很重要：点击这个窗口时声明给
服务端的 `carriedItem` 与这个窗口快照里的 `cursor` 必须是同一份，否则就会出现“点了、光标空了、
本地却还拿着一堆东西”。

### 2.18 方块交互：序号是动作的回执

`player_action`（挖掘三段：开始/取消/完成）、`use_item_on`（对方块使用物品：放置或交互）、
`use_item`（对空气使用物品）都带一个**客户端自增**的序号；服务端处理后在 `block_changed_ack`
里原样回送。这是“动作确实处理完了”的唯一直接证据——方块变更不能当回执用，它也可能来自别的
玩家或别的动作。

- 挖掘三段必须带**同一组坐标与面**：服务端按两者配对，中途改动等于一次新的动作。
- 挖掘耗时属于游戏规则（方块硬度、工具、状态效果），本库不算；`digBlock`（开始+完成）
  在宽松的服务端可用，严格的服务端会忽略“进度不足”的完成包。
- `use_item_on` 的光标是点击点在方块内的相对坐标（三轴 0..1），服务端据此验算命中点；
  坐标位宽为 x/z 26 位、y 12 位，**越界会被静默截断**，必须在发送前校验。
- 服务端不保证接受放置：目标格被玩家自己的碰撞盒占据、冒险模式、世界边界都会让放置
  变成“只回确认、不下发方块变更”。

### 2.19 方块变更是权威结果

`block_update`（单格）与 `section_blocks_update`（整段批量，爆炸/水流/回滚）是服务端世界
的权威事实：挖掉方块之后本地区块缓存必须跟着变，否则 `blockStateAt` 永远返回旧值。
本库把两者统一成一批 `BlockChange`（一格一条），一个包一条事件——批量包一次可能带几百格，
逐格发事件会打满有界缓冲。

区块列**不可变**的约定保持不变：写入走写时复制（`ChunkColumn.withBlock`），复制受影响的
section 并展开成每格一个 int，未改动的 section 保留压缩形态。空层计数 `blockCount` 按增量
维护，依据是“全局方块状态 id 0 恒为 `minecraft:air`”这条跨版本不变量。

### 2.20 属性包只带被更新的项

`update_attributes` 在出生时带全量、之后只发改动的属性。客户端必须按键合并——整表替换
会把没动的属性抹掉。属性实例在服务端是按需创建的：刚出生的实体可能发出**空的**属性包，
「没有事件」与「空事件」都不是属性被清掉的证据。

协议库的内置属性表按旧版编号反查 wire id，键名会错位；本层用 `type.id`（= ordinal = wire id）
还原编号后查自己的表（由 Minestom 注册表导出，协议库升级时必须核对）。

### 2.21 攻击没有回执

26.2 把攻击拆成独立的 `attack` 包。目标不存在或不在视野里时服务端**静默忽略**；打中了，
结果以伤害事件包（`damage_event`，带伤害类型与来源实体）与实体状态变化体现。
交互（右键）同样没有回执。挥手是纯视觉，与判定无关。

### 2.22 物品 id 到键名没有协议内的注册表

协议库的 `ItemStack` 只带数字 id；`minecraft:item` 注册表**不会**随登录下发，
id → 键名只能由客户端按版本维护。本仓库的表由 Minestom 注册表导出（协议 776，1537 项、
id 连续），升级协议库时必须重新导出核对。`custom_name` 组件随物品补丁表下发，
空文本是合法值（与“没有组件”不同），渲染层不应把两者混为一谈。

### 2.23 运动按键是状态包，能力是服务端权威

`player_input` 一个字节带七个布尔位，服务端按最后一次收到的字节为准，因此每次改动都要
整份重发。冲刺另外需要 `player_command`（带本端实体 id），只发 input 位不会让服务端改速度。
飞行请求（`player_abilities` 上行）**没有回执**：服务端允许时下发权威
`player_abilities`（flags + 两个速度倍率），拒绝时什么都不发。因此本端声明的按键与服务端
认可的能力必须分成两个状态，不能用上行包推断“已经在飞”。

### 2.24 状态效果不带注册表，一次只推一个

`update_mob_effect` / `remove_mob_effect` 里的效果 id 是 `Effect` 枚举 ordinal；
`minecraft:mob_effect` 注册表不随登录下发，键名由本仓库按版本固化（`EffectKeys`，
协议 776 共 40 项）。一个包只处理一个效果，完整表必须由客户端按键合并；
`duration = -1` 表示无限，四个显示标志属于效果本身。升级协议库必须重新核对这张表。

### 2.25 容器属性只给下标，不给含义

`container_set_data` 带窗口 id、short 下标与 short 值，下标语义**取决于窗口类型**
（熔炉的 2 是熔炼进度，酿造台则不是）。协议不带类型信息，本层按下标累积而不翻译；
窗口关闭时该窗口的属性表一并作废。

### 2.26 实体元数据下标依赖实体层级

`set_entity_data` 只有「下标 → 值」，没有层级信息：`Entity` 层占 0–7（自定义名称是 2），
`LivingEntity` 层从 8 开始，血量在绝对下标 9；同一个 9 对 `Interaction` / `ItemDisplay`
是完全无关的 float 字段。协议不下发 `entity_type` 注册表，因此本仓库按版本固化
`LivingEntityTypes`（由 Minestom 的 `packetType = LIVING|PLAYER` 导出，协议 776 为
158 种中的 93 种，名字与 MCPL `EntityType` 枚举逐项一致）。只有表内类型才读下标 9，
未知类型返回 `null` 而不是猜。玩家自己的血量走 `set_health`，不属于这条链路。
升级协议库必须重新导出并核对这张表。

### 2.27 物品组件是补丁，附魔注册表是动态的

`data_components` 只带**与物品原型不同**的项：给钻石剑设默认的 `max_damage = 1561` 时
补丁里没有这个键，客户端读到 `null`（含义是「补丁没提」，不是「物品没有耐久上限」）。
还原完整原型需要另一份物品默认组件表，本项目不建模。补丁里「带键但值为 null」表示删除，
「不带键」表示没提，两者对外结果一致，按 `get` 取值即可。
`unbreakable` 是 Unit 组件，存在即为真；`custom_name` 与 `item_name` 是两个字段。

附魔 id 来自**动态**注册表：配置阶段下发的 `minecraft:enchantment` 条目顺序才是 wire id，
静态数据文件既没有 id 字段也不是字母序。因此适配层保存下发顺序，解析时按 id 还原键名；
id 落在表外时保留 `unknown:<id>`。升级时要确认该注册表仍随配置阶段下发。

### 2.28 光照按掩码铺数组，光照层比 section 多两层

`level_chunk_with_light` 与 `update_light` 用同一套结构：`skyYMask`、`blockYMask`、
`emptySkyYMask`、`emptyBlockYMask` 四张位图，加两串 2048 字节的数组。数组**不写下标**：
掩码里第 n 个为 1 的位依次对应数组里的第 n 段。把「层号当成数组下标」写错时数据依然能解，
只是整片光照错位，表现为地形忽明忽暗。

光照层的编号从 world 最低 section 的**下一层**开始：一列 `sectionCount` 段区块对应
`sectionCount + 2` 层（层 0 在地表之下，最后一层在天上）。因此世界 Y 的层号是
`floorDiv(y - minY, 16) + 1`，`minY` 之下的负数区间用 `and 15` 取层内 Y 恰好等于 floorMod。

每层 2048 字节是 4096 个格的半字节：`index = (y << 8) | (z << 4) | x`，偶数格取低四位。
「掩码里没有这一层」与「`emptyXxxYMask` 里有这一层」含义不同：前者是本包没提（保持旧值），
后者是明确全黑（要覆盖缓存）。两者在 `LightPatch` 里统一成「替换这一层」，全黑共享一段
零数组，合并逻辑只有一条路径。

### 2.29 高度图固定 9 位、37 个 long，且只是快照

高度图不带位宽也不带长度：每格 9 位、按「一个 long 放 7 个值、值不跨 long」打包，
256 格固定 37 个 long（`256 / 7` 向上取整）。存储值是 `第一格空气的 Y - minY`，
所以世界坐标要加回 `minY`。种类（`WORLD_SURFACE` / `OCEAN_FLOOR` / `MOTION_BLOCKING` …）
只差「哪些方块算地表」，协议只给整数 id，顺序与 `HeightmapTypes` 枚举一致。

高度图是**服务端算好的快照**，只在整列下发时更新。本端改方块不会重算它：判断「空气/流体/树叶」
需要方块分类表，协议不提供。需要精确值时应等服务端重发整列，不要把旧快照当成最新地形。

### 2.30 方块实体类型是枚举序号，数据是可选的 NBT

`block_entity_data` 与整列区块里的方块实体都用「枚举序号 + NBT」表示类型。枚举顺序即 wire id，
名字是 `BlockEntityType` 成员的小写形式（`CHEST` → `minecraft:chest`）；这不是动态注册表，
随协议号固定，升级时核对枚举即可。NBT 可以为空：字段存在但没有内容表示「服务端没有数据」，
与明确下发一个空复合标签不同。

方块被换成非方块实体时服务端**不发删除包**——那条 `block_update` 已经说明旧实体不存在了。
客户端如果只在收到「删除」时才清缓存，会一直显示一个已经被挖掉的箱子。

### 2.31 命令树是扁平数组，节点之间用下标互相引用

`commands` 包下发的是一个数组：每个节点自带子节点下标数组与可选的重定向下标，根节点下标单独给。
下标就是数组位置，**不能重排或压缩**，否则子节点与重定向会指错地方。

节点标志位合并了四件事：类型（root/literal/argument）、是否可执行、是否有重定向、是否受限；
参数节点还会带 parser 与（部分 parser 专有的）附加字段，以及可选的补全类型键。
parser 的取值顺序是版本资产：本层按**名字**而不是序号映射，升级协议库后新增类型时
`CommandTreeParsingTest` 会先失败。

数值范围的上限/下限各有「是否设置」位，没设置时线上填的是 `Integer.MIN_VALUE` /
`Double.MAX_VALUE` 这类哨兵值——**float 的哨兵值与 double 不同**，必须按浮点自己的常量判断。

### 2.32 补全请求是上行里唯一有回执的一条

`command_suggestion` 带一个请求编号与**完整输入行**（含前导斜杠），服务端回
`command_suggestions` 时把编号原样带回，并给出 `start` / `length` 描述**要替换的区间**，
而不是匹配项自身的位置。同一连接上可以并发多个请求，靠编号配对。

服务端算不出候选时可以**什么都不回**（Minestom 在没有补全回调时就是这样）。因此
「等超时」与「收到空列表」是两种不同的事实：前者说明这条路径没有答案，后者说明服务端
认可了请求但没有可选值。本层把前者暴露成 `CompletionResult.NoAnswer`。

替换区间的下标相对发起请求时那段文本，拿别的文本套会错位；错位时本层返回 null 而不是
尽量修正——错位替换出一段假输入比不替换更难排查。

### 2.31 骑乘只有一份乘客列表，载具移动包不带实体 id

协议里**没有**「上载具」「下载具」两个包：只有 `set_passengers`，按载具下发一份**完整**
的乘客列表。空列表就是下车；本端成为乘客的判据只能是「自己的实体 id 出现在某条列表里」。
因此 `Session.vehicle` 必须由这份列表推出来，而不能等一个不存在的 `mount` 包。

`move_vehicle` 分两个方向，**都不带实体 id**：

- 下行（服务端 → 客户端）是权威坐标，一出现就只可能指本端当前乘坐的载具；
- 上行（客户端 → 服务端）同样指「你正坐着的那个载具」，服务端收到后直接改载具位置。

因此适配层必须自己维护「我在骑哪个实体」这份映射，否则下行的坐标无处落地。这份映射与
会话层的 `Session.vehicle` 是两份**不同用途**的状态：前者是网络线程上收包时的解析依据，
后者是对外可见的快照，二者的变化都源于同一条乘客列表包。

两条容易踩空的规则：

- **骑乘时不发玩家位置包**。原版客户端在 `isPassenger()` 时直接跳过位置同步；此时再说
  「我走到了某处」与服务端的载具位置冲突。本库在 `moveTo` / `look` 上直接拒绝，而不是
  发出去让服务端忽略——静默失效会让人以为自己在动。
- **坐标是绝对量**。实体移动包是 1/4096 的相对量，载具包是完整 double，不做叠加。

服务端**不保证**移除载具前补一条空乘客列表（Minestom 的 `kill` 会，但这不是协议承诺）。
因此实体消失时要顺带清掉这份映射与「我在骑它」，否则载具 id 会一直指向一个不存在的实体。


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
| 移动上行 | `Movement` + `McplConnection` | 与上一姿态比较后选最小的包；接受移动无回执，发送即推进本地姿态；状态位恒不声明碰撞 |
| 容器累积 | `MutableContainer` | 单槽更新按需补齐、整包内容整体替换；向上只交不可变 `Container` 快照 |
| 槽位换算 | `PlayerSlotMapping` | 背包槽位 → 窗口槽位；护甲段反序，独立单测 |
| 快捷栏上行 | `McplConnection` | `set_carried_item` 只在 play 阶段可发；接受无回执，发送即推进选中格 |
| 装备累积 | `McplConnection.applyEquipment` | 独立包按槽位合并，空物品删键；变化复写 `TrackedEntity` 走缓存 |
| 自身状态合并 | `client-core` | 生命/饥饿与经验两个包各写一半，向上给合并后的 `PlayerStatus` 快照 |

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
| 移动与转向上行：服务端 `PlayerMoveEvent` 里的坐标与朝向、传送后的续走、非法值被拒 | `ClientSessionTest.移动与转向服务端可见` |
| 移动包的最小选择规则（四种包 + 不发包） | `MovementTest`（9 例） |
| 背包、容器内容与快捷栏选中格 | `ClientSessionTest.背包与容器内容可从会话读到` |
| 槽位换算（含护甲段反序）与容器累积 | `PlayerSlotMappingTest`（6 例） |
| 生命/饥饿/经验与实体装备 | `ClientSessionTest.生命、饥饿、经验与装备对客户端可见` |
| 点击上行：拿起、放入背包、Shift 转移、丢弃、关窗，客户端与服务端双侧对账 | `ClientSessionTest.容器点击能把物品搬到背包` |
| 点击意图到包字段的映射（动作参数、快捷栏下标、光标声明、槽位校验） | `ContainerClicksTest`（9 例） |
| 挖掘/放置/对空气用物品的包字段（面的序号、坐标位宽、光标范围、序号） | `BlockInteractionTest`（8 例） |
| 属性键名还原（协议库内置表错位的规避）与属性包合并 | 联调 `实体属性可读且变化会推给本人`；`AttributeTable` 由注册表导出 |
| 攻击/交互/挥手的上行与「无回执」语义 | 联调 `攻击与交互实体后两侧同时可见` |
| 物品键名表（协议库不带注册表） | `ItemKeysTest`（锚点与越界）；联调 `物品键名与自定义名可读` |
| 方块变更写入区块缓存：写时复制、空层计数、旧列不变 | `ChunkColumnWriteTest`（5 例） |
| 挖掘上行：服务端 `PlayerFinishDiggingEvent`、两侧方块同时变空、回执序号前进 | `ClientSessionTest.挖掘方块后服务端世界与本地缓存同时变空` |
| 放置上行：服务端发石头、`PlayerBlockPlaceEvent`、两侧方块同为 `minecraft:stone` | `ClientSessionTest.放置方块后服务端与本地缓存同时可见` |
| 上行输入的长度、单行与斜杠规则 | `ChatInputTest`（8 例） |
| 运动按键：三类上行包到服务端、能力与本地声明分离 | 联调 `冲刺潜行与飞行的上行包服务端可见且能力由服务端权威下发`；`MovementInputTest`（3 例） |
| 状态效果表与按键合并、移除只影响一个键 | `EffectKeysTest`（2 例）；联调 `状态效果可按键合并且移除只影响被点名的效果` |
| 容器属性按下标累积 | 联调 `容器数值属性可读且多个下标互不覆盖` |
| 生物血量只在生物层级读下标 9，掉落物不误读 | `LivingEntityTypesTest`（2 例）；联调 `生物血量按实体层级读取而非只看元数据下标` |
| 物品组件补丁语义与动态附魔注册表还原 | `ItemComponentsTest`（4 例）；联调 `物品组件可读且附魔键名来自动态注册表` |
| 光照掩码到数组的对齐、空掩码与「没提」的区别 | `LightPatchesTest`（4 例） |
| 光照半字节顺序、光照层的上下各一层、世界 Y 换算 | `ChunkEnvironmentTest`（9 例，含高度图与方块实体） |
| 光照与方块实体穿过协议：整列下发与增量两条路径 | `ClientSessionTest.区块的光照、高度图与方块实体都可读` |
| 命令树的节点下标、参数类型/属性、重定向与字面量路径 | `CommandTreeParsingTest`（13 例，含参数类型表与协议库取值对账） |
| 命令树与补全穿过协议：顶层命令、参数类型、候选过滤、替换区间、无回包情形 | `ClientSessionTest.命令树与补全由服务端给出` |
| 转移包只上报地址、不改变会话，换服由调用方显式发起 | `ClientSessionTest.转移请求只上报地址，换服由调用方决定` |
| 骑乘：乘客列表推出上/下车、载具包双向、骑乘时封住玩家位置包 | `ClientSessionTest.骑乘时乘客关系可读，载具移动走独立包` |
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
4. 重新导出版本资产并核对条目数：`AttributeTable`（属性）、`ItemKeys`（物品）、
   `EffectKeys`（效果）、`LivingEntityTypes`（实体层级）；后者的名字必须与新版
   `EntityType` 枚举一致，元数据基础层的字段数变化会直接移动血量下标。
5. 核对区块附属数据的格式：光照层数应为 `sectionCount + 2`、每层 2048 字节；高度图应为
   37 个 long；方块实体枚举的名字要与 `BlockEntityType` 逐项一致（`LightPatchesTest`、
   `ChunkEnvironmentTest` 会先失败）。
6. 若上游改了默认执行器或 `EventLoopGroup` 归属，确认适配层的覆盖仍然生效。
7. 核对命令树：`CommandParser` 是否新增类型（`CommandTreeParsingTest.参数类型表覆盖协议库的全部取值`
   会先失败）、参数属性类是否新增字段、`ClientboundCommandsPacket` 的标志位是否变化。
8. 若上游开始自带区块解码，或调色板线格式变化（例如重新带回 long 数组长度前缀），
   先跑 `ChunkDecoderTest` 与 `ClientSessionTest.出生后读到的方块状态与服务端一致`。
9. 核对转移：`ClientboundTransferPacket` 是否仍在 `packet.common`、是否仍有配置阶段分支；
   `FOLLOW_TRANSFERS` 的默认值若变成 false，本项目的显式设置就变成了纯文档作用。
10. 核对载具：`set_passengers` 是否仍按载具下发完整列表、`move_vehicle` 是否仍不带实体 id；
    若上游新增了上/下车包，`Session.vehicle` 的推导要改回用显式包。
11. 更新本文与 `docs/architecture.md` 中受影响的描述。
