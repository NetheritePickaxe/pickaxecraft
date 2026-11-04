# 实施路线

状态：M2 已完成（出生链路打通），M4a–M4n 已完成（区块内容、实体跟踪、重生换维度、玩家身份、聊天读写、移动上行、背包读写、自身状态与装备、容器点击、方块交互、实体属性、物品键名与自定义名、实体交互）；M3 仍在等待真机或模拟器环境
日期：2026-11-04

## 1. 执行原则

- 每个阶段必须形成可运行、可验证的结果，不以“类已创建”作为完成标准。
- Headless 是第一阶段；长期下游是独立仓库中的 libGDX 轻量客户端，本路线只交付其核心库能力。
- Minestom 在本仓库只承担联调测试。可选单人游戏由外部 Desktop/Java 25 客户端集成，不进入共享核心，也不要求移动端内嵌服务端。
- 平台优先级为 Desktop → Android → iOS；Web/HTML5 不纳入目标。移动端支持必须以真实运行时验证为准。
- 先验证风险最高的链路，再扩大功能面积：当前主要平台风险是 Android 的 Netty 与 iOS 的 RoboVM，而不是协议本身。
- 一个阶段只引入完成该阶段所需的新抽象和依赖。
- 协议版本随协议库锁定，升级时同时调整协议库与 Minestom，并重跑真实握手。
- 当前阶段未通过验收时，不通过提前建设后续模块绕过问题。

## 2. 当前进度基线（2026-11-04，M2 完成）

- 模块为 `protocol-core`、`client-core`、`app`、`testing:minestom`。
- 调用方只依赖 `client-core` 即可完成状态查询与离线登录进入 play：`MinecraftClient` 提供
  `status` / `connect` / `close`，`Session` 提供有序事件流与幂等关闭。
- `protocol-core` 是协议库的唯一适配层，Netty 与 MCProtocolLib 不在 `client-core` 及以上模块的编译类路径
  （由 `verifyModuleBoundaries` 断言）。
- 联调用例全部经由公共 API：`ClientSessionTest` 覆盖状态查询、登录到 play、注册表接收、
  出生进入世界、会话隔离、服务端断开、连接失败、关闭后线程回收、事件溢出。
- 协议库的行为契约（回调顺序、KeepAlive 归属、状态切换点、断开语义）记录在
  [协议适配契约](protocol-contract.md)。
- Android 只完成构建链探针（[Android 反糖探针](android-dex-probe.md)）：D8 可产出 dex、
  `minSdk` 下限 26、`org.cloudburstmc.math:api` 必须剔除。运行时仍未验证。
- 出生链路已打通：客户端收到 `ClientboundLoginPacket`、位置同步与区块批次，服务端日志出现
  `event=player.spawned` 与 `event=player.loaded`。传送确认、区块批次确认、`player_loaded`
  全部由适配层自动完成。
- 世界模型：区块内容已可查询（M4a）——`Session.blockStateAt` / `biomeAt` / `chunk` 返回解码后的
  方块状态与生物群系；实体已可跟踪（M4b）——`Session.entity` / `entities` 返回服务端视角的位置
  与朝向，增删通过事件流可见；玩家身份可解析（M4d）——`Session.players` / `nameOf` 把实体映射到
  用户名；聊天可读（M4e）——`Session.chat` 回看最近消息，三类来源统一成 `ChatMessage`，
  格式键与参数来自 `chat_type` 注册表；聊天可发（M4f）——`Session.sendChat` / `sendCommand`
  把文本交给服务端，广播回来的消息回到事件流与记录；移动可发（M4g）——`Session.moveTo` /
  `look` 把位置与朝向声明给服务端，只发变化的分量；背包可读（M4h）——`Session.inventory` /
  `container` / `heldItem` 给出窗口槽位上的物品与容器内容，`selectHotbarSlot` 可切换选中格；
  自身状态可读（M4i）——`Session.status` 给出生命/饥饿/经验，实体快照带 `equipment`；
  容器可操作（M4j）——`Session.clickSlot` 拿起、放下、Shift 转移、换到快捷栏、丢弃，
  `closeContainer` 关窗，`cursorItem` 读光标；世界可动（M4k）——`Session.digBlock` /
  `useOnBlock` 挖掘与放置，服务端下发的方块变更写回区块缓存（写时复制），交互序号作为回执；
  属性可读（M4l）——`Session.attributes` / `attribute` 按键合并累积，键名表由 Minestom 注册表导出；
  物品可读名（M4m）——`InventoryItem.key` / `customName`，键名表同样是版本资产；
  实体可交互（M4n）——`Session.attack` / `interactWith` / `interactAt` / `swing`，
  伤害包转成 `EntityDamaged` 事件。
  光照、高度图与方块实体仍未建立。

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

