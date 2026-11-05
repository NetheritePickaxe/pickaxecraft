package org.pickaxe.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import org.pickaxe.protocol.ProtocolCloseReason
import org.pickaxe.protocol.ProtocolConnection
import org.pickaxe.protocol.ProtocolEvent
import org.pickaxe.protocol.ProtocolFailureKind
import org.pickaxe.protocol.ProtocolListener
import org.pickaxe.protocol.ProtocolPhase
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.command.CommandSuggestions
import org.pickaxe.protocol.command.CommandTree
import org.pickaxe.protocol.entity.EntityAttribute
import org.pickaxe.protocol.entity.MobEffect
import org.pickaxe.protocol.entity.MovementInput
import org.pickaxe.protocol.entity.PlayerAbilities
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.Hand
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import org.pickaxe.protocol.inventory.SlotClick
import org.pickaxe.protocol.world.BlockChange
import org.pickaxe.protocol.world.BlockEntity
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition
import org.pickaxe.protocol.world.ChunkColumn
import org.pickaxe.protocol.world.HeightmapType
import org.pickaxe.protocol.world.RemotePlayer
import org.pickaxe.protocol.world.TrackedEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    private val mutableAbilities = MutableStateFlow(PlayerAbilities())

    /**
     * 服务端权威的本端能力（无敌、可飞、正在飞、创造模式与两个速度倍率）。
     *
     * 与 [movementInput] 的区别是**谁说的**：那个是本端声明的按键，这个是服务端认可的
     * 能力。`setFlying(true)` 之后只有这里的 `flying` 变成 true 才算真的起飞。
     */
    public val abilities: StateFlow<PlayerAbilities> = mutableAbilities.asStateFlow()

    private val mutableMovementInput = MutableStateFlow(MovementInput())

    /** 本端最后一次声明的运动按键；发送后本地立即推进，服务端不回包。 */
    public val movementInput: StateFlow<MovementInput> = mutableMovementInput.asStateFlow()

    private val mutableTransferRequest = MutableStateFlow<TransferRequest?>(null)

    /**
     * 服务端最近一次要求转到的目标；没收到过时为 null。
     *
     * **不随会话结束清空**：会话因转移而断开后，调用方正是靠它决定“接着连哪里”。
     * 一次连接可能收到多次，后一次覆盖前一次。
     */
    public val transferRequest: StateFlow<TransferRequest?> = mutableTransferRequest.asStateFlow()

    private val mutableCommands = MutableStateFlow<CommandTree?>(null)

    /**
     * 服务端下发的命令树；还没收到时为 null。
     *
     * 登录进入 play 后会下发一次，权限变化时可能整棵重发，因此这里是**替换**语义。
     * 补全请求不依赖它（候选由服务端算），它提供的是离线可读的结构：有哪些命令、
     * 各自要什么参数。会话结束或溢出终止时清空。
     */
    public val commands: StateFlow<CommandTree?> = mutableCommands.asStateFlow()

    /**
     * 未完成的补全请求：请求编号 → 等待中的结果。
     *
     * 网络线程在收到回答时直接完成对应的 deferred，不经过 Actor 的事件队列——
     * 否则消费者稍慢一点，`complete` 就会等到超时，哪怕答案早已到达。
     * 完成时用 null 表示“会话结束了，不会有答案”。
     */
    private val pendingSuggestions = ConcurrentHashMap<Int, CompletableDeferred<CommandSuggestions?>>()

    /** 补全请求编号；只需在一个连接内唯一，从 1 开始便于日志辨认。 */
    private val nextSuggestionId = AtomicInteger(1)

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
     * 载具 → 乘客列表，按键整份覆盖。
     *
     * 协议只有这一条按载具下发的完整列表，没有“某人上车/下车”的增量包，因此这里是覆盖语义。
     * 列表里的 id 可能包含还没被跟踪的实体（同一载具里的其他乘客不在本端视野时也会出现），
     * 所以它独立于 [entities] 存放，不要求两边都能对上。
     */
    private val passengers = ConcurrentHashMap<Int, List<Int>>()

    private val mutableVehicle = MutableStateFlow<Int?>(null)

    /**
     * 本端当前乘坐的载具实体 id；没在载具上时为 null。
     *
     * 判据是“自己的实体 id 出现在某个载具的乘客列表里”——协议没有上/下车包，
     * 空列表就是下车。骑乘时不要用 [moveTo] / [look]，改用 [steerVehicle]：
     * 原版客户端在载具上也根本不发玩家位置包。
     */
    public val vehicle: StateFlow<Int?> = mutableVehicle.asStateFlow()

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
     * 实体属性缓存：实体 id → 键名 → 属性。
     *
     * 服务端每次只下发改动的属性，这里按键合并；与区块/实体一样不占事件缓冲。
     */
    private val entityAttributes = ConcurrentHashMap<Int, Map<String, EntityAttribute>>()

    /**
     * 实体状态效果缓存：实体 id → 效果键名 → 效果。
     *
     * 协议每次只推一个效果的新增/替换或移除，因此按键合并；与区块/实体一样不占事件缓冲。
     */
    private val entityEffects = ConcurrentHashMap<Int, Map<String, MobEffect>>()

    /** 窗口数值属性缓存：窗口 id → 原始下标 → 值。 */
    private val containerPropertyValues = ConcurrentHashMap<Int, Map<Int, Int>>()

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
            passengers.clear()
            mutableVehicle.value = null
            players.clear()
            containers.clear()
            heldSlotIndex = 0
            lastAcknowledgedSequenceValue = 0
            entityAttributes.clear()
            mutableCommands.value = null
            releasePendingSuggestions()
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

    /**
     * 按世界坐标读取天空光等级（0–15）；区块未加载、或该层没有光照数据时返回 null。
     *
     * 全黑返回 0，与“没有数据”不同：前者是服务端明确算出来的结果，后者只是本端不知道。
     *
     * @throws IllegalArgumentException 区块已加载，但坐标超出该列的光照层范围
     */
    public fun skyLightAt(x: Int, y: Int, z: Int): Int? =
        chunk(x shr 4, z shr 4)?.skyLightAt(x, y, z)

    /** 按世界坐标读取方块光等级（0–15），语义与 [skyLightAt] 相同。 */
    public fun blockLightAt(x: Int, y: Int, z: Int): Int? =
        chunk(x shr 4, z shr 4)?.blockLightAt(x, y, z)

    /**
     * 读取某列的高度图，单位是世界 Y：该列第一格空气的 Y。
     *
     * 区块未加载、或服务端没有下发这张表时返回 null；哪些方块算“地表”取决于 [type]。
     */
    public fun heightAt(type: HeightmapType, x: Int, z: Int): Int? =
        chunk(x shr 4, z shr 4)?.heightAt(type, x, z)

    /** 按坐标取方块实体；区块未加载、或这一格没有实体时返回 null。 */
    public fun blockEntity(position: BlockPosition): BlockEntity? =
        chunk(position.x shr 4, position.z shr 4)?.blockEntity(position)

    /** [blockEntity] 的三坐标版本。 */
    public fun blockEntity(x: Int, y: Int, z: Int): BlockEntity? =
        chunk(x shr 4, z shr 4)?.blockEntity(x, y, z)

    /** 当前跟踪的实体数量。 */
    public val entityCount: Int get() = entities.size

    /** 按实体 id 取最新快照；未跟踪或会话已关闭时返回 null。 */
    public fun entity(entityId: Int): TrackedEntity? = entities[entityId]

    /**
     * 按实体 id 读生物生命值；非生物、还没收到元数据、或会话已关闭时返回 null。
     *
     * 等价于 `entity(id)?.health`，单独提供是为了让调用方不必先理解实体元数据层级。
     * 本端玩家自己的血量在 [status] 里。
     */
    public fun health(entityId: Int): Float? = entities[entityId]?.health

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

    /**
     * 某个载具当前的乘客实体 id 列表；不是载具或没收到过列表时返回空列表。
     *
     * 列表可能包含还没被跟踪的实体 id：协议只给 id，不保证本端已经收到它们的生成包。
     * 想拿乘客的坐标/类型，得先确认 [entity] 非空。
     */
    public fun passengers(vehicleId: Int): List<Int> = passengers[vehicleId] ?: emptyList()

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

    /**
     * 某实体的属性表快照（键名如 `minecraft:max_health` → 属性值与修改器）。
     *
     * 服务端每次只下发改动的属性，这里返回**按键合并后**的累积结果；从没收到过就是空表。
     * 本端玩家自己的属性也在其中（用 `Session.world` 里的实体 id 查询）。与区块、实体一样，
     * 属性写入走无锁缓存、不占事件缓冲。
     */
    public fun attributes(entityId: Int): Map<String, EntityAttribute> =
        entityAttributes[entityId] ?: emptyMap()

    /** 按键查单个属性；从没收到过就是 null。 */
    public fun attribute(entityId: Int, key: String): EntityAttribute? =
        entityAttributes[entityId]?.get(key)

    /** 本端玩家在服务端里的实体 id；还没进入世界时为 null。 */
    public val selfEntityId: Int? get() = mutableWorld.value?.entityId

    /**
     * 某实体当前的状态效果表（键名如 `minecraft:speed` → 效果）。
     *
     * 服务端每次只推一个效果的新增/替换或移除，这里返回按键合并后的累积结果。
     * 查本端自己用 [selfEntityId]。
     */
    public fun effects(entityId: Int): Map<String, MobEffect> =
        entityEffects[entityId] ?: emptyMap()

    /** 本端玩家身上的状态效果；还没进入世界时是空表。 */
    public fun ownEffects(): Map<String, MobEffect> = selfEntityId?.let(::effects) ?: emptyMap()

    /** 按键查单个状态效果；从没收到过就是 null。 */
    public fun effect(entityId: Int, key: String): MobEffect? = entityEffects[entityId]?.get(key)

    /**
     * 某窗口当前的数值属性（原始下标 → 值）。
     *
     * 下标含义取决于窗口类型：熔炉的 0/1/2/3 分别是燃烧剩余、燃烧总量、熔炼进度与总时长，
     * 酿造台则完全不同。本库不内置翻译表，拿到窗口类型后由调用方解释。
     */
    public fun containerProperties(windowId: Int): Map<Int, Int> =
        containerPropertyValues[windowId] ?: emptyMap()

    /**
     * 整份声明运动按键（前进/后退/左右/跳跃/潜行/冲刺）。
     *
     * `player_input` 是状态包：服务端按最后一次收到的字节为准，本方法每次重发全部七位。
     * 成功没有回执，发送后 [movementInput] 立即推进。
     *
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun setMovementInput(input: MovementInput) {
        playConnection("移动输入").setMovementInput(input)
        mutableMovementInput.value = input
    }

    /** 切换潜行（shift 位）。 */
    public fun setSneaking(enabled: Boolean) {
        playConnection("潜行").setSneaking(enabled)
        mutableMovementInput.value = mutableMovementInput.value.copy(sneaking = enabled)
    }

    /**
     * 切换冲刺：同时声明 `player_input` 的 sprint 位与独立的 `player_command`。
     *
     * 开启时会清掉潜行位（原版客户端两者互斥）；关闭时保留当前潜行状态。
     */
    public fun setSprinting(enabled: Boolean) {
        playConnection("冲刺").setSprinting(enabled)
        val current = mutableMovementInput.value
        mutableMovementInput.value = current.copy(
            sprinting = enabled,
            sneaking = if (enabled) false else current.sneaking,
        )
    }

    /**
     * 请求开始/停止飞行。
     *
     * 只是请求，服务端可能拒绝：真正的结果看 [abilities] 与
     * [ClientEvent.AbilitiesChanged]。本机不做本地预测，因此 [movementInput] 不含飞行位。
     */
    public fun setFlying(enabled: Boolean) {
        playConnection("飞行").setFlying(enabled)
    }

    /**
     * 攻击一个实体。
     *
     * 攻击没有回执包（「成功没有回执」的第四例）：目标不在视野里时服务端静默忽略，
     * 打中了会以 [ClientEvent.EntityDamaged] 与实体状态变化体现。
     *
     * @param swing 是否跟随一次挥手动画（原版客户端攻击时总是挥手）
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun attack(entityId: Int, swing: Boolean = true) {
        playConnection("攻击实体").attack(entityId, swing)
    }

    /** 挥动一只手。只是视觉表现，与攻击判定无关。 */
    public fun swing(hand: Hand = Hand.MAIN_HAND) {
        playConnection("挥手").swing(hand)
    }

    /**
     * 与实体交互（右键）。
     *
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun interactWith(entityId: Int, hand: Hand = Hand.MAIN_HAND, sneaking: Boolean = false) {
        playConnection("与实体交互").interactWith(entityId, hand, sneaking)
    }

    /**
     * 在实体表面的指定点交互（实体坐标系里的相对位置）。
     *
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public fun interactAt(
        entityId: Int,
        x: Double,
        y: Double,
        z: Double,
        hand: Hand = Hand.MAIN_HAND,
        sneaking: Boolean = false,
    ) {
        playConnection("与实体交互").interactAt(entityId, x, y, z, hand, sneaking)
    }

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
     * 请求服务端补全 [text] 对应的命令候选，最多等 [timeoutMillis] 毫秒。
     *
     * [text] 必须是**完整输入行**（含前导斜杠，例如 `/gamemode cr`）：服务端按它自己
     * 解析出的位置决定替换哪一段，只发末尾几个字符会让区间算错。结果里的
     * [org.pickaxe.protocol.command.CommandSuggestions.applyTo] 可以把第一个候选套回原文。
     *
     * 这是上行里唯一**有回执**的请求：本端分配编号、服务端原样带回，因此并发调用互不串答案。
     * 没有回执时（服务端算不出候选，Minestom 就不回包）表现为 [CompletionResult.NoAnswer]，
     * 不是空列表——两者语义不同，见 [CompletionResult]。
     *
     * 挂起只发生在等待回答期间；不占用会话的事件队列，因此可以与 [events] 的消费并行调用
     * （在 `events.collect` 里直接调用也是安全的）。
     *
     * @throws IllegalArgumentException [timeoutMillis] 不是正数
     * @throws IllegalStateException 会话尚未进入 play，或已经关闭
     */
    public suspend fun complete(
        text: String,
        timeoutMillis: Long = DEFAULT_COMPLETION_TIMEOUT_MILLIS,
    ): CompletionResult {
        require(timeoutMillis > 0) { "补全超时必须大于 0，当前为 $timeoutMillis" }
        val connection = playConnection("命令补全")
        val transactionId = nextSuggestionId.getAndIncrement()
        val pending = CompletableDeferred<CommandSuggestions?>()
        pendingSuggestions[transactionId] = pending
        return try {
            connection.requestCommandSuggestions(transactionId, text)
            when (val answer = withTimeoutOrNull(timeoutMillis) { pending.await() }) {
                null -> CompletionResult.NoAnswer
                else -> CompletionResult.Suggestions(answer)
            }
        } finally {
            pendingSuggestions.remove(transactionId)
        }
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
     * **骑乘时不能用**：原版客户端在载具上根本不发玩家位置包，服务端也只按载具移动包改
     * 载具坐标。这里直接拒绝而不是发出去被忽略——静默失效会让调用方以为自己在动。
     * 骑乘时请用 [steerVehicle]。
     *
     * @throws IllegalArgumentException 任一坐标为 NaN 或无穷
     * @throws IllegalStateException 会话尚未进入 play、已经关闭，或正在骑乘
     */
    public fun moveTo(position: Position, onGround: Boolean = false) {
        checkOnFoot("移动")
        playConnection("移动").moveTo(position.x, position.y, position.z, position.yaw, position.pitch, onGround)
    }

    /**
     * 只改朝向，位置不变。只有 [SessionState.PLAY] 阶段可用。
     *
     * 协议里的朝向包不带坐标，因此这条路径不要求已经收到过位置同步；但它**不会**改变
     * 位置，想同时移动请用 [moveTo]。
     *
     * 骑乘时同样不能用：载具的朝向只能随 [steerVehicle] 一起声明。
     *
     * @throws IllegalArgumentException 任一角度为 NaN 或无穷
     * @throws IllegalStateException 会话尚未进入 play、已经关闭，或正在骑乘
     */
    public fun look(yaw: Float, pitch: Float, onGround: Boolean = false) {
        checkOnFoot("转向")
        playConnection("转向").look(yaw, pitch, onGround)
    }

    /**
     * 声明载具移动到了 [position]，并把该位置与朝向发给服务端。
     *
     * 骑乘时这是唯一合法的“我在动”表达方式：`move_vehicle` 是绝对坐标（不像实体移动包
     * 是相对量），包不带实体 id，只对当前载具成立。服务端接受时不回包，权威纠正走
     * `move_vehicle`（下行），表现为 [ClientEvent.PassengersChanged] 之外无事件、
     * 但 [entity] 里载具快照的坐标会被改写。
     *
     * 不在载具上时发送是非法的：这不是“会被忽略”，而是会让服务端把一个不在载具上的玩家
     * 当成载具来移动。因此这里直接失败。
     *
     * @throws IllegalArgumentException 坐标或角度非法
     * @throws IllegalStateException 会话尚未进入 play、已经关闭，或当前不在载具上
     */
    public fun steerVehicle(position: Position, onGround: Boolean = false) {
        playConnection("载具移动")
            .steerVehicle(position.x, position.y, position.z, position.yaw, position.pitch, onGround)
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
    /**
     * 骑乘时不允许走玩家位置/朝向通道。
     *
     * 判据用 [vehicle] 而不是适配层的映射：调用方看到的是同一个 [ClientEvent.VehicleChanged]，
     * 两边的一致性由那条事件保证；适配层另有自己的检查，防止绕过本类直接发。
     */
    private fun checkOnFoot(what: String) {
        val riding = mutableVehicle.value ?: return
        throw IllegalStateException("正在乘坐载具 $riding，不能发送$what；骑乘时请用 steerVehicle")
    }

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

            // 光照与方块实体同样先写缓存再进事件流：消费者收到事件时一定能查到新值。
            // 落点不存在（区块没加载）时丢弃——服务端重发整列时会带上完整数据。
            // 与区块/实体不同，这两类事件频率低且有下游意义，因此仍走事件流。
            is ProtocolEvent.LightUpdated -> {
                if (!closing.get()) {
                    chunks.computeIfPresent(chunkKey(event.x, event.z)) { _, column ->
                        column.withLight(event.patch)
                    }
                }
            }

            is ProtocolEvent.BlockEntityUpdated -> {
                if (!closing.get()) {
                    val position = event.entity.position
                    chunks.computeIfPresent(chunkKey(position.x shr 4, position.z shr 4)) { _, column ->
                        column.withBlockEntity(event.entity)
                    }
                }
            }

            // 重生会换维度：旧维度的区块与实体全部作废。清理必须发生在网络线程上，
            // 否则新维度的区块可能先写入、再被 Actor 里迟到的清理抹掉。
            // 玩家背包不清（换维度不会丢东西），打开的容器随服务端关闭包一起消失。
            is ProtocolEvent.Respawned -> {
                if (!closing.get()) {
                    chunks.clear()
                    entities.clear()
                    // 乘客关系跟着旧维度的实体一起作废：新维度里的载具是新 id，
                    // 留着旧列表会让 vehicle 指向一个已经不存在的实体。
                    passengers.clear()
                    mutableVehicle.value = null
                    containers.keys.removeIf { it != Container.PLAYER_WINDOW_ID }
                    entityAttributes.clear()
                    entityEffects.clear()
                    containerPropertyValues.clear()
                    mutableAbilities.value = PlayerAbilities()
                    mutableMovementInput.value = MovementInput()
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
                if (!closing.get()) {
                    event.ids.forEach(entities::remove)
                    event.ids.forEach(entityAttributes::remove)
                    event.ids.forEach(entityEffects::remove)
                    // 载具被移除时服务端不保证补一条空乘客列表，缓存不能留着它。
                    event.ids.forEach(passengers::remove)
                }
            }

            // 乘客列表是整份替换，直接覆盖缓存；与其它世界数据一样先写缓存再进事件流，
            // 消费者收到 PassengersChanged 时 passengers() 已经是新值。
            is ProtocolEvent.PassengersChanged -> {
                if (!closing.get()) {
                    if (event.passengerIds.isEmpty()) {
                        passengers.remove(event.vehicleId)
                    } else {
                        passengers[event.vehicleId] = event.passengerIds
                    }
                }
            }

            // 属性按键合并：服务端每次只发改动的项，整表替换会抹掉没动的键。
            is ProtocolEvent.AttributesUpdated -> {
                if (!closing.get()) {
                    val table = HashMap(entityAttributes[event.entityId] ?: emptyMap())
                    for (attribute in event.changed) table[attribute.key] = attribute
                    entityAttributes[event.entityId] = table
                }
            }

            // 状态效果按键合并：新增/替换与移除都在同一个事件里表达。
            is ProtocolEvent.EffectsUpdated -> {
                if (!closing.get()) {
                    val table = HashMap(entityEffects[event.entityId] ?: emptyMap())
                    for (effect in event.changed) table[effect.key] = effect
                    event.removed.forEach(table::remove)
                    entityEffects[event.entityId] = table
                }
            }

            // 能力是服务端权威状态：网络线程直接写入 StateFlow，事件流里再报告一次。
            is ProtocolEvent.AbilitiesUpdated -> {
                if (!closing.get()) mutableAbilities.value = event.abilities
            }

            is ProtocolEvent.ContainerPropertyChanged -> {
                if (!closing.get()) {
                    val table = HashMap(containerPropertyValues[event.windowId] ?: emptyMap())
                    table[event.property] = event.value
                    containerPropertyValues[event.windowId] = table
                }
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
                if (!closing.get()) {
                    containers.remove(event.windowId)
                    containerPropertyValues.remove(event.windowId)
                }
            }

            is ProtocolEvent.HeldSlotChanged -> {
                if (!closing.get()) heldSlotIndex = event.slot
            }

            // 命令树是会话级快照（权限变化时整棵重发），先写 StateFlow 再上报事件。
            is ProtocolEvent.CommandTreeReceived -> {
                if (!closing.get()) mutableCommands.value = event.tree
            }

            // 转移请求是行动信号而不是可缓存状态：先写 StateFlow 让晚到的订阅者也能读到，
            // 再照常排队上报事件。它不清空——会话因转移断开后，调用方正是靠它决定连哪里。
            is ProtocolEvent.TransferRequested -> {
                if (!closing.get()) {
                    mutableTransferRequest.value = TransferRequest(event.host, event.port)
                }
            }

            // 补全回答是**一对一的请求/响应**，不是可缓存状态：在网络线程上直接交给等待方，
            // 不经过 Actor 的事件队列——消费者慢一点也照样能拿到答案。事件仍然照常排队上报。
            is ProtocolEvent.CommandSuggestionsReceived -> {
                if (!closing.get()) {
                    pendingSuggestions.remove(event.suggestions.transactionId)
                        ?.complete(event.suggestions)
                }
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
        passengers.clear()
        mutableVehicle.value = null
        players.clear()
        containers.clear()
        heldSlotIndex = 0
        lastAcknowledgedSequenceValue = 0
        entityAttributes.clear()
        entityEffects.clear()
        containerPropertyValues.clear()
        mutableCommands.value = null
        releasePendingSuggestions()
        mutableStatus.value = PlayerStatus()
        mutableAbilities.value = PlayerAbilities()
        mutableMovementInput.value = MovementInput()
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

        // 这两条在 onProtocolEvent 里已合并进区块缓存；区块不在缓存里说明更新已丢弃，
        // 此时也不该通知消费者“某列变了”。返回值是“继续处理后续事件”，不是“是否已应用”。
        is ProtocolEvent.LightUpdated -> {
            if (chunks.containsKey(chunkKey(event.x, event.z))) {
                emit(ClientEvent.ChunkLightUpdated(event.x, event.z))
            }
            true
        }

        is ProtocolEvent.BlockEntityUpdated -> {
            val position = event.entity.position
            if (chunks.containsKey(chunkKey(position.x shr 4, position.z shr 4))) {
                emit(ClientEvent.BlockEntityChanged(event.entity))
            }
            true
        }

        is ProtocolEvent.EntitySpawned -> {
            emit(ClientEvent.EntitySpawned(event.entity))
            true
        }

        is ProtocolEvent.EntityRemoved -> {
            val ridden = mutableVehicle.value
            emit(ClientEvent.EntityRemoved(event.ids))
            // 载具被移除（死亡、离开视野）等同于下车：没有这条，vehicle 会一直指着一个
            // 已经不存在的实体，而服务端未必补发空乘客列表。
            if (ridden != null && ridden in event.ids) {
                mutableVehicle.value = null
                emit(ClientEvent.VehicleChanged(null))
            }
            true
        }

        is ProtocolEvent.AttributesUpdated -> {
            emit(ClientEvent.EntityAttributesUpdated(event.entityId, event.changed))
            true
        }

        is ProtocolEvent.EffectsUpdated -> {
            emit(
                ClientEvent.EntityEffectsChanged(
                    entityId = event.entityId,
                    changed = event.changed,
                    removed = event.removed,
                ),
            )
            true
        }

        is ProtocolEvent.AbilitiesUpdated -> {
            emit(ClientEvent.AbilitiesChanged(event.abilities))
            true
        }

        is ProtocolEvent.ContainerPropertyChanged -> {
            emit(
                ClientEvent.ContainerPropertyChanged(
                    windowId = event.windowId,
                    property = event.property,
                    value = event.value,
                ),
            )
            true
        }

        // 命令树与补全回答都已经在 onProtocolEvent 里写过缓存/交付过等待方，这里只上报事实。
        is ProtocolEvent.CommandTreeReceived -> {
            emit(ClientEvent.CommandTreeReceived(event.tree))
            true
        }

        is ProtocolEvent.CommandSuggestionsReceived -> {
            emit(ClientEvent.CommandSuggestionsReceived(event.suggestions))
            true
        }

        is ProtocolEvent.TransferRequested -> {
            emit(ClientEvent.TransferRequested(event.host, event.port))
            true
        }

        is ProtocolEvent.Damaged -> {
            emit(
                ClientEvent.EntityDamaged(
                    entityId = event.entityId,
                    sourceType = event.sourceType,
                    sourceCause = event.sourceCause,
                    sourceDirect = event.sourceDirect,
                ),
            )
            true
        }

        is ProtocolEvent.EntityHealthChanged -> {
            emit(ClientEvent.EntityHealthChanged(event.entityId, event.health))
            true
        }

        // 位置增量已在 onProtocolEvent 里写入缓存，不进事件流。
        is ProtocolEvent.EntityUpdated -> true

        // 骑乘时服务端用 `move_vehicle` 表达“你在哪”。它每秒都会来，和实体移动一样
        // 只推进快照、不进事件流；载具实体自己的坐标由适配层的 EntityUpdated 更新。
        // 只取位置，朝向仍用本端的：乘客可以转头看别处，那不是载具的朝向。
        is ProtocolEvent.VehicleMoved -> {
            mutableWorld.update {
                val current = it ?: return@update it
                val position = current.position ?: return@update current
                current.copy(position = position.copy(x = event.x, y = event.y, z = event.z))
            }
            true
        }

        // 乘客列表已在 onProtocolEvent 里写入缓存。这里只做「我在哪个载具上」的推导：
        // 协议没有上/下车包，判据只能是「自己的实体 id 出现在某条乘客列表里」。
        is ProtocolEvent.PassengersChanged -> {
            val self = mutableWorld.value?.entityId
            val current = mutableVehicle.value
            val next = when {
                self != null && self in event.passengerIds -> event.vehicleId
                // 载具发来空列表（或把自己排除掉的列表）就是下车；别的载具的列表不动我自己。
                current == event.vehicleId -> null
                else -> current
            }
            emit(ClientEvent.PassengersChanged(event.vehicleId, event.passengerIds))
            if (next != current) {
                mutableVehicle.value = next
                emit(ClientEvent.VehicleChanged(next))
            }
            true
        }

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

    /**
     * 结束所有等待中的补全请求。
     *
     * 用 null 完成而不是取消：调用方拿到 [CompletionResult.NoAnswer] 就能正常收尾，
     * 取消会让 `await` 抛 CancellationException，看起来像调用方自己被取消了。
     */
    private fun releasePendingSuggestions() {
        pendingSuggestions.values.forEach { it.complete(null) }
        pendingSuggestions.clear()
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
        passengers.clear()
        mutableVehicle.value = null
        players.clear()
        containers.clear()
        heldSlotIndex = 0
        lastAcknowledgedSequenceValue = 0
        entityAttributes.clear()
        entityEffects.clear()
        containerPropertyValues.clear()
        mutableCommands.value = null
        releasePendingSuggestions()
        mutableStatus.value = PlayerStatus()
        mutableAbilities.value = PlayerAbilities()
        mutableMovementInput.value = MovementInput()
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
