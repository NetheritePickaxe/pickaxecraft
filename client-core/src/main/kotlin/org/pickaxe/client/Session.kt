package org.pickaxe.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pickaxe.protocol.ProtocolCloseReason
import org.pickaxe.protocol.ProtocolConnection
import org.pickaxe.protocol.ProtocolEvent
import org.pickaxe.protocol.ProtocolFailureKind
import org.pickaxe.protocol.ProtocolListener
import org.pickaxe.protocol.ProtocolPhase
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.Hand
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import org.pickaxe.protocol.inventory.SlotClick
import org.pickaxe.protocol.world.BlockChange
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition
import org.pickaxe.protocol.world.ChunkColumn
import org.pickaxe.protocol.world.RemotePlayer
import org.pickaxe.protocol.world.TrackedEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次已建立的客户端会话。
 *
 * 线程模型：协议回调发生在网络线程，只做一次有界入队；状态迁移、事件分发与
 * 调用方可见的一切都在会话自己的 Actor 协程里按到达顺序执行。因此
 * [state] 与 [events] 天然有序，且调用方永远不会在网络线程上被回调。
 *
 * 区块是这条规则的唯一例外：它是高频率、大体积的世界数据，走独立的有界缓存
 * （见 [chunk]），写入的是不可变对象，因此读取不需要等待 Actor，也不会挤掉控制事件。
 *
 * 生命周期：[close] 幂等；关闭后 [state] 变为 [SessionState.CLOSED]，
 * [events] 结束，网络线程随之回收。会话也可以因服务端断开或协议错误自行终止，
 * 终止事件恰好投递一次。
 */