## 5. M2：Minestom 离线登录 MVP（已完成）

实施记录（含根因与偏差）见 [M2 实施记录](plans/m2-implementation-plan.md)。

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

### 实际结论

- 四条验收标准全部满足：`ClientSessionTest.登录后完成出生并进入世界` 同时断言客户端侧事件
  （JoinedWorld / PositionUpdated / ChunksReceived）与服务端侧里程碑（PlayerSpawnEvent / PlayerLoadedEvent）。
- 此前“停在 `player.configuring`”不是协议问题，而是 harness 用错了启动入口：
  `process().start()` 只打开监听端口，不启动线程调度器与 tick 线程，玩家永远留在
  `playWaitingPlayers` 队列里。改用 `MinecraftServer.start()` 后出生立即完成。
- 剩下一项不属于本阶段：区块内容解码与世界模型（M4）。

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

### 实际进展：M4a 区块内容（2026-11-04）

实施记录见 [M4a 实施记录](plans/m4a-chunk-model.md)。已交付：

- 适配层解码区块（三种调色板形态、位宽与截断校验），维度高度取自 `dimension_type` 注册表；
- `Session.chunk` / `blockStateAt` / `biomeAt` 返回解码后的内容，未加载与越界分开表达；
- 区块走独立缓存而不进入有界事件队列，出生时的 300+ 列不会打满 256 容量；
- 联调用例与真实服务端 `stateId` 对账，CLI 打印区块摘要。

### 实际进展：M4b 实体跟踪（2026-11-04）

实施记录见 [M4b 实施记录](plans/m4b-entity-tracking.md)。已交付：

- 适配层按 id 累积相对位移（`short / 4096`）与传送的相对标记，向上只给完整快照；
- `Session.entity` / `entities` / `entityCount` 查询，增删走事件流、位置只写缓存；
- 联调用例用服务端的真实位置对账，覆盖生成、传送、逐 tick 相对移动与移除。

### 实际进展：M4c 重生与维度切换（2026-11-04）

实施记录见 [M4c 实施记录](plans/m4c-respawn.md)。已交付：

- `ClientboundRespawnPacket` 重新解析维度并作废旧世界的区块与实体；
- 区块计数与 `player_loaded` 复位，新维度首批区块到达后重发；
- 新增 `ClientEvent.Respawned`，世界快照换维度时清空位置；
- 联调用例从主世界切到下界，对账 `minY`、section 数量与新维度方块。

### 实际进展：M4d 玩家身份（2026-11-04）

实施记录见 [M4d 实施记录](plans/m4d-player-identity.md)。已交付：

- 玩家列表按 UUID 合并多次增量（档案、延迟、游戏模式），向上一个包一个事件；
- `Session.players` / `player(uuid)` / `playerCount` / `nameOf(entityId)` 完成实体 → 名字的关联；
- 实体元数据只读自定义名称（下标 2），其余下标因缺少实体类型表不读；
- 联调用例用两个并发会话验证列表、关联与改名，CLI 摘要打印在线玩家。

### 实际进展：M4e 聊天（2026-11-04）

实施记录见 [M4e 实施记录](plans/m4e-chat.md)。已交付：

- 系统、玩家、伪装三类消息统一成 `ChatMessage`，保留发送者、格式与编号；
- 格式从 `chat_type` 注册表解析成“键 + 参数顺序”，不内置翻译表；
- 每条玩家消息回 `chat_ack`，维持服务端的待确认窗口；
- 有界聊天记录（默认 200 条），删除包按编号移除对应条目；
- 联调用例逐项对账三类消息，并验证删除只影响被引用的那条。

### 实际进展：M4f 聊天上行（2026-11-04）

实施记录见 [M4f 实施记录](plans/m4f-chat-upstream.md)。已交付：

- `Session.sendChat` / `sendCommand` 发送聊天与命令，只有 play 阶段可发，非 play 与已关闭时
  明确抛错而不是把包塞进待发队列；
- 消息不带签名（离线模式可发；安全聊天需要会话密钥，属于 M5），last-seen 窗口按“什么都没
  确认”填写，与服务端记录的语义一致；
- 输入校验在本地完成：256 字符上限、单行、命令剥一层前导斜杠；
- 不做本地回显——服务端广播回来的那条才是回执，用例同时断言服务端收到原文与客户端收到广播；
- CLI 新增 `connect ... --say <文本>`，出生后发送一条消息并打印 `event=chat.sent`。

### 实际进展：M4g 移动上行（2026-11-04）

实施记录见 [M4g 实施记录](plans/m4g-movement-upstream.md)。已交付：

- `Session.moveTo` / `Session.look`：把位置与朝向声明给服务端，只有 play 阶段可发；
- 与上一姿态比较后只发变化的分量（`pos` / `rot` / `pos_rot` / `status_only` / 不发包），
  选择逻辑做成纯函数并用单测钉住；
- 服务端接受移动时不回包，因此本地姿态在发送后立即推进，`Session.world.position` 表示
  “本端声明的坐标”，服务端纠正时由位置同步覆盖；
- 不做本地物理与预测、不声明水平碰撞、只挡 NaN/无穷坐标与角度；
- 联调用例用服务端 `PlayerMoveEvent` 的目标坐标对账，并覆盖“服务端传送后从新坐标续走”；
- CLI 新增 `connect ... --walk <格数>`，按 20 次/秒沿 +X 走并打印每一步。

### 实际进展：M4h 背包与容器（只读）（2026-11-04）

实施记录见 [M4h 实施记录](plans/m4h-inventory.md)。已交付：

- `Session.inventory` / `container` / `heldItem` 读窗口内容；`Container` 是不可变快照，
  物品只有协议 id 与数量，空槽是 null；
- 两套槽位编号在适配层换算（护甲段反序），对外只暴露窗口槽位，`PlayerSlotMappingTest` 钉住；
- 单槽更新增量累积、整包内容整体替换；内容变化只报 `InventoryChanged(windowId)`，不逐槽发事件；
- `Session.selectHotbarSlot` 上行切换选中格：接受无回执，本地推进、服务端纠正时覆盖；
- 联调用例逐格对账背包与箱子，覆盖开箱/关箱、单槽更新、选中格与手持物品；
- CLI 新增 `connect ... --hold <0-8>`，摘要行打印选中格与背包槽数。

### 实际进展：M4i 自身状态与实体装备（2026-11-04）

实施记录见 [M4i 实施记录](plans/m4i-player-state.md)。已交付：

- `Session.status`（StateFlow）与 `ClientEvent.StatusChanged`：生命/饥饿/饱和度、经验条/等级/总量，
  两个包各写一半，字段可空以区分“还没收到”与“真的是 0”；
- `TrackedEntity.equipment`：装备走独立包，不依赖实体元数据下标，所有实体都成立；
  空物品表示清空该槽，因此删键而不是写一个数量 0 的物品；
- 重生时状态归零（属于上一个身体），关闭与溢出时一并清空；
- 联调用例逐项对账三个包，并验证经验包不影响生命值、清空装备后槽位消失。

仍未交付：生物血量（元数据下标依赖实体层级）、状态效果、物品组件（自定义名除外）、
拖拽与创造模式取整堆、容器属性（熔炉进度之类）、光照/高度图/方块实体、跨服转移、命令补全、
冲刺/潜行/飞行状态、骑乘、挖掘进度的服务端认可、libGDX 消费工程。
（属性表、物品可读名与实体交互已分别由 M4l/M4m/M4n 交付。）

### 实际进展：M4j 容器点击与关闭窗口（2026-11-04）

实施记录见 [M4j 实施记录](plans/m4j-container-click.md)。已交付：

- `SlotClick`（`Pickup` / `QuickMove` / `SwapWithHotbar` / `Throw` / `PickupAll`）表达点击意图，
  到「动作类型 + 参数」的映射集中在 `ContainerClicks`，由 `ContainerClicksTest` 钉住；