public class Session internal constructor(
    /** 该会话连接的服务端地址。 */
    public val address: ServerAddress,
    /** 离线登录使用的用户名。 */
    public val username: String,
    private val options: ClientOptions,
    private val scope: CoroutineScope,
) : AutoCloseable {

    private val mutableState = MutableStateFlow(SessionState.CONNECTING)

    /** 当前状态，可随时读取；终态为 [SessionState.CLOSED]。 */
    public val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val mutableRegistries = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 会话级注册表快照：注册表 id 到条目数，随配置阶段累加。 */
    public val registries: StateFlow<Map<String, Int>> = mutableRegistries.asStateFlow()

    private val mutableStatus = MutableStateFlow(PlayerStatus())

    /**
     * 本端生命、饥饿与经验；未收到任何相关包时各字段为 null。
     *
     * 与 [world] 分开：它不是世界状态，也不随维度切换而失效，重生时会被服务端重新下发。
     */
    public val status: StateFlow<PlayerStatus> = mutableStatus.asStateFlow()

    private val mutableWorld = MutableStateFlow<WorldSnapshot?>(null)

    /**
     * 会话级世界状态，进入 play 之前为 null。
     *
     * 出生过程由协议层自动推进；调用方读这里就能知道“到哪一步了”，
     * 不需要自己拼装包信息。
     */
    public val world: StateFlow<WorldSnapshot?> = mutableWorld.asStateFlow()

    private val inbox = Channel<ProtocolEvent>(capacity = options.eventBufferCapacity)
    private val outbox = Channel<ClientEvent>(capacity = options.eventBufferCapacity)

    /**
     * 会话私有的区块缓存，键是打包后的区块坐标。
     *
     * 这里绕开了 [inbox]：区块是世界数据，出生时会连续到达数百列且单项体积很大，
     * 放进与信用量绑定的队列会挤掉控制事件，消费者慢时还会直接终止会话。缓存的值是
     * 不可变的 [ChunkColumn]，因此网络线程写入、调用方任意线程读取都不需要额外加锁。
     */
    private val chunks = ConcurrentHashMap<Long, ChunkColumn>()

    /**
     * 会话私有的实体缓存，键是协议内的实体 id。
     *
     * 与区块同属**世界数据**：移动包每秒可达上百条，放进与信用量绑定的队列会让
     * 慢消费者的缓冲被位置增量填满。实体状态本身是可重建的（每次更新都带完整快照），
     * 因此按 id 缓存、按需查询比逐条投递更合适；实体增删仍通过事件流可见。
     */
    private val entities = ConcurrentHashMap<Int, TrackedEntity>()

    /**
     * 会话私有的玩家列表，键是玩家档案 UUID。
     *
     * 与区块、实体同样走缓存：登录时服务端会一次性下发全服玩家，逐个发事件会把
     * 有界缓冲打满。适配层已经把同一个人的多次更新合并成一条，这里只需要覆盖。
     */
    private val players = ConcurrentHashMap<UUID, RemotePlayer>()

    /**
     * 会话私有的窗口内容，键是协议窗口 id；0 恒为玩家背包。
     *
     * 与区块、实体同样走缓存：一次打开箱子会连着下发整包内容与若干单槽更新，
     * 放在与信用量绑定的队列里会让玩家背包的 46 个槽把缓冲占满。
     */
    private val containers = ConcurrentHashMap<Int, Container>()

    /**
     * 当前快捷栏选中格（0–8）。
     *
     * 服务端接受切换时不回包，所以本端 [selectHotbarSlot] 成功后立即更新；服务端拒绝或改写时
     * 会下发 `set_held_slot`，以它的值为准。
     */
    @Volatile
    private var heldSlotIndex: Int = 0

    /**
     * 最近一次被服务端确认的方块交互序号。
     *
     * 序号由适配层自增，服务端在 `block_changed_ack` 里原样回送；这是“挖/放的动作确实
     * 处理完了”的唯一直接证据。会话刚建立时为 0——还没有任何交互被确认过。
     */
    @Volatile
    private var lastAcknowledgedSequenceValue: Int = 0

    /**
     * 会话私有的聊天记录，保留最近的 [ClientOptions.chatHistorySize] 条。
     *
     * 只在 Actor 里写入，读取可能来自任意线程，所以用一把小锁保护。聊天不走
     * 世界数据那套无锁缓存：它不能被覆盖，只能追加，且顺序就是显示顺序。
     */
    private val chatLock = Any()
    private val chatLog = ArrayDeque<ChatMessage>()

    /** 有序事件流，单消费者；会话终止后正常结束。 */
    public val events: Flow<ClientEvent> = outbox.consumeAsFlow()

    private val terminalError = AtomicReference<ClientException?>(null)
    private val closing = AtomicBoolean(false)
    private val overflowing = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var connection: ProtocolConnection? = null

    private var actor: Job? = null

    internal fun start() {
        val created = ProtocolConnection.login(
            address = address,
            username = username,
            listener = ProtocolListener(::onProtocolEvent),
            connectTimeoutSeconds = options.connectTimeoutSeconds(),
            readTimeoutSeconds = options.readTimeoutSeconds(),
        )
        connection = created
        actor = scope.launch { runActor() }
        created.connect()
    }

    /** 关闭会话。阻塞到网络资源回收完成，重复调用无副作用。 */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        try {
            connection?.close()
        } finally {
            inbox.close()
            actor?.cancel()
            // 区块与实体都属于本次会话；不清理会让已关闭的会话继续持有整个出生点视野。
            chunks.clear()
            entities.clear()
            players.clear()
            containers.clear()
            heldSlotIndex = 0
            lastAcknowledgedSequenceValue = 0
            mutableStatus.value = PlayerStatus()
            clearChat()
            mutableState.value = SessionState.CLOSED
            outbox.close()
        }
    }

    /** 等待进入 play；失败或提前关闭时抛出对应异常。 */
    internal suspend fun awaitPlay() {
        mutableState.first { it == SessionState.PLAY || it == SessionState.CLOSED }
        if (mutableState.value != SessionState.PLAY) {
            throw terminalError.get() ?: ClientException.Closed()
        }
    }

    /** 当前已缓存的区块列数量。 */
    public val loadedChunks: Int get() = chunks.size

    /** 按区块坐标取一列区块；未加载、已卸载或会话已关闭时返回 null。 */
    public fun chunk(chunkX: Int, chunkZ: Int): ChunkColumn? = chunks[chunkKey(chunkX, chunkZ)]

    /**
     * 按世界坐标读取方块状态 id；所在区块未加载时返回 null。
     *
     * id 是协议的全局方块状态 id。要得到 `minecraft:stone` 这样的名字需要版本数据表，
     * 那属于下游客户端的资产，不进入本库。
     *
     * @throws IllegalArgumentException 区块已加载，但 Y 超出该维度高度。
     */
    public fun blockStateAt(x: Int, y: Int, z: Int): Int? =
        chunk(x shr 4, z shr 4)?.blockStateAt(x, y, z)

    /** [blockStateAt] 的方块坐标版本；交互结果核对时少写三个参数。 */
    public fun blockStateAt(position: BlockPosition): Int? =
        blockStateAt(position.x, position.y, position.z)

    /**
     * 按世界坐标读取生物群系 id（`minecraft:worldgen/biome` 注册表下标）；
     * 所在区块未加载时返回 null。
     */
    public fun biomeAt(x: Int, y: Int, z: Int): Int? =
        chunk(x shr 4, z shr 4)?.biomeAt(x, y, z)

    /** 当前跟踪的实体数量。 */
    public val entityCount: Int get() = entities.size

    /** 按实体 id 取最新快照；未跟踪或会话已关闭时返回 null。 */
    public fun entity(entityId: Int): TrackedEntity? = entities[entityId]

    /**
     * 当前所有已跟踪实体的快照。
     *
     * 每次调用都会复制一份映射，成本与实体数成正比；高频渲染路径应当按 id 查询
     * （[entity]），或者自行在事件流上维护视图。
     *
     * 本端玩家不在其中：服务端不会为玩家自己发实体生成包，自己的位置见
     * [WorldSnapshot.position]。
     */
    public fun entities(): Map<Int, TrackedEntity> = HashMap(entities)

    /** 玩家列表中的条目数，含本端玩家自己。 */
    public val playerCount: Int get() = players.size

    /** 按玩家档案 UUID 取列表条目；不在列表里或会话已关闭时返回 null。 */
    public fun player(uuid: UUID): RemotePlayer? = players[uuid]

    /** 玩家列表的当前快照；每次调用都会复制一份映射。 */
    public fun players(): Map<UUID, RemotePlayer> = HashMap(players)

    /**
     * 实体 id 对应的玩家名；实体不是玩家、或玩家列表里还没有这个人时返回 null。
     *
     * 这是 [entity] 与 [player] 的关联：实体生成包只带 UUID，名字来自玩家列表，
     * 两者到达顺序不固定，因此这里按查询时刻关联，而不是在实体快照里存一份副本。
     */
    public fun nameOf(entityId: Int): String? = entity(entityId)?.uuid?.let { player(it)?.name }

    /** 最近的聊天记录，按到达顺序，最多 [ClientOptions.chatHistorySize] 条；每次调用复制一份。 */
    public fun chat(): List<ChatMessage> = synchronized(chatLock) { chatLog.toList() }

    /** 当前保留的聊天条数。 */
    public val chatCount: Int get() = synchronized(chatLock) { chatLog.size }

    /**
     * 玩家自己的背包（窗口 0）；出生前或会话关闭后为 null。
     *
     * 槽位编号见 [PlayerInventoryLayout]：9–35 主背包、36–44 快捷栏、45 副手。
     * 空槽是 null，物品只带协议 id 与数量，不带组件。
     */
    public fun inventory(): Container? = containers[Container.PLAYER_WINDOW_ID]

    /**
     * 当前打开的容器（箱子、工作台……）；没有打开或会话关闭后为 null。
     *
     * 同一时刻最多一个：服务端打开新容器时会先关掉旧的。
     */
    public fun container(): Container? =
        containers[activeWindowId]?.takeUnless { it.isPlayerInventory }

    /** 按窗口 id 取容器快照；窗口 0 与当前打开的容器都在其中。 */
    public fun container(windowId: Int): Container? = containers[windowId]

    /** 所有窗口的快照；每次调用都会复制一份映射。 */
    public fun containers(): Map<Int, Container> = HashMap(containers)

    /**
     * 当前活动的窗口 id：有打开的容器时是它，否则是玩家背包（0）。
     *
     * 服务端按递增顺序分配窗口 id，所以“最大的非 0 窗口”就是最后打开的那个；同一时刻
     * 只会有一个，用最大值只是为了在乱序到达时不至于随机挑一个。
     */
    public val activeWindowId: Int
        get() = containers.keys
            .filter { it != Container.PLAYER_WINDOW_ID }
            .maxOrNull()
            ?: Container.PLAYER_WINDOW_ID

    /** 服务端认为本端选中的快捷栏格（0–8）。 */
    public val heldSlot: Int get() = heldSlotIndex

    /**
     * 当前手持物品（快捷栏 [heldSlot] 格）；背包未知或该格为空时返回 null。
     *
     * 这是 [inventory] 与 [heldSlot] 的组合查询，不做缓存：两者都可能随时变化，
     * 存一份副本只会让人忘记它已经过期。
     */
    public fun heldItem(): InventoryItem? = inventory()?.slot(PlayerInventoryLayout.hotbar(heldSlot))

    /**
     * 光标上的物品（点着不放的那一堆）；没有时为 null。
     *
     * 读的是活动窗口（[activeWindowId]）：没开容器时是玩家背包，打开箱子时是箱子窗口——服务端只在
     * 当前菜单上维护光标，这里跟着它的视角走。点击之后光标由服务端确认：本库不预测点击结果，
     * 等 `set_cursor_item` 或整包内容到达。
     */
    public fun cursorItem(): InventoryItem? = containers[activeWindowId]?.cursor

    /**
     * 发送一条聊天消息。只有 [SessionState.PLAY] 阶段可用。
     *
     * 消息会被服务端广播回来（包括自己发的那条），因此成功发送后事件流里会出现同一条
     * [ClientEvent.ChatReceived]，[chat] 里也能回看到它。本端不做本地回显：服务端可能
     * 过滤、改写或拒收，提前插入会让界面显示一条服务端并不认可的消息。
     *
     * 非挂起函数：写入网络是异步的，返回不代表服务端已收到。无返回值也没有回执事件——
     * 真正的回执就是那条广播回来的消息。
     *
     * @throws IllegalArgumentException 内容为空、只有空白、含换行或超过 256 个字符
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun sendChat(message: String) {
        playConnection("聊天").sendChat(message)
    }

    /**
     * 发送一条命令，例如 `sendCommand("/help")`；前导斜杠可省略。
     *
     * 命令不走聊天签名，输出以系统消息回到 [chat]；能否执行取决于服务端给该玩家的权限。
     *
     * @throws IllegalArgumentException 命令为空、只有空白、含换行或超过 256 个字符
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun sendCommand(command: String) {
        playConnection("命令").sendCommand(command)
    }

    /**
     * 把本端移动到 [position]，并把该位置声明给服务端。只有 [SessionState.PLAY] 阶段可用。
     *
     * 只发送与上一状态不同的分量（位置与朝向都没变时一个字节也不发），因此可以按固定频率
     * 反复调用同一个位置而不产生流量。本库不做物理与预测：坐标就是调用方给的值。
     *
     * 服务端接受移动时**不回包**，所以 [world] 里的位置在调用后立即变成 [position] ——
     * 它表示“本端声明的坐标”，不是“服务端确认过的坐标”。服务端不同意时会下发位置同步，
     * 表现为 [ClientEvent.PositionUpdated] 并把快照改回来。
     *
     * 落地标记 [onGround] 由调用方判断：无头客户端不做碰撞检测，默认 `false`。
     *
     * @throws IllegalArgumentException 任一坐标为 NaN 或无穷
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun moveTo(position: Position, onGround: Boolean = false) {
        playConnection("移动").moveTo(position.x, position.y, position.z, position.yaw, position.pitch, onGround)
    }

    /**
     * 只改朝向，位置不变。只有 [SessionState.PLAY] 阶段可用。
     *
     * 协议里的朝向包不带坐标，因此这条路径不要求已经收到过位置同步；但它**不会**改变
     * 位置，想同时移动请用 [moveTo]。
     *
     * @throws IllegalArgumentException 任一角度为 NaN 或无穷
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun look(yaw: Float, pitch: Float, onGround: Boolean = false) {
        playConnection("转向").look(yaw, pitch, onGround)
    }

    /**
     * 切换快捷栏选中格（0–8）。只有 [SessionState.PLAY] 阶段可用。
     *
     * 与移动同理：服务端接受时不回包，因此 [heldSlot] 在调用后立即改变，并触发
     * [ClientEvent.HeldSlotChanged]；被拒绝或改写时，服务端的 `set_held_slot` 以同样的事件
     * 把值改回来。
     *
     * @throws IllegalArgumentException 下标不在 0..8 之间
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun selectHotbarSlot(slot: Int) {
        playConnection("切换快捷栏").selectHotbarSlot(slot)
    }

    /**
     * 点击当前活动窗口（有打开的容器时是它，否则是玩家背包）的一格。
     *
     * 等价于 [clickSlot] 用在 [activeWindowId] 上；窗口内容还没到时抛
     * `IllegalStateException`——没有内容状态号可用，发出去只会被服务端拒绝。
     *
     * @throws IllegalArgumentException 槽位为负，或玩家背包窗口的槽位超过 46
     * @throws IllegalStateException 会话尚未进入 play、已经关闭，或该窗口内容尚未到达
     */
    public fun clickSlot(slot: Int, click: SlotClick) {
        clickSlot(activeWindowId, slot, click)
    }

    /**
     * 点击指定窗口的一格：拿起、放下、快速转移、与快捷栏交换、丢弃。
     *
     * **结果不由本端预测**：调用后 [inventory] / [container] 仍是旧内容，等服务端下发
     * 权威内容后才变，并以 [ClientEvent.InventoryChanged] 通知。因此“点了还没生效”是正常现象，
     * 想确认结果就等事件，而不是重试点击。
     *
     * 打开着的容器窗口里，容器自己的格子在前，其后是背包段（3 行箱子时背包从 27 开始）；
     * 这些背包槽位在快照里可能并不存在（服务端只发容器自己的格子），但仍然可以点击。
     *
     * @throws IllegalArgumentException 槽位为负，或玩家背包窗口的槽位超过 46
     * @throws IllegalStateException 会话尚未进入 play、已经关闭，或该窗口内容尚未到达
     */
    public fun clickSlot(windowId: Int, slot: Int, click: SlotClick) {
        val container = containers[windowId] ?: throw IllegalStateException(
            "窗口 $windowId 的内容还没有到达，无法点击；等 ContainerOpened/InventoryChanged 之后再试",
        )
        playConnection("点击槽位").clickSlot(container, slot, click)
    }

    /**
     * 关闭一个已打开的窗口。窗口 0（玩家背包）会被忽略。
     *
     * 客户端主动关闭时服务端不回包，因此关闭在发出时就已生效：本端随即移除该窗口并投递
     * [ClientEvent.ContainerClosed]（事件经会话 Actor 异步到达，稍晚于调用返回）。服务端自己
     * 关窗（插件调用关闭）走的是同一个事件，所以只监听事件、不区分是谁发起的。
     *
     * 已经搬进背包的物品不受影响。
     *
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun closeContainer(windowId: Int) {
        playConnection("关闭窗口").closeContainer(windowId)
    }

    /**
     * 开始挖掘 [position] 处方块的 [face] 那一面。三段挖掘的第一段。
     *
     * 服务端对开始、取消、完成都会回确认（[ClientEvent.BlockAcknowledged]）；挖掘耗时为 0 的
     * 方块会在开始时就碎掉，表现为紧随其后的 [ClientEvent.BlocksChanged]。
     *
     * @throws IllegalArgumentException 坐标超出协议位宽
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun startDigging(position: BlockPosition, face: BlockFace = BlockFace.UP) {
        playConnection("挖掘方块").startDigging(position, face)
    }

    /** 放弃正在进行的挖掘。@throws 见 [startDigging]。 */
    public fun cancelDigging(position: BlockPosition, face: BlockFace = BlockFace.UP) {
        playConnection("挖掘方块").cancelDigging(position, face)
    }

    /**
     * 完成挖掘：方块在这一刻真正被打碎。
     *
     * 本库不计算挖掘进度（硬度、工具、状态效果都属于游戏规则）；提前发送会被严格的服务端
     * 忽略，宽松的服务端接受。需要动画时用 [startDigging] + 自己计时 + 本方法。
     *
     * @throws IllegalArgumentException 坐标超出协议位宽
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun finishDigging(position: BlockPosition, face: BlockFace = BlockFace.UP) {
        playConnection("挖掘方块").finishDigging(position, face)
    }

    /**
     * 一次性挖掉一块方块：开始 + 完成，不等挖掘进度。
     *
     * 结果由服务端下发：方块变更以 [ClientEvent.BlocksChanged] 到达并写入缓存，之后
     * [blockStateAt] 才返回新值。空手挖石头在生存模式下可能没有进度，严格的服务端会忽略
     * 这次完成——判断依据是确认事件与方块变更，而不是本方法的返回（它没有返回值）。
     *
     * @throws IllegalArgumentException 坐标超出协议位宽
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun digBlock(position: BlockPosition, face: BlockFace = BlockFace.UP) {
        playConnection("挖掘方块").digBlock(position, face)
    }

    /**
     * 对着某格方块使用物品：主手拿着方块时是放置（落点为 `position.offset(face)`），
     * 空手或拿着工具时是与方块交互（开箱子、按按钮……）。
     *
     * 与挖掘一样由服务端裁决：能放就下发方块变更，不能放只回确认。光标默认取方块中心，
     * 需要精确命中面的某一点时再传。
     *
     * @throws IllegalArgumentException 坐标超出协议位宽，或光标位置不在 0..1 之间
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun useOnBlock(
        position: BlockPosition,
        face: BlockFace = BlockFace.UP,
        cursorX: Float = 0.5f,
        cursorY: Float = 0.5f,
        cursorZ: Float = 0.5f,
        hand: Hand = Hand.MAIN_HAND,
        insideBlock: Boolean = false,
    ) {
        playConnection("使用物品").useOnBlock(position, face, cursorX, cursorY, cursorZ, hand, insideBlock)
    }

    /**
     * 对着空气使用手里的物品（吃、喝、投掷、拉弓）。朝向取本端当前视角。
     *
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun useItem(hand: Hand = Hand.MAIN_HAND) {
        playConnection("使用物品").useItem(hand)
    }

    /**
     * 最近一次被服务端确认的方块交互序号；还没有任何交互被确认时为 0。
     *
     * 这是“挖/放的动作处理完了”的回执，也是这一轮唯一的新机制：真实客户端用它做预测
     * 回滚，本库不做预测，只把它作为对账证据暴露出来。
     */
    public val lastAcknowledgedSequence: Int get() = lastAcknowledgedSequenceValue

    /**
     * 取可用于上行的连接。
     *
     * 用状态而不是连接是否存活来判断：连接存活但仍在 configuration 阶段时发送会被服务端
     * 当作协议错误断开，那是最难定位的一类失败。关闭与未进入 play 用同一类异常（都是
     * “现在不能发”），但消息里带上当前状态，便于区分是没连上还是已经结束。
     */
    private fun playConnection(what: String): ProtocolConnection {
        check(state.value == SessionState.PLAY) {
            "会话当前为 ${state.value}，只有进入 play 后才能发送$what"
        }
        return connection ?: throw IllegalStateException("会话还没有建立连接，不能发送$what")
    }

    /** 协议回调：网络线程上只允许缓存写入或一次入队，溢出即终止会话。 */
    private fun onProtocolEvent(event: ProtocolEvent) {
        when (event) {
            is ProtocolEvent.ChunkLoaded -> {
                if (!closing.get()) chunks[chunkKey(event.column.x, event.column.z)] = event.column
                return
            }

            is ProtocolEvent.ChunkUnloaded -> {
                if (!closing.get()) chunks.remove(chunkKey(event.x, event.z))
                return
            }

            // 方块变更与区块同属世界数据：先写缓存再进事件流，消费者收到事件时一定能查到新值。
            // 变更落在未加载的区块上不是错误——那条变更在本端没有落点，直接丢弃。
            is ProtocolEvent.BlocksChanged -> {
                if (!closing.get()) {
                    for (change in event.changes) {
                        val position = change.position
                        val key = chunkKey(position.x shr 4, position.z shr 4)
                        chunks.computeIfPresent(key) { _, column ->
                            column.withBlock(position.x, position.y, position.z, change.stateId) ?: column
                        }
                    }
                }
            }

            is ProtocolEvent.BlockChangeAcknowledged -> {
                if (!closing.get()) lastAcknowledgedSequenceValue = event.sequence
            }

            // 重生会换维度：旧维度的区块与实体全部作废。清理必须发生在网络线程上，
            // 否则新维度的区块可能先写入、再被 Actor 里迟到的清理抹掉。
            // 玩家背包不清（换维度不会丢东西），打开的容器随服务端关闭包一起消失。
            is ProtocolEvent.Respawned -> {
                if (!closing.get()) {
                    chunks.clear()
                    entities.clear()
                    containers.keys.removeIf { it != Container.PLAYER_WINDOW_ID }
                }
            }

            // 位置增量只更新缓存：它每秒可达上百条，且每条都带完整快照，投递出去只会
            // 把消费者的缓冲填满，却不带来任何新信息。
            is ProtocolEvent.EntityUpdated -> {
                if (!closing.get()) entities[event.entity.id] = event.entity
                return
            }

            // 增删是里程碑，走事件流；缓存先写，保证消费者收到事件时一定能查到。
            is ProtocolEvent.EntitySpawned -> {
                if (!closing.get()) entities[event.entity.id] = event.entity
            }

            is ProtocolEvent.EntityRemoved -> {
                if (!closing.get()) event.ids.forEach(entities::remove)
            }

            // 玩家列表的变化已经由适配层按包合并，一个包最多一个事件；缓存仍然先写。
            is ProtocolEvent.PlayerListChanged -> {
                if (!closing.get()) {
                    event.updated.forEach { players[it.uuid] = it }
                    event.removed.forEach(players::remove)
                }
            }

            // 窗口内容与玩家列表同理：先写缓存，消费者收到事件时一定查得到同一份快照。
            is ProtocolEvent.ContainerUpdated -> {
                if (!closing.get()) containers[event.container.windowId] = event.container
            }

            is ProtocolEvent.ContainerOpened -> {
                if (!closing.get()) containers[event.container.windowId] = event.container
            }

            is ProtocolEvent.ContainerClosed -> {
                if (!closing.get()) containers.remove(event.windowId)
            }

            is ProtocolEvent.HeldSlotChanged -> {
                if (!closing.get()) heldSlotIndex = event.slot
            }

            else -> Unit
        }

        if (inbox.trySend(event).isFailure) {
            if (closing.get()) return
            abortBecauseOfOverflow()
        }
    }

    /**
     * 事件溢出终止。
     *
     * 此处的终止不能交给 Actor：消费者卡住时 Actor 正挂在 `outbox.send` 上，
     * 既不会读到 inbox 已关闭，也发不出终止事件。因此由回调线程直接收敛状态并
     * 取消 Actor，未投递的事件只能丢弃——这正是溢出的定义，且已向调用方报告。
     */
    private fun abortBecauseOfOverflow() {
        if (!overflowing.compareAndSet(false, true)) return

        terminalError.compareAndSet(null, ClientException.EventOverflow(options.eventBufferCapacity))
        inbox.close()
        actor?.cancel()
        chunks.clear()
        entities.clear()
        players.clear()
        containers.clear()
        heldSlotIndex = 0
        lastAcknowledgedSequenceValue = 0
        mutableStatus.value = PlayerStatus()
        clearChat()
        mutableState.value = SessionState.CLOSED
        outbox.close()
        // 网络线程上不能做阻塞关闭；交回调度器。
        scope.launch { connection?.close() }
    }

    private suspend fun runActor() {
        while (true) {
            val event = inbox.receiveCatching().getOrNull() ?: break
            if (!handle(event)) break
        }
        finish()
    }

    /** @return 是否继续处理后续事件。 */
    private suspend fun handle(event: ProtocolEvent): Boolean = when (event) {
        ProtocolEvent.Connected -> true

        is ProtocolEvent.PhaseChanged -> {
            transition(event.phase.toSessionState())
            true
        }

        is ProtocolEvent.RegistryData -> {
            mutableRegistries.update { it + (event.registryId to event.entryCount) }
            emit(ClientEvent.RegistryReceived(event.registryId, event.entryCount))
            true
        }

        is ProtocolEvent.JoinedWorld -> {
            mutableWorld.value = WorldSnapshot(
                entityId = event.entityId,
                dimension = event.dimension,
                gameMode = event.gameMode,
            )
            emit(ClientEvent.JoinedWorld(event.entityId, event.dimension, event.gameMode))
            true
        }

        is ProtocolEvent.PositionUpdated -> {
            val position = Position(event.x, event.y, event.z, event.yaw, event.pitch)
            // 位置可能先于 Login 到达（协议没有强制顺序）；此时快照只有位置，其余字段留空。
            mutableWorld.update { it.orEmpty().copy(position = position) }
            emit(ClientEvent.PositionUpdated(position))
            true
        }

        is ProtocolEvent.PositionSent -> {
            // 服务端接受移动时不会回包，快照只能由本端声明推进；不发事件——这是调用方自己
            // 发起的动作，再回一个事件只是把同一件事说两遍。服务端纠正走 PositionUpdated。
            mutableWorld.update {
                it.orEmpty().copy(
                    position = Position(event.x, event.y, event.z, event.yaw, event.pitch),
                )
            }
            true
        }

        is ProtocolEvent.Respawned -> {
            // 旧维度的区块在 onProtocolEvent 里已清空；这里把快照里依赖维度的字段一起复位，
            // 位置要等新的同步包——保留旧坐标等于给调用方一个来自另一个世界的答案。
            // 生命与经验同样属于“上一个身体”，服务端会重新下发。
            mutableStatus.value = PlayerStatus()
            mutableWorld.update {
                it.orEmpty().copy(
                    dimension = event.dimension,
                    gameMode = event.gameMode,
                    position = null,
                    chunksLoaded = 0,
                )
            }
            emit(ClientEvent.Respawned(event.dimension, event.gameMode))
            true
        }

        is ProtocolEvent.ChunksReceived -> {
            mutableWorld.update { it.orEmpty().copy(chunksLoaded = event.total) }
            emit(ClientEvent.ChunksReceived(event.chunks, event.total))
            true
        }

        // 区块与卸载在 onProtocolEvent 里已写入缓存，不经过 Actor；列出来是为了让
        // 这里的穷尽分支跟上协议事件的增长——新增事件没有处理策略时应当编译失败。
        is ProtocolEvent.ChunkLoaded, is ProtocolEvent.ChunkUnloaded -> true

        is ProtocolEvent.BlocksChanged -> {
            emit(ClientEvent.BlocksChanged(event.changes))
            true
        }

        is ProtocolEvent.BlockChangeAcknowledged -> {
            emit(ClientEvent.BlockAcknowledged(event.sequence))
            true
        }

        is ProtocolEvent.EntitySpawned -> {
            emit(ClientEvent.EntitySpawned(event.entity))
            true
        }

        is ProtocolEvent.EntityRemoved -> {
            emit(ClientEvent.EntityRemoved(event.ids))
            true
        }

        // 位置增量已在 onProtocolEvent 里写入缓存，不进事件流。
        is ProtocolEvent.EntityUpdated -> true

        is ProtocolEvent.PlayerListChanged -> {
            emit(ClientEvent.PlayerListChanged(event.updated, event.removed))
            true
        }

        is ProtocolEvent.HealthUpdated -> {
            mutableStatus.update {
                it.copy(
                    health = event.health,
                    food = event.food,
                    foodSaturation = event.saturation,
                )
            }
            emit(ClientEvent.StatusChanged(mutableStatus.value))
            true
        }

        is ProtocolEvent.ExperienceUpdated -> {
            mutableStatus.update {
                it.copy(
                    experienceBar = event.bar,
                    level = event.level,
                    totalExperience = event.total,
                )
            }
            emit(ClientEvent.StatusChanged(mutableStatus.value))
            true
        }

        is ProtocolEvent.ChatReceived -> {
            appendChat(event.message)
            emit(ClientEvent.ChatReceived(event.message))
            true
        }

        is ProtocolEvent.ChatDeleted -> {
            removeChat(event.signatureId)
            emit(ClientEvent.ChatDeleted(event.signatureId))
            true
        }

        is ProtocolEvent.ContainerUpdated -> {
            emit(ClientEvent.InventoryChanged(event.container.windowId))
            true
        }

        is ProtocolEvent.ContainerOpened -> {
            emit(
                ClientEvent.ContainerOpened(
                    windowId = event.container.windowId,
                    type = event.container.type.orEmpty(),
                    title = event.container.title.orEmpty(),
                ),
            )
            true
        }

        is ProtocolEvent.ContainerClosed -> {
            emit(ClientEvent.ContainerClosed(event.windowId))
            true
        }

        is ProtocolEvent.HeldSlotChanged -> {
            emit(ClientEvent.HeldSlotChanged(event.slot))
            true
        }

        // 登录会话不会收到状态查询结果。
        is ProtocolEvent.StatusReceived -> true

        is ProtocolEvent.Closed -> {
            recordClose(event.reason)
            false
        }
    }

    private fun appendChat(message: ChatMessage) {
        synchronized(chatLock) {
            chatLog.addLast(message)
            while (chatLog.size > options.chatHistorySize) chatLog.removeFirst()
        }
    }

    /** 按编号删除一条消息；系统消息没有编号，不会被误删。 */
    private fun removeChat(signatureId: Int) {
        synchronized(chatLock) {
            val index = chatLog.indexOfLast { it.signatureId == signatureId }
            if (index >= 0) chatLog.removeAt(index)
        }
    }

    private fun clearChat() {
        synchronized(chatLock) { chatLog.clear() }
    }

    private suspend fun transition(next: SessionState) {
        val current = mutableState.value
        if (current == next || current == SessionState.CLOSED) return
        mutableState.value = next
        emit(ClientEvent.StateChanged(next))
    }

    private suspend fun emit(event: ClientEvent) {
        try {
            outbox.send(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClosedSendChannelException) {
            // 会话已终止，终止事件已由 finish() 或 close() 处理。
        }
    }

    private fun recordClose(reason: ProtocolCloseReason) {
        when (reason) {
            ProtocolCloseReason.Local -> Unit
            is ProtocolCloseReason.Server ->
                terminalError.compareAndSet(null, ClientException.ServerDisconnected(reason.message))
            is ProtocolCloseReason.Failure ->
                terminalError.compareAndSet(null, reason.toClientException())
        }
    }

    private suspend fun finish() {
        if (!finished.compareAndSet(false, true)) return
        chunks.clear()
        entities.clear()
        players.clear()
        containers.clear()
        heldSlotIndex = 0
        lastAcknowledgedSequenceValue = 0
        mutableStatus.value = PlayerStatus()
        clearChat()
        mutableState.value = SessionState.CLOSED
        try {
            // 服务端或协议先断开时也要回收连接资源；本方法幂等。
            connection?.close()
        } finally {
            val error = terminalError.get()
            try {
                outbox.send(if (error != null) ClientEvent.Failed(error) else ClientEvent.Closed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedSendChannelException) {
                // 已被 close() 抢先终止。
            } finally {
                outbox.close()
            }
        }
    }
}

private fun WorldSnapshot?.orEmpty(): WorldSnapshot = this ?: WorldSnapshot()

/** 区块坐标打包成一个 long 作为缓存键，避免每次查询都分配对象。 */
private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFF_FFFFL)

internal fun ProtocolPhase.toSessionState(): SessionState = when (this) {
    ProtocolPhase.LOGIN -> SessionState.LOGIN
    ProtocolPhase.CONFIGURATION -> SessionState.CONFIGURATION
    ProtocolPhase.PLAY -> SessionState.PLAY
}

internal fun ProtocolCloseReason.Failure.toClientException(): ClientException = when (kind) {
    ProtocolFailureKind.CONNECT -> ClientException.ConnectFailed(cause.message ?: "无法建立连接", cause)
    ProtocolFailureKind.NETWORK -> ClientException.NetworkFailure(cause)
    ProtocolFailureKind.PROTOCOL -> ClientException.ProtocolFailure(cause)
}