- `Session.clickSlot`（作用于活动窗口，也可指定窗口）与 `closeContainer`，`stateId` 跟着快照走，
  适配层不需要跨线程读自己的可变状态；
- **不做本地预测**：`changedSlots` 恒空、`carriedItem` 填点击前的光标，服务端发现差异后重发
  权威内容，本地视图因此绝不漂移；
- 光标记在活动窗口上（`cursorItem()`），保证“点击时声明的光标”与“快照里的光标”是同一份；
- 关闭窗口是第二处“成功没有回执”：客户端主动关窗时服务端不回包，关闭在发出时本地生效；
- 联调用例走完整搬运链路：拿起 → 放进背包 → Shift 转移 → 丢弃 → 关窗，客户端与服务端双侧对账；
- CLI 新增 `--click <槽位>[:动作]` 与 `--close-window`。

### 实际进展：M4k 方块交互（2026-11-04）

实施记录见 [M4k 实施记录](plans/m4k-block-interaction.md)。已交付：

- `BlockPosition`（带协议位宽校验）、`BlockFace`、`BlockChange`、`Hand` 四个公共类型；
- `Session.startDigging / cancelDigging / finishDigging / digBlock`：挖掘三段原语加
  “开始+完成”的便捷组合，坐标与面在三段之间必须一致（文档约定 + 单测钉住映射）；
- `Session.useOnBlock`（放置或与方块交互，光标默认方块中心）与 `useItem`（对空气用物品）；
- 交互序号：客户端自增、服务端在 `block_changed_ack` 里回送，`Session.lastAcknowledgedSequence`
  与 `ClientEvent.BlockAcknowledged` 暴露这条回执链路；
- 方块变更落回缓存：`block_update` / `section_blocks_update` 统一成 `BlocksChanged` 事件，
  `ChunkColumn.withBlock` 写时复制（复制受影响 section 并展开成每格一个 int），空层计数按
  增量维护（0 恒为空气）；
- 联调用例两侧对账：挖掘后 `instance.getBlock` 变空气且客户端 `blockStateAt` 变 0；放置后
  两侧同见 `minecraft:stone`，回执序号前进；
- CLI 新增 `--dig` 与 `--place`（放置点偏东一格：放进玩家自己占据的格子会被服务端碰撞检查
  拒绝，只回确认不下发方块变更）。

### 实际进展：M4l 实体属性（2026-11-04）

实施记录见 [M4l 实施记录](plans/m4l-attributes.md)。已交付：

- `update_attributes` 按键合并累积（出生时全量、之后增量），`Session.attributes(entityId)` /
  `attribute(entityId, key)` 给出累积快照，`EntityAttributesUpdated` 事件只带本包变化的项；
- 属性键名由 Minestom 注册表导出的 `AttributeTable` 还原——协议库内置表按旧版编号错位，
  升级协议库时必须重跑导出核对；
- 联调用例专门验证「合并不得抹掉旧键」：先改最大生命再改移动速度，最后两个键都要在。

### 实际进展：M4m 物品键名与自定义名（2026-11-04）

实施记录见 [M4m 实施记录](plans/m4m-item-names.md)。已交付：

- `ItemKeys` 由 Minestom 注册表导出（协议 776，1537 项，id 连续），`InventoryItem.key` 把
  数字 id 翻译成 `minecraft:*` 键名——`minecraft:item` 注册表不随登录下发，表是版本资产；
- `InventoryItem.customName` 读取 `custom_name` 组件；空文本组件与 null（没有组件）语义不同，
  两者都不做归一；
- 联调用例：带名石头 + 无名钻石，断言一个 `customName` 有值、另一个是 null。

### 实际进展：M4n 实体交互（2026-11-04）

实施记录见 [M4n 实施记录](plans/m4n-entity-interaction.md)。已交付：

- 26.2 把攻击拆成独立的 `attack` 包：`Session.attack(entityId)`；`interactWith`（右键交互）、
  `interactAt`（带命中点偏移的交互）与 `swing`（挥手，纯视觉）；
- 攻击与交互均无回执（第四例）：打中了看 `EntityDamaged` 事件（`damage_event` 包）与实体
  状态变化，服务端事实就是全部证据；
- 联调用例：生成僵尸后攻击 + 右键，两侧对账。

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
