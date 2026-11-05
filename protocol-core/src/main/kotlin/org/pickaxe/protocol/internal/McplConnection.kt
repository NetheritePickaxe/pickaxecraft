package org.pickaxe.protocol.internal

import io.netty.channel.EventLoopGroup
import io.netty.handler.timeout.TimeoutException
import io.netty.util.concurrent.DefaultThreadFactory
import net.kyori.adventure.text.Component
import org.geysermc.mcprotocollib.network.BuiltinFlags
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent
import org.geysermc.mcprotocollib.network.event.session.SessionEvent
import org.geysermc.mcprotocollib.network.helper.TransportHelper
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession
import org.geysermc.mcprotocollib.protocol.MinecraftConstants
import org.cloudburstmc.math.vector.Vector3d
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.level.HeightmapTypes
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo
import org.geysermc.mcprotocollib.protocol.data.status.handler.ServerInfoHandler
import org.geysermc.mcprotocollib.protocol.data.status.handler.ServerPingTimeHandler
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundTransferPacket
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDeleteChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoRemovePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveMobEffectPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveVehiclePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRotateHeadPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEquipmentPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetPassengersPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerAbilitiesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetExperiencePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundBlockChangedAckPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLightUpdatePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerAbilitiesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket
import org.pickaxe.protocol.ProtocolCloseReason
import org.pickaxe.protocol.ProtocolEvent
import org.pickaxe.protocol.ProtocolFailureKind
import org.pickaxe.protocol.ProtocolListener
import org.pickaxe.protocol.ProtocolPhase
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.ServerStatus
import org.pickaxe.protocol.chat.ChatFormat
import org.pickaxe.protocol.entity.AttributeTable
import org.pickaxe.protocol.entity.EffectKeys
import org.pickaxe.protocol.entity.EntityAttribute
import org.pickaxe.protocol.entity.EntityAttributeModifier
import org.pickaxe.protocol.entity.LivingEntityTypes
import org.pickaxe.protocol.entity.MobEffect
import org.pickaxe.protocol.entity.MovementInput
import org.pickaxe.protocol.entity.PlayerAbilities
import org.pickaxe.protocol.chat.ChatKind
import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.Hand
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.inventory.SlotClick
import org.pickaxe.protocol.world.BlockChange
import org.pickaxe.protocol.world.BlockEntity
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition
import org.pickaxe.protocol.world.ChunkColumn
import org.pickaxe.protocol.world.ChunkLight
import org.pickaxe.protocol.world.HeightmapType
import org.pickaxe.protocol.world.LightPatch
import org.pickaxe.protocol.world.RemotePlayer
import org.pickaxe.protocol.world.TrackedEntity
import java.io.IOException
import java.net.InetSocketAddress
import java.util.BitSet
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 协议库会话的受控封装。
 *
 * 与直接使用 `ClientNetworkSession` 的差别只有三点，但三点都必须由本项目承担：
 *
 * 1. 网络资源归本对象：协议库默认把 `EventLoopGroup` 放在静态字段里并挂到 JVM
 *    关闭钩子，多会话共享；这里改为每个连接自持一个单线程组，关闭时回收。
 * 2. 观察点在协议库监听器**之后**：父类的包回调先交给库自带的 `ClientListener`
 *    完成状态切换与 KeepAlive 应答，再回到本对象转换语义事件，因此读到的阶段
 *    是处理后的结果，也不会重复应答 KeepAlive。
 * 3. 关闭语义收敛：无论谁先断开，向上只报告一次 [ProtocolEvent.Closed]。
 *
 * 协议要求本端回应的东西（客户端设置、传送确认、区块批次确认、player_loaded）
 * 都由本类就地处理：这些是“连接可用”的前提，不该让每个调用方各写一遍。
 *
 * 区块内容也在这里解码：协议库为了避免绑定客户端世界模型，只把 `chunkData` 原样交出，
 * section 结构与调色板由 [ChunkDecoder] 补齐。上层拿到的是不可变的区块列，
 * 看不到 `ByteBuf`，也不需要知道维度高度是怎么来的。
 *
 * 所有回调都发生在该连接自己的网络线程上（`pickaxe-mcpl` 线程组）。
 */
internal class McplConnection(
    target: ServerAddress,
    protocol: MinecraftProtocol,
    private val listener: ProtocolListener,
    private val statusQuery: Boolean,
    private val connectTimeoutSeconds: Int,
    private val readTimeoutSeconds: Int,
) : ClientNetworkSession(
    InetSocketAddress.createUnresolved(target.host, target.port),
    protocol,
    // 包处理就地执行：协议库默认会再开一个共享的包处理线程池，那会让回调出现在
    // 两个线程上，并把进程级资源重新引回来。适配层的转换只需要一次入队。
    Executor { command -> command.run() },
    null,
    null,
) {

    private val started = AtomicBoolean(false)
    private val shutdownStarted = AtomicBoolean(false)
    private val closedReported = AtomicBoolean(false)
    private val localClose = AtomicBoolean(false)
    private val reachedServer = AtomicBoolean(false)

    @Volatile
    private var loopGroup: EventLoopGroup? = null

    @Volatile
    private var lastPhase: ProtocolPhase? = null

    @Volatile
    private var statusInfo: ServerStatusInfo? = null

    /**
     * 本端声明的位置与朝向。
     *
     * 两个线程都会写：网络线程在服务端同步/传送时覆盖，调用线程在 [moveTo] / [look] 时覆盖。
     * 因此它必须是整体替换的不可变值，而不是一组各自更新的字段——否则“只发变了的那部分”
     * 会在两端交错时算出错的增量基准。最后写入者胜出，与真实客户端遇到延迟时的行为一致。
     */
    private val pose = AtomicReference(LocalPose(0.0, 0.0, 0.0, 0f, 0f, false))

    /**
     * 方块交互的序号，从 1 开始递增。
     *
     * 协议要求 `player_action`、`use_item_on`、`use_item` 各带一个客户端自增的序号，服务端在
     * `block_changed_ack` 里把它原样回送。本库不做方块预测，序号因此只承担**回执**职责，
     * 但它仍然必须单调递增且不重复：服务端用序号判断“这个动作是不是已经过期”，
     * 重复或倒退的序号会让后续方块更新被当成过期预测丢掉。
     *
     * 用原子量而不是网络线程本地变量：交互由调用线程发起，而发包本身会切到网络线程。
     */
    private val blockSequence = AtomicInteger(0)

    // 以下状态只在连接自己的网络线程上读写，无需同步。
    private var chunkTotal: Int = 0
    private var playerLoadedSent: Boolean = false

    /**
     * 已跟踪的实体，只在网络线程上读写。
     *
     * 之所以在适配层留一份：移动包大多是**相对量**，只有叠加到上一状态才有意义，
     * 而“上一状态”正是本层累积出来的事实。上层拿到的是叠加后的完整快照，
     * 不需要自己重放增量。
     */
    private val entities = HashMap<Int, TrackedEntity>()

    /**
     * 玩家列表，只在网络线程上读写。
     *
     * 服务端把同一条目拆成多次更新：ADD_PLAYER 给档案，UPDATE_LATENCY 给延迟，
     * UPDATE_GAME_MODE 给模式。这里按 UUID 合并成完整快照，向上只报合并后的结果；
     * 没有这一步，上层每次都要自己判断“这个包带了哪些字段”。
     */
    private val players = LinkedHashMap<UUID, RemotePlayer>()

    /**
     * 窗口内容，只在网络线程上读写。
     *
     * 窗口 0 是玩家背包，服务端在出生时就会下发整包内容；其余窗口由打开屏幕包创建、
     * 关闭包移除。保留可变状态是因为服务端大量使用单槽更新（`container_set_slot`），
     * 每次都要在上一份内容上改一格，向上只交不可变快照。
     */
    private val containers = HashMap<Int, MutableContainer>()

    /**
     * 实体属性，按实体 id 累积，只在网络线程上读写。
     *
     * `update_attributes` 每次只带**被更新**的那些属性（出生时全量，之后只发改动的项），
     * 因此必须按键合并：整表替换会把没动的属性抹掉。
     */
    private val entityAttributes = HashMap<Int, HashMap<String, EntityAttribute>>()

    /**
     * 本端的实体 id，Login 包下发。`player_command` 必须带上它，服务端据此确认
     * “这次状态切换属于哪个玩家实体”。-1 表示还没进入世界。
     *
     * 由网络线程写、调用线程读，因此需要可见性保证。
     */
    @Volatile
    private var selfEntityId: Int = -1

    /**
     * 本端正在乘坐的载具实体 id；null 表示没在载具上。
     *
     * 这份映射只存在于适配层：下行的 `move_vehicle` **不带实体 id**，它一出现
     * 就只可能指本端当前的载具。没有它，那个包里的坐标无处落地。
     *
     * 由网络线程写、调用线程读，因此需要可见性保证。
     */
    @Volatile
    private var selfVehicleId: Int? = null

    /**
     * 本端声明的运动按键，整体替换。
     *
     * `player_input` 是状态包：服务端按最后一次收到的字节为准，因此每次改动都要
     * 把这七个布尔位整份重发，不能只发“变化的那一位”。
     */
    private val movementInput = AtomicReference(MovementInput())

    /**
     * 实体状态效果，按「实体 id → 效果键名」累积。
     *
     * 协议每次只推一个效果的新增/替换或移除；并发容器是因为查询方法可能从调用线程进入。
     */
    private val effects = ConcurrentHashMap<Int, ConcurrentHashMap<String, MobEffect>>()

    /**
     * 窗口数值属性，按「窗口 id → 属性下标」累积。
     *
     * 下标含义取决于容器类型（熔炉的 2 是熔炼进度，酿造台的 0 是酿造时间），
     * 本层不解释，只保留原始编号与值。
     */
    private val containerProperties = ConcurrentHashMap<Int, ConcurrentHashMap<Int, Int>>()

    /**
     * 当前选中的快捷栏格。
     *
     * 与姿态同理：服务端**接受**切换时不回包（它只在拒绝或改写时下发 `set_held_slot`），
     * 所以发送后立即推进本地值，服务端纠正时再覆盖。
     */
    @Volatile
    private var heldSlot: Int = 0

    /** 配置阶段收到的维度类型表；下标即 Login 包里的维度 id。 */
    private var dimensions: List<DimensionShape> = emptyList()

    /**
     * 配置阶段收到的聊天格式表；下标即消息包里的格式 id。
     *
     * 缺省为空表示“没有格式可查”，此时聊天照常上报，只是没有
     * [ChatFormat]——排版由下游按朴素形式兜底。
     */
    private var chatTypes: List<ChatFormat> = emptyList()

    /**
     * 配置阶段收到的附魔注册表；下标即物品组件里的附魔 wire id。
     *
     * 附魔是动态注册表，顺序由服务端决定（不是版本常量），所以键名只能从这张表还原。
     * 缺省为空表示注册表还没到或服务端没下发，此时附魔降级为 `unknown:<id>` 而不是丢弃。
     */
    private var enchantmentKeys: List<String> = emptyList()

    /** 当前所在维度，决定区块的 section 数量与最低 Y。 */
    private var currentDimension: DimensionShape? = null

    val isShutDown: Boolean get() = shutdownStarted.get()

    fun start() {
        check(started.compareAndSet(false, true)) { "连接已经启动过" }
        check(!shutdownStarted.get()) { "连接已经关闭，不能再次启动" }

        // SRV 解析会额外做一次 DNS 查询；M1 只接受显式 host:port，行为必须可预测。
        setFlag(BuiltinFlags.ATTEMPT_SRV_RESOLVE, false)
        setFlag(BuiltinFlags.CLIENT_CONNECT_TIMEOUT, connectTimeoutSeconds)
        setFlag(BuiltinFlags.READ_TIMEOUT, readTimeoutSeconds)
        // 转移包会在协议库内部新建一个会话并阻塞 join，绕过本对象的生命周期管理。
        // 关闭跟随，让转移表现为一次断开，由上层决定后续行为。
        setFlag(MinecraftConstants.FOLLOW_TRANSFERS, false)

        if (statusQuery) {
            setFlag(
                MinecraftConstants.SERVER_INFO_HANDLER_KEY,
                ServerInfoHandler { _, info -> statusInfo = info },
            )
            setFlag(
                MinecraftConstants.SERVER_PING_TIME_HANDLER_KEY,
                ServerPingTimeHandler { _, latency ->
                    // 库在 Pong 回调之后会自行断开，这是状态查询的正常收尾。
                    localClose.set(true)
                    emit(ProtocolEvent.StatusReceived(statusInfo.toStatus(latency)))
                },
            )
        }

        connect(false)
    }

    fun shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return
        localClose.set(true)

        try {
            if (isConnected) {
                disconnect(Component.text("客户端关闭"))
            }
        } finally {
            // 已经断开过的连接不会再回调 DisconnectedEvent，这里补齐唯一一次终止通知。
            val reportClosed = closedReported.compareAndSet(false, true)
            try {
                if (reportClosed) emit(ProtocolEvent.Closed(ProtocolCloseReason.Local))
            } finally {
                shutdownLoopGroup()
            }
        }
    }

    override fun getEventLoopGroup(): EventLoopGroup {
        loopGroup?.let { return it }
        val created = TransportHelper.TRANSPORT_TYPE.eventLoopGroupFactory()
            .apply(1, DefaultThreadFactory(THREAD_NAME_PREFIX, true))
        loopGroup = created
        return created
    }

    override fun callEvent(event: SessionEvent) {
        // 先让协议库自己的监听器处理：状态切换、握手、自动应答都发生在 super 里。
        super.callEvent(event)

        when (event) {
            is ConnectedEvent -> {
                reachedServer.set(true)
                emit(ProtocolEvent.Connected)
                reportPhase()
            }
            is DisconnectedEvent -> reportClosed(event)
            else -> Unit
        }
    }

    override fun callPacketReceived(packet: Packet) {
        super.callPacketReceived(packet)

        // 先完成协议要求本端回的应答，再向上报告；顺序反过来时，消费者越慢
        // 越容易把服务端的等待拖成超时。
        when (packet) {
            is ClientboundRegistryDataPacket -> {
                // 注册表条目顺序即 wire id，必须在 Login 之前读出来：区块解码依赖维度高度，
                // 物品组件的附魔键名依赖附魔注册表。
                val registryId = packet.registry.asString()
                if (registryId == DimensionRegistry.REGISTRY_ID) {
                    dimensions = DimensionRegistry.parse(packet.entries)
                }
                if (registryId == ChatTypeRegistry.REGISTRY_ID) {
                    chatTypes = ChatTypeRegistry.parse(packet.entries)
                }
                if (registryId == ItemComponents.ENCHANTMENT_REGISTRY_ID) {
                    enchantmentKeys = ItemComponents.enchantmentKeys(packet.entries)
                }
                emit(ProtocolEvent.RegistryData(registryId, packet.entries.size))
            }

            is ClientboundLoginPacket -> {
                // 注册表条目顺序即维度 id，必须在 Login 之前读出来：区块解码依赖维度高度。
                currentDimension = dimensionOf(packet.commonPlayerSpawnInfo.dimension, "Login")
                // player_command 要带本端实体 id；此前的上行包都不需要它。
                selfEntityId = packet.entityId
                emit(
                    ProtocolEvent.JoinedWorld(
                        entityId = packet.entityId,
                        dimension = packet.commonPlayerSpawnInfo.worldName.asString(),
                        gameMode = packet.commonPlayerSpawnInfo.gameMode.name,
                        viewDistance = packet.viewDistance,
                        simulationDistance = packet.simulationDistance,
                        maxPlayers = packet.maxPlayers,
                    ),
                )
            }

            is ClientboundPlayerPositionPacket -> acceptTeleport(packet)

            is ClientboundRespawnPacket -> respawn(packet)

            is ClientboundLevelChunkWithLightPacket -> {
                chunkTotal++
                emit(ProtocolEvent.ChunkLoaded(decodeChunk(packet)))
            }

            is ClientboundForgetLevelChunkPacket ->
                emit(ProtocolEvent.ChunkUnloaded(packet.x, packet.z))

            // 光照与方块是两条独立链路：改一个方块可能先来方块变更、后来光照更新，
            // 也可能只来其中一个，所以两者各自成一个事件。
            is ClientboundLightUpdatePacket -> emit(
                ProtocolEvent.LightUpdated(
                    x = packet.x,
                    z = packet.z,
                    patch = lightPatch(packet.lightData, lightLayerCount("光照更新")),
                ),
            )

            is ClientboundBlockEntityDataPacket -> emit(
                ProtocolEvent.BlockEntityUpdated(
                    BlockEntity(
                        position = BlockPosition(
                            packet.position.x,
                            packet.position.y,
                            packet.position.z,
                        ),
                        type = blockEntityKey(packet.type),
                        data = packet.nbt?.toNbtCompound(),
                    ),
                ),
            )

            // 命令树与补全：补全请求是上行里**唯一有回执**的一条，服务端把请求编号原样带回。
            is ClientboundCommandsPacket -> emit(ProtocolEvent.CommandTreeReceived(commandTree(packet)))

            is ClientboundCommandSuggestionsPacket ->
                emit(ProtocolEvent.CommandSuggestionsReceived(commandSuggestions(packet)))

            // 转移：只上报目标地址。协议库的自动跟随已关闭（见 attach() 里的 FOLLOW_TRANSFERS），
            // 所以这里之后连接状态不变——换服是上层的决定，本层不替它建连接。
            is ClientboundTransferPacket ->
                emit(ProtocolEvent.TransferRequested(packet.host, packet.port))

            // 方块变更：服务端下发的才是权威结果。挖掉一块方块后本地缓存要跟着变，
            // 否则 `blockStateAt` 会一直返回已经不存在的方块。
            is ClientboundBlockUpdatePacket ->
                emit(ProtocolEvent.BlocksChanged(listOf(blockChange(packet.entry))))

            // 批量版本（爆炸、水流、区块回滚）：一个包一个事件。逐格发事件会打满有界缓冲。
            is ClientboundSectionBlocksUpdatePacket -> {
                if (packet.entries.isNotEmpty()) {
                    emit(ProtocolEvent.BlocksChanged(packet.entries.map(::blockChange)))
                }
            }

            is ClientboundBlockChangedAckPacket ->
                emit(ProtocolEvent.BlockChangeAcknowledged(packet.sequence))

            is ClientboundAddEntityPacket -> spawnEntity(packet)

            is ClientboundRemoveEntitiesPacket -> {
                removeEntities(packet.entityIds)
                for (id in packet.entityIds) {
                    entityAttributes.remove(id)
                    effects.remove(id)
                }
                // 载具被移除时服务端不一定会补一条空乘客列表；不清掉这份映射，
                // 后续的 `move_vehicle` 会落到一个已经不存在的实体上。
                val ridden = selfVehicleId
                if (ridden != null && ridden in packet.entityIds) selfVehicleId = null
            }

            // 乘客列表是**整份替换**：没有“上/下”包，空列表就是下车。
            is ClientboundSetPassengersPacket -> {
                val passengerIds = packet.passengerIds.toList()
                if (selfEntityId in passengerIds) {
                    selfVehicleId = packet.entityId
                } else if (selfVehicleId == packet.entityId) {
                    selfVehicleId = null
                }
                emit(ProtocolEvent.PassengersChanged(packet.entityId, passengerIds))
            }

            // `move_vehicle` 不带实体 id：它只可能指本端当前乘坐的载具，因此靠上面的映射
            // 落地。坐标是绝对量（不像实体移动包是 1/4096 的相对量）。
            is ClientboundMoveVehiclePacket -> {
                val vehicleId = selfVehicleId
                if (vehicleId != null) {
                    updateEntity(vehicleId) { entity ->
                        entity.copy(
                            x = packet.position.x,
                            y = packet.position.y,
                            z = packet.position.z,
                            yaw = packet.yRot,
                            pitch = packet.xRot,
                        )
                    }
                    emit(
                        ProtocolEvent.VehicleMoved(
                            x = packet.position.x,
                            y = packet.position.y,
                            z = packet.position.z,
                            yaw = packet.yRot,
                            pitch = packet.xRot,
                        ),
                    )
                }
            }

            is ClientboundUpdateAttributesPacket -> applyAttributes(packet)

            is ClientboundDamageEventPacket -> emit(
                ProtocolEvent.Damaged(
                    entityId = packet.entityId,
                    sourceType = packet.sourceTypeId,
                    sourceCause = packet.sourceCauseId.takeIf { it >= 0 },
                    sourceDirect = packet.sourceDirectId.takeIf { it >= 0 },
                ),
            )

            is ClientboundMoveEntityPosPacket -> updateEntity(packet.entityId) { entity ->
                entity.copy(
                    x = entity.x + packet.moveX,
                    y = entity.y + packet.moveY,
                    z = entity.z + packet.moveZ,
                )
            }

            is ClientboundMoveEntityRotPacket -> updateEntity(packet.entityId) { entity ->
                entity.copy(yaw = packet.yaw, pitch = packet.pitch)
            }

            is ClientboundMoveEntityPosRotPacket -> updateEntity(packet.entityId) { entity ->
                entity.copy(
                    x = entity.x + packet.moveX,
                    y = entity.y + packet.moveY,
                    z = entity.z + packet.moveZ,
                    yaw = packet.yaw,
                    pitch = packet.pitch,
                )
            }

            is ClientboundRotateHeadPacket -> updateEntity(packet.entityId) { entity ->
                entity.copy(headYaw = packet.headYaw)
            }

            is ClientboundEntityPositionSyncPacket -> updateEntity(packet.id) { entity ->
                entity.copy(
                    x = packet.position.x,
                    y = packet.position.y,
                    z = packet.position.z,
                    yaw = packet.yRot,
                    pitch = packet.xRot,
                )
            }

            is ClientboundTeleportEntityPacket -> updateEntity(packet.id) { entity ->
                val relative = packet.relatives
                entity.copy(
                    x = if (PositionElement.X in relative) entity.x + packet.position.x else packet.position.x,
                    y = if (PositionElement.Y in relative) entity.y + packet.position.y else packet.position.y,
                    z = if (PositionElement.Z in relative) entity.z + packet.position.z else packet.position.z,
                    yaw = if (PositionElement.Y_ROT in relative) entity.yaw + packet.yRot else packet.yRot,
                    pitch = if (PositionElement.X_ROT in relative) entity.pitch + packet.xRot else packet.xRot,
                )
            }

            is ClientboundChunkBatchFinishedPacket -> acknowledgeChunkBatch(packet)

            is ClientboundSetEntityDataPacket -> applyEntityMetadata(packet)

            is ClientboundPlayerInfoUpdatePacket -> applyPlayerInfo(packet)

            is ClientboundPlayerInfoRemovePacket -> removePlayers(packet.profileIds)

            is ClientboundSystemChatPacket -> emit(ProtocolEvent.ChatReceived(systemChat(packet)))

            is ClientboundPlayerChatPacket -> receivePlayerChat(packet)

            is ClientboundDisguisedChatPacket ->
                emit(ProtocolEvent.ChatReceived(disguisedChat(packet)))

            is ClientboundDeleteChatPacket ->
                emit(ProtocolEvent.ChatDeleted(packet.messageSignature.id))

            is ClientboundOpenScreenPacket -> openScreen(packet)

            is ClientboundContainerClosePacket -> forgetContainer(packet.containerId)

            is ClientboundContainerSetContentPacket -> setContainerContent(packet)

            is ClientboundContainerSetSlotPacket ->
                updateContainerSlot(packet.containerId, packet.stateId, packet.slot, packet.item)

            is ClientboundSetPlayerInventoryPacket -> {
                // 这个包不带窗口 id，只能用来改玩家自己的背包；槽位编号与窗口 0 不同，须换算。
                val windowSlot = PlayerSlotMapping.toWindowSlot(packet.slot)
                if (windowSlot != null) {
                    updateContainerSlot(Container.PLAYER_WINDOW_ID, 0, windowSlot, packet.contents)
                }
            }

            is ClientboundSetCursorItemPacket -> applyCursor(packet.contents)

            is ClientboundSetHeldSlotPacket -> setHeldSlot(packet.slot)

            is ClientboundSetHealthPacket ->
                emit(ProtocolEvent.HealthUpdated(packet.health, packet.food, packet.saturation))

            is ClientboundSetExperiencePacket ->
                emit(ProtocolEvent.ExperienceUpdated(packet.experience, packet.level, packet.totalExperience))

            is ClientboundSetEquipmentPacket -> applyEquipment(packet)

            // 服务端权威能力：这是对本端 setFlying 之类请求的答复，不是请求本身。
            is ClientboundPlayerAbilitiesPacket -> emit(
                ProtocolEvent.AbilitiesUpdated(
                    PlayerAbilities(
                        invincible = packet.isInvincible,
                        canFly = packet.isCanFly,
                        flying = packet.isFlying,
                        creative = packet.isCreative,
                        flySpeed = packet.flySpeed,
                        walkSpeed = packet.walkSpeed,
                    ),
                ),
            )

            // 状态效果：一个包只带一个效果，新增/替换与移除是两种包。
            is ClientboundUpdateMobEffectPacket -> applyMobEffect(packet)

            is ClientboundRemoveMobEffectPacket -> removeMobEffect(packet)

            // 容器属性：服务端只给编号和值，下标含义由窗口类型决定。
            is ClientboundContainerSetDataPacket -> {
                val property = packet.rawProperty
                val value = packet.value
                containerProperties.getOrPut(packet.containerId) { ConcurrentHashMap() }[property] = value
                emit(ProtocolEvent.ContainerPropertyChanged(packet.containerId, property, value))
            }

            else -> Unit
        }

        // 阶段只在包被协议库处理完之后读取，见类注释第 2 点。
        reportPhase()
    }

    /**
     * 传送确认。
     *
     * 服务端在出生、传送与移动修正时都会下发该包，并在收到确认前把玩家视为
     * 未就位；不回确认会表现为“人已经进世界但不走”。坐标可能是相对量，
     * 必须叠加在上一次已知坐标上，否则位置会逐次漂移。
     */
    private fun acceptTeleport(packet: ClientboundPlayerPositionPacket) {
        val relative = packet.relatives
        val current = pose.get()
        val next = LocalPose(
            x = if (PositionElement.X in relative) current.x + packet.position.x else packet.position.x,
            y = if (PositionElement.Y in relative) current.y + packet.position.y else packet.position.y,
            z = if (PositionElement.Z in relative) current.z + packet.position.z else packet.position.z,
            yaw = if (PositionElement.Y_ROT in relative) current.yaw + packet.yRot else packet.yRot,
            pitch = if (PositionElement.X_ROT in relative) current.pitch + packet.xRot else packet.xRot,
            // 传送不改变“是否站在地上”：那是本端对脚下的判断，服务端不会随传送一起告诉本端。
            onGround = current.onGround,
        )
        pose.set(next)

        send(ServerboundAcceptTeleportationPacket(packet.id))
        emit(ProtocolEvent.PositionUpdated(next.x, next.y, next.z, next.yaw, next.pitch))
    }

    /**
     * 重生与维度切换。
     *
     * 服务端在 `ClientboundRespawnPacket` 里换维度，而新维度的最低 Y 与高度可能完全不同；
     * 区块会因为高度对不上而整列丢弃，实体也不再属于这个世界。因此这里换掉维度形状、
     * 清空实体表、把区块计数与 player_loaded 复位——服务端会重发全部区块，首批到达后
     * 本端需要再回一次 player_loaded。
     *
     * 玩家的实体 id 不变；位置由紧随其后的位置同步包给出，在那之前没有可信坐标。
     */
    private fun respawn(packet: ClientboundRespawnPacket) {
        val info = packet.commonPlayerSpawnInfo
        currentDimension = dimensionOf(info.dimension, "Respawn")
        chunkTotal = 0
        playerLoadedSent = false
        entities.clear()
        entityAttributes.clear()
        // 属性、状态效果与容器属性都属于“上一个身体/上一个窗口”：换维度后服务端会重发，
        // 留着旧值等于把上一条命的事实报给调用方。
        effects.clear()
        containerProperties.clear()
        // 玩家列表不清：它是会话级事实，换维度不会让人从 Tab 列表里消失。
        emit(
            ProtocolEvent.Respawned(
                dimension = info.worldName.asString(),
                gameMode = info.gameMode.name,
            ),
        )
    }

    /** 维度 id 是注册表下标；越界说明本端没有正确的注册表，只能失败而不是猜默认高度。 */
    private fun dimensionOf(id: Int, context: String): DimensionShape =
        dimensions.getOrNull(id) ?: throw IllegalStateException(
            "$context 指向维度 id $id，但 dimension_type 注册表只有 ${dimensions.size} 项",
        )

    /** 协议里的方块变更条目 → 上层看到的不可变事实。 */
    private fun blockChange(entry: BlockChangeEntry): BlockChange {
        val position = entry.position
        return BlockChange(BlockPosition(position.x, position.y, position.z), entry.block)
    }

    /**
     * 区块批次确认。
     *
     * 服务端按批次发送区块并等待本端回送“还能收多少”；不回确认时发送会在若干批
     * 之后停住，表现是视野里只有出生点附近一小片。首批收完即视为初始加载完成，
     * 补发 player_loaded —— 无头客户端不需要等渲染，但服务端会用它判断玩家已就绪。
     */
    private fun acknowledgeChunkBatch(packet: ClientboundChunkBatchFinishedPacket) {
        send(ServerboundChunkBatchReceivedPacket(DESIRED_CHUNKS_PER_TICK))
        emit(ProtocolEvent.ChunksReceived(packet.batchSize, chunkTotal))

        if (!playerLoadedSent) {
            playerLoadedSent = true
            send(ServerboundPlayerLoadedPacket.INSTANCE)
        }
    }

    /**
     * 区块解码。
     *
     * 解码在网络线程上完成：一列 24 个 section 的工作量在微秒级，远低于读超时；
     * 这里同样不能做任何可能阻塞的操作，否则会连带拖住库对 KeepAlive 的自动应答。
     *
     * 一个 `level_chunk_with_light` 包里的四部分（section、高度图、方块实体、光照）在这里
     * 一次组装成一列：它们是同一份世界数据的四个视图，分开保存只会让缓存出现多个真相。
     * 光照与高度图由协议库解析好交出来，本层只做形状校验与坐标换算。
     *
     * 失败必须带坐标与维度，否则“世界缺了一块”在现场无法定位。
     */
    private fun decodeChunk(packet: ClientboundLevelChunkWithLightPacket): ChunkColumn {
        val dimension = currentDimension
            ?: throw IllegalStateException("收到区块 (${packet.x}, ${packet.z}) 时还没有确定当前维度")
        val blockEntities: List<BlockEntity> =
            packet.blockEntities.map { info: BlockEntityInfo -> info.toBlockEntity(packet.x, packet.z) }
        return try {
            ChunkDecoder.decode(
                x = packet.x,
                z = packet.z,
                minY = dimension.minY,
                sectionCount = dimension.sectionCount,
                data = packet.chunkData,
                heightmaps = heightmapsOf(packet),
                light = ChunkLight.of(ChunkLight.layersFor(dimension.sectionCount))
                    .withPatch(lightPatch(packet.lightData, ChunkLight.layersFor(dimension.sectionCount))),
                blockEntities = blockEntities,
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "解码维度 ${dimension.id} 的区块 (${packet.x}, ${packet.z}) 失败：${e.message}",
                e,
            )
        }
    }

    /** 高度图种类名与 MCPL 枚举一一对应；出现未知种类说明协议库升级了，不能静默丢弃。 */
    private fun heightmapsOf(packet: ClientboundLevelChunkWithLightPacket): Map<HeightmapType, LongArray> =
        packet.heightMaps.entries.associate { (type, packed) ->
            heightmapType(type) to packed
        }

    private fun heightmapType(type: HeightmapTypes): HeightmapType = when (type) {
        HeightmapTypes.WORLD_SURFACE_WG -> HeightmapType.WORLD_SURFACE_WG
        HeightmapTypes.WORLD_SURFACE -> HeightmapType.WORLD_SURFACE
        HeightmapTypes.OCEAN_FLOOR_WG -> HeightmapType.OCEAN_FLOOR_WG
        HeightmapTypes.OCEAN_FLOOR -> HeightmapType.OCEAN_FLOOR
        HeightmapTypes.MOTION_BLOCKING -> HeightmapType.MOTION_BLOCKING
        HeightmapTypes.MOTION_BLOCKING_NO_LEAVES -> HeightmapType.MOTION_BLOCKING_NO_LEAVES
    }

    /**
     * 光照包 → 增量。
     *
     * 两层掩码与两段数组要按**升序层号**对齐：掩码里第 n 个为 1 的位对应数组里的第 n 段，
     * 而不是“层号即数组下标”。漏掉这一层换算的表现是整片天空光错位，且看起来像地形问题。
     * 掩码与数组数量不一致时直接失败——那说明包本身的语义已经不对。
     */
    private fun lightLayerCount(context: String): Int {
        val dimension = currentDimension
            ?: throw IllegalStateException("收到$context 时还没有确定当前维度")
        return ChunkLight.layersFor(dimension.sectionCount)
    }

    /** 方块实体类型：协议里是枚举序号，键名是枚举名的小写形式。 */
    private fun blockEntityKey(type: BlockEntityType): String =
        "minecraft:" + type.name.lowercase(Locale.ROOT)

    private fun BlockEntityInfo.toBlockEntity(chunkX: Int, chunkZ: Int): BlockEntity = BlockEntity(
        position = BlockPosition((chunkX shl 4) + x, y, (chunkZ shl 4) + z),
        type = blockEntityKey(type),
        data = nbt?.toNbtCompound(),
    )

    /**
     * 实体生成。
     *
     * 同一 id 再次出现时直接覆盖：服务端在视野切换后可能整段重发，语义是
     * “以最新一次为准”，不是错误。
     */
    private fun spawnEntity(packet: ClientboundAddEntityPacket) {
        val entity = TrackedEntity(
            id = packet.entityId,
            uuid = packet.uuid,
            type = packet.type.name,
            x = packet.x,
            y = packet.y,
            z = packet.z,
            yaw = packet.yaw,
            pitch = packet.pitch,
            headYaw = packet.headYaw,
        )
        entities[packet.entityId] = entity
        emit(ProtocolEvent.EntitySpawned(entity))
    }

    /** 移除实体；未跟踪的 id 静默跳过——没有可撤销的状态。 */
    private fun removeEntities(ids: IntArray) {
        val removed = ids.filter { entities.remove(it) != null }
        if (removed.isEmpty()) return
        emit(ProtocolEvent.EntityRemoved(removed))
    }

    /**
     * 实体状态更新。
     *
     * 未知 id 的增量包被丢弃而不是报错：增量只有叠加到已知状态上才有意义，
     * 而“尚未收到过该实体”本身是合法状态。真正的解析失败仍会在解码阶段抛出，
     * 不会在这里被吞掉。
     */
    private fun updateEntity(id: Int, change: (TrackedEntity) -> TrackedEntity) {
        val current = entities[id] ?: return
        val updated = change(current)
        entities[id] = updated
        emit(ProtocolEvent.EntityUpdated(updated))
    }

    /**
     * 实体元数据里的自定义名称与生物血量。
     *
     * 元数据是一张「下标 → 值」的表，下标由协议版本固定，但**含义取决于实体层级**：
     * 下标 2 是所有实体共有的自定义名称；生物层从 8 开始，血量在 9，而同样的 9 在船、
     * 掉落物或展示实体上是完全无关的字段。协议包不携带层级信息，因此这里按实体类型查
     * [LivingEntityTypes]，不在表内的类型不读血量——不猜层级。
     *
     * 字段存在但为空表示服务端要求清掉名牌；字段不出现才表示「本包没提这件事」。
     * 未知实体的元数据直接忽略，理由与移动增量相同：没有可更新的状态。
     */
    private fun applyEntityMetadata(packet: ClientboundSetEntityDataPacket) {
        val current = entities[packet.entityId] ?: return
        val living = LivingEntityTypes.isLiving(current.type)
        var customName = current.customName
        var health = current.health
        var healthUpdate: Float? = null
        for (metadata in packet.metadata) {
            when (metadata.id) {
                CUSTOM_NAME_INDEX -> {
                    val component = (metadata.value as? Optional<*>)?.orElse(null) as? Component
                    customName = component?.let(ComponentText::render)
                }

                HEALTH_INDEX -> if (living) {
                    val value = (metadata.value as? Number)?.toFloat()
                    if (value != null && value != health) {
                        health = value
                        healthUpdate = value
                    }
                }
            }
        }
        if (customName != current.customName || health != current.health) {
            updateEntity(packet.entityId) { it.copy(customName = customName, health = health) }
        }
        // 快照先入队写缓存，血量事件后入队；消费者收到事件时 entity(id) 已是新值。
        healthUpdate?.let { emit(ProtocolEvent.EntityHealthChanged(packet.entityId, it)) }
    }

    /**
     * 实体装备。
     *
     * 装备是**独立的包**，不靠元数据下标，因此这里对所有实体都成立——与只读下标 2 的自定义
     * 名称不同，不需要实体层级表。
     *
     * 包里的物品为空表示“把这个槽清空”，不是“本包没提这个槽”，因此要删键而不是保留旧值。
     * 未知实体的装备直接忽略：没有可更新的状态。
     */
    private fun applyEquipment(packet: ClientboundSetEquipmentPacket) {
        val current = entities[packet.entityId] ?: return
        val equipment = HashMap(current.equipment)
        var changed = false
        for (entry in packet.equipment) {
            val key = entry.slot.name
            val item = entry.item?.toInventoryItem()
            if (item == null) {
                if (equipment.remove(key) != null) changed = true
            } else if (equipment[key] != item) {
                equipment[key] = item
                changed = true
            }
        }
        if (!changed) return
        updateEntity(packet.entityId) { it.copy(equipment = equipment) }
    }

    /**
     * 实体属性更新。按键合并进累积表后，把**本包出现**的项上报——事件里带全表会让
     * 「这一包到底改了什么」无从分辨，合并结果留给读接口。
     */
    private fun applyAttributes(packet: ClientboundUpdateAttributesPacket) {
        val table = entityAttributes.getOrPut(packet.entityId) { HashMap() }
        val changed = ArrayList<EntityAttribute>(packet.attributes.size)
        for (attribute in packet.attributes) {
            // 协议库的内置属性表按旧版编号反查，键名会错位（详见 [AttributeTable] 的 KDoc）；
            // `type.id` 实际返回 ordinal，而 ordinal 恰好等于 wire id，据此查我们自己的表。
            val key = AttributeTable.key(attribute.type.id) ?: "unknown:${attribute.type.id}"
            val converted = EntityAttribute(
                key = key,
                value = attribute.value,
                modifiers = attribute.modifiers.map {
                    EntityAttributeModifier(it.id.toString(), it.amount, it.operation.name)
                },
            )
            table[converted.key] = converted
            changed += converted
        }
        emit(ProtocolEvent.AttributesUpdated(packet.entityId, changed))
    }

    /** 查询某实体的属性表快照；本层由 [ProtocolConnection] 暴露给上层。 */
    fun attributes(entityId: Int): Map<String, EntityAttribute> =
        entityAttributes[entityId]?.toMap() ?: emptyMap()

    /**
     * 状态效果新增或替换。
     *
     * 键名由数字 id 还原；未知 id 保留 `unknown:<id>` 而不是丢弃，否则新版本效果会让
     * 整条事实凭空消失。四个显示标志是效果的属性，照原样落到快照里。
     */
    private fun applyMobEffect(packet: ClientboundUpdateMobEffectPacket) {
        val id = packet.effect.ordinal
        val key = EffectKeys.key(id) ?: "unknown:$id"
        val effect = MobEffect(
            key = key,
            id = id,
            amplifier = packet.amplifier,
            durationTicks = packet.duration,
            ambient = packet.isAmbient,
            showParticles = packet.isShowParticles,
            showIcon = packet.isShowIcon,
            blend = packet.isBlend,
        )
        effects.getOrPut(packet.entityId) { ConcurrentHashMap() }[key] = effect
        emit(ProtocolEvent.EffectsUpdated(packet.entityId, listOf(effect), emptyList()))
    }

    /** 状态效果移除：只删被点名的那个键，同实体的其它效果保持不动。 */
    private fun removeMobEffect(packet: ClientboundRemoveMobEffectPacket) {
        val id = packet.effect.ordinal
        val key = EffectKeys.key(id) ?: "unknown:$id"
        effects[packet.entityId]?.remove(key)
        emit(ProtocolEvent.EffectsUpdated(packet.entityId, emptyList(), listOf(key)))
    }

    /** 某实体当前的状态效果表（按键合并后的快照）。 */
    fun effects(entityId: Int): Map<String, MobEffect> =
        effects[entityId]?.toMap() ?: emptyMap()

    /** 某窗口当前的数值属性表（下标 → 值）。 */
    fun containerProperties(windowId: Int): Map<Int, Int> =
        containerProperties[windowId]?.toMap() ?: emptyMap()

    /**
     * 整份声明运动按键。
     *
     * `player_input` 是状态包而不是事件包：服务端按最后一次收到的字节为准，
     * 因此每次改动都把七个布尔位重发一遍。调用方分别改潜行/冲刺时，本方法负责
     * 用上一份状态补齐其它位。
     */
    fun setMovementInput(input: MovementInput) {
        movementInput.set(input)
        sendInPlay("移动输入") {
            ServerboundPlayerInputPacket(
                input.forward, input.backward, input.left, input.right,
                input.jump, input.sneaking, input.sprinting,
            )
        }
    }

    /** 切换潜行（shift 位）。服务端不回包，本端在发送后立即推进状态。 */
    fun setSneaking(enabled: Boolean) {
        setMovementInput(movementInput.get().copy(sneaking = enabled))
    }

    /**
     * 切换冲刺。
     *
     * 26.2 里冲刺同时要两件事：`player_input` 的 sprint 位与独立的
     * `player_command`（START/STOP_SPRINTING）——后者带实体 id，服务端据此改移动速度。
     * 原版客户端冲刺与潜行互斥，因此开启冲刺时顺带清掉 shift 位。
     */
    fun setSprinting(enabled: Boolean) {
        val id = selfEntityId
        check(id >= 0) { "还没有收到 Login 包，无法确定本端实体 id" }
        val current = movementInput.get()
        setMovementInput(
            current.copy(
                sprinting = enabled,
                sneaking = if (enabled) false else current.sneaking,
            ),
        )
        sendInPlay("冲刺状态") {
            ServerboundPlayerCommandPacket(
                id,
                if (enabled) PlayerState.START_SPRINTING else PlayerState.STOP_SPRINTING,
            )
        }
    }

    /**
     * 请求开始/停止飞行。
     *
     * 只是请求：服务端按 `player_abilities` 下发权威结果（可能拒绝），本层不把
     * 上行包当成“已经在飞”。
     */
    fun setFlying(enabled: Boolean) {
        sendInPlay("飞行状态") { ServerboundPlayerAbilitiesPacket(enabled) }
    }

    /** 本端最后一次声明的运动按键，用于上层快照与便捷方法的增量合并。 */
    fun movementInput(): MovementInput = movementInput.get()

    /**
     * 玩家列表更新。
     *
     * 一个包只带 `actions` 声明的那几列：可能只有延迟，也可能只有档案。缺失字段沿用
     * 上一状态——否则“先到延迟、后到档案”的常见顺序会把刚拿到的用户名抹成 null。
     * 合并后没有任何变化（例如只更新了列表排序）就不上报，避免把无关字段变成事件。
     */
    private fun applyPlayerInfo(packet: ClientboundPlayerInfoUpdatePacket) {
        val actions = packet.actions
        val updated = ArrayList<RemotePlayer>(packet.entries.size)
        for (entry in packet.entries) {
            val existing = players[entry.profileId]
            val merged = RemotePlayer(
                uuid = entry.profileId,
                name = if (PlayerListEntryAction.ADD_PLAYER in actions) {
                    entry.profile?.name ?: existing?.name
                } else {
                    existing?.name
                },
                gameMode = if (PlayerListEntryAction.UPDATE_GAME_MODE in actions) {
                    entry.gameMode.name
                } else {
                    existing?.gameMode ?: entry.gameMode.name
                },
                latencyMillis = if (PlayerListEntryAction.UPDATE_LATENCY in actions) {
                    entry.latency
                } else {
                    existing?.latencyMillis ?: entry.latency
                },
            )
            if (merged == existing) continue
            players[entry.profileId] = merged
            updated += merged
        }
        if (updated.isEmpty()) return
        emit(ProtocolEvent.PlayerListChanged(updated, emptyList()))
    }

    /** 玩家离开。列表里没有的 UUID 静默跳过——没有可撤销的状态。 */
    private fun removePlayers(ids: List<UUID>) {
        val removed = ids.filter { players.remove(it) != null }
        if (removed.isEmpty()) return
        emit(ProtocolEvent.PlayerListChanged(emptyList(), removed))
    }

    /**
     * 系统消息。
     *
     * `overlay` 区分聊天栏与动作栏：两者走同一个包，只差一个布尔位。语义上差别很大
     * （前者可回看，后者一闪而过），所以保留这个标记，让下游自己决定。
     */
    private fun systemChat(packet: ClientboundSystemChatPacket): ChatMessage = ChatMessage(
        kind = ChatKind.SYSTEM,
        text = ComponentText.text(packet.content),
        overlay = packet.isOverlay,
    )

    /**
     * 玩家聊天。
     *
     * 正文优先取未签名内容：服务端在过滤或改写后会把它单独下发，`content` 保留原始输入。
     * 两者都没有样式信息可用，本层只给纯文本。
     *
     * 确认包必须紧跟着回送：不带确认时服务端的待确认窗口只增不减，真实服务端会因此
     * 拒绝后续带签名的消息。本端只发不带签名的消息，但同样要维持这个窗口——窗口是
     * 服务端对“客户端是否跟得上”的判断，与消息本身是否签名无关。
     */
    private fun receivePlayerChat(packet: ClientboundPlayerChatPacket) {
        send(ServerboundChatAckPacket(CHAT_ACK_PER_MESSAGE))

        emit(
            ProtocolEvent.ChatReceived(
                ChatMessage(
                    kind = ChatKind.PLAYER,
                    text = packet.unsignedContent?.let(ComponentText::text) ?: packet.content,
                    sender = packet.sender,
                    senderName = ComponentText.text(packet.name),
                    targetName = packet.targetName?.let(ComponentText::text),
                    format = ChatTypeRegistry.formatOf(packet.chatType, chatTypes),
                    timestamp = packet.timeStamp,
                    // 没带签名的消息（服务端关闭了安全聊天）无法被 delete_chat 回收，
                    // 给它一个编号反而会让上层以为可以。
                    signatureId = if (packet.messageSignature != null) packet.index else null,
                ),
            ),
        )
    }

    /**
     * 伪装聊天：服务端借某人的名义说话（`/say`、`/me`、控制台）。
     *
     * 带名字但没有 UUID 与签名，因此不能当成玩家消息处理：谁都能被“冒充”，
     * 本层也不去猜是哪个玩家。
     */
    private fun disguisedChat(packet: ClientboundDisguisedChatPacket): ChatMessage = ChatMessage(
        kind = ChatKind.DISGUISED,
        text = ComponentText.text(packet.message),
        senderName = ComponentText.text(packet.name),
        targetName = packet.targetName?.let(ComponentText::text),
        format = ChatTypeRegistry.formatOf(packet.chatType, chatTypes),
    )

    /**
     * 打开窗口。
     *
     * 内容随后由 `container_set_content` 下发；这里先建一个空容器，让“窗口已经打开”
     * 与“内容还没到”成为两个可区分的事实——直接等整包内容，会让打开的箱子看起来不存在。
     */
    private fun openScreen(packet: ClientboundOpenScreenPacket) {
        val container = MutableContainer(packet.containerId)
        container.type = packet.type.name
        container.title = ComponentText.text(packet.title)
        containers[packet.containerId] = container
        emit(ProtocolEvent.ContainerOpened(container.snapshot()))
    }

    /**
     * 服务端关闭窗口。
     *
     * 窗口 0 是玩家背包，服务端不会关闭它；收到就忽略——把背包当成“已关闭”会让
     * 后续查询凭空失败。
     */
    private fun forgetContainer(windowId: Int) {
        if (windowId == Container.PLAYER_WINDOW_ID) return
        if (containers.remove(windowId) == null) return
        // 属性是窗口的一部分：窗口没了，熔炉进度之类也就没有落点。
        containerProperties.remove(windowId)
        emit(ProtocolEvent.ContainerClosed(windowId))
    }

    /** 整包内容：一次性替换全部槽位与鼠标物品。 */
    private fun setContainerContent(packet: ClientboundContainerSetContentPacket) {
        val container = containerFor(packet.containerId)
        container.setContent(
            stateId = packet.stateId,
            items = packet.items.map { it?.toInventoryItem() },
            cursor = packet.carriedItem?.toInventoryItem(),
        )
        emit(ProtocolEvent.ContainerUpdated(container.snapshot()))
    }

    /**
     * 记录光标物品。
     *
     * 协议里光标不带窗口 id：它属于玩家，但服务端只在**当前打开的那个菜单**上维护它（原版客户端
     * 也是把光标存在 menu 上）。因此这里写到活动窗口——没开容器时就是窗口 0。这样“点这个窗口时
     * 声明给服务端的光标”与“这个窗口快照里的光标”永远是同一份，不会出现两处各说一套。
     *
     * 没有变化就不上报：服务端在去同步检测之后可能补发一条相同的光标。
     */
    private fun applyCursor(item: ItemStack?) {
        val windowId = containers.keys.filter { it != Container.PLAYER_WINDOW_ID }.maxOrNull()
            ?: Container.PLAYER_WINDOW_ID
        val container = containerFor(windowId)
        val cursor = item?.toInventoryItem()
        if (container.cursor == cursor) return
        container.cursor = cursor
        emit(ProtocolEvent.ContainerUpdated(container.snapshot()))
    }

    /** 单槽更新；未知窗口按需建容器（出生时的背包就可能先来单槽、后到整包）。 */
    private fun updateContainerSlot(windowId: Int, stateId: Int, slot: Int, item: ItemStack?) {
        val container = containerFor(windowId)
        container.stateId = stateId
        container.setSlot(slot, item?.toInventoryItem())
        emit(ProtocolEvent.ContainerUpdated(container.snapshot()))
    }

    private fun containerFor(windowId: Int): MutableContainer =
        containers.getOrPut(windowId) { MutableContainer(windowId) }

    /**
     * 物品堆转公共快照。
     *
     * 键名从本仓库的注册表还原；自定义名称从组件补丁里取——组件可能显式删除该键
     * （补丁语义），取不到就是 null，与「没有设置过」不可区分，两者对外的含义一致。
     */
    private fun ItemStack.toInventoryItem(): InventoryItem = ItemComponents.read(this, enchantmentKeys)

    private fun reportPhase() {
        val phase = when (packetProtocol.inboundState) {
            ProtocolState.LOGIN -> ProtocolPhase.LOGIN
            ProtocolState.CONFIGURATION -> ProtocolPhase.CONFIGURATION
            ProtocolState.GAME -> ProtocolPhase.PLAY
            else -> return
        }
        if (lastPhase == phase) return
        lastPhase = phase

        // 服务端在配置阶段就按客户端设置确定视野距离与语言；不发这一包时
        // Minestom 侧玩家设置为空，出生流程会在读取视野距离时失败。
        if (phase == ProtocolPhase.CONFIGURATION) sendClientInformation()

        emit(ProtocolEvent.PhaseChanged(phase))
    }

    private fun sendClientInformation() {
        send(
            ServerboundClientInformationPacket(
                "en_us",
                CLIENT_VIEW_DISTANCE,
                ChatVisibility.FULL,
                true,
                SkinPart.VALUES.toList(),
                HandPreference.RIGHT_HAND,
                false,
                true,
                ParticleStatus.ALL,
            ),
        )
    }

    /**
     * 发送一条聊天消息。
     *
     * 调用线程不限：协议库的 `send` 会把写入切到该连接自己的事件循环，因此上层不需要
     * 关心网络线程。顺序只在同一调用线程内成立——跨线程调用按事件循环收到的先后排队。
     *
     * 消息**不带签名**。离线模式的服务端（`enforce-secure-profile=false`）接受这种消息；
     * 启用安全聊天的服务端需要会话密钥，那属于在线模式（M5），本层不做半套签名。
     *
     * last-seen 窗口按“什么都没看到”填写（偏移 0、位集全空、校验 0）。协议里这是合法状态，
     * 语义是真的：本端没有为任何已收到的消息做过签名确认。填一个假的“已看到”反而会与
     * 服务端记录的待确认序列不一致。
     */
    fun sendChat(message: String) {
        sendInPlay("聊天") {
            ServerboundChatPacket(
                message,
                System.currentTimeMillis(),
                0L,
                null,
                0,
                BitSet(CHAT_ACK_WINDOW),
                0,
            )
        }
    }

    /**
     * 发送一条命令（不含前导斜杠）。
     *
     * 命令包本身不带签名与 last-seen 字段，因此与聊天不同：即使在启用安全聊天的服务端上
     * 也能用，只要命令本身允许该玩家执行。
     */
    fun sendCommand(command: String) {
        sendInPlay("命令") { ServerboundChatCommandPacket(command) }
    }

    /**
     * 请求一次命令补全。
     *
     * [transactionId] 由调用方分配：同一连接上可以同时有多个未完成的请求，服务端把编号
     * 原样带回，所以并发请求不会互相串答案。编号是 `varint`，取值只要在一个连接内唯一。
     *
     * [text] 是**完整输入行**（含前导斜杠）：服务端按它自己解析出的当前位置决定补全哪一段，
     * 只发末尾几个字符会让它算错区间。是否在补全候选里返回什么由服务端决定，
     * 本端不本地推候选——候选依赖权限、世界状态与实体，本地猜必然与服务端不一致。
     */
    fun requestCommandSuggestions(transactionId: Int, text: String) {
        sendInPlay("命令补全") { ServerboundCommandSuggestionPacket(transactionId, text) }
    }

    /**
     * 声明本端移动到了指定位置与朝向。
     *
     * 只发与上一状态不同的部分：协议里 `pos` / `rot` / `pos_rot` 是三个包，缺的字段由服务端
     * 沿用上一状态，所以选择逻辑在 [Movement] 里独立实现并单测。
     *
     * 不做本地物理与预测：调用方给什么就发什么，服务端不同意时会下发位置同步/传送把它改回来。
     * 服务端**接受**移动时不会回包，因此这里在发送后就把本地姿态推进到目标值并上报
     * [ProtocolEvent.PositionSent]——否则“发出去了但本地还停在原地”，后续的差分会一直错。
     */
    fun moveTo(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, onGround: Boolean) {
        Movement.requirePosition(x, y, z)
        Movement.requireAngles(yaw, pitch)
        val current = pose.get()
        applyPose(LocalPose(x, y, z, yaw, pitch, onGround), current, "移动")
    }

    /**
     * 只改朝向，位置保持不变。
     *
     * 服务端的 `rot` 包不带坐标，因此这条路径在尚未收到位置同步时也能用——它只依赖本地
     * 记录的朝向，不依赖“我在哪”。
     */
    fun look(yaw: Float, pitch: Float, onGround: Boolean) {
        Movement.requireAngles(yaw, pitch)
        val current = pose.get()
        applyPose(current.copy(yaw = yaw, pitch = pitch, onGround = onGround), current, "转向")
    }

    /**
     * 声明载具移动到了指定位置与朝向（骑乘时替代 `moveTo` / `look`）。
     *
     * 两个协议事实决定了这里的形状：
     *
     * - **骑乘时不能发玩家位置包**。原版客户端在 `isPassenger()` 时直接跳过位置同步，
     *   服务端也只会按 `move_vehicle` 改载具位置；发了玩家位置包等于宣称自己在走路。
     * - 坐标是**绝对量**，不像实体移动包是 1/4096 的相对量，因此这里不做叠加。
     *
     * 包本身不带实体 id（下行的同名包也不带），所以调用方不必传入载具 id。
     *
     * @throws IllegalArgumentException 坐标或角度非法
     * @throws IllegalStateException 当前不在载具上，或尚未进入 play
     */
    fun steerVehicle(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, onGround: Boolean) {
        Movement.requirePosition(x, y, z)
        Movement.requireAngles(yaw, pitch)
        check(selfVehicleId != null) { "当前没有乘坐载具，不能发送载具移动包" }
        sendInPlay("载具移动") {
            ServerboundMoveVehiclePacket(Vector3d.from(x, y, z), yaw, pitch, onGround)
        }
    }

    /** 比较、发包、推进本地姿态；状态完全一致时不发任何包。 */
    private fun applyPose(target: LocalPose, current: LocalPose, what: String) {
        val packet = Movement.packet(current, target) ?: return
        sendInPlay(what) { packet }
        pose.set(target)
        emit(
            ProtocolEvent.PositionSent(
                x = target.x,
                y = target.y,
                z = target.z,
                yaw = target.yaw,
                pitch = target.pitch,
                onGround = target.onGround,
            ),
        )
    }

    /**
     * 切换快捷栏选中格（0–8）。
     *
     * 这个包同样没有成功回执：服务端只在**拒绝或改写**时下发 `set_held_slot`，接受时什么都不发。
     * 因此发送后立即推进本地值并上报，否则“切了但本地还是旧格子”，服务端纠正时再覆盖回来。
     */
    fun selectHotbarSlot(slot: Int) {
        sendInPlay("切换快捷栏") { ServerboundSetCarriedItemPacket(slot) }
        setHeldSlot(slot)
    }

    /** 记录选中格；只有真的变了才上报，避免重复包变成事件噪音。 */
    private fun setHeldSlot(slot: Int) {
        if (heldSlot == slot) return
        heldSlot = slot
        emit(ProtocolEvent.HeldSlotChanged(slot))
    }

    /**
     * 点击一个窗口槽位。
     *
     * [container] 是调用方手上的快照，`windowId` 与 `stateId` 都从它取：适配层的容器状态是
     * 网络线程独占的，跨线程读不安全，而快照不可变、本来就是调用方看到的那个版本。
     *
     * 不做本地预测，也不发确认：点击的结果由服务端下发的整包内容或单槽更新体现
     * （[ProtocolEvent.ContainerUpdated]），本地视图永远跟着服务端走。
     */
    fun clickSlot(container: Container, slot: Int, click: SlotClick) {
        sendInPlay("点击槽位") { ContainerClicks.packet(container, slot, click) }
    }

    /**
     * 攻击一个实体。
     *
     * 26.2 把攻击从交互包里拆成了独立的 `attack` 包；原版客户端攻击时还会挥手播动画，
     * [swing] 为 true 时同样跟一发（挥竿只是视觉，服务端不会因此改判定）。
     *
     * 攻击**没有回执包**：服务端处理完的结果（伤害事件、实体血量变化）走各自的下发路径。
     * 目标不存在或不在视野里时服务端会静默忽略，这是「成功没有回执」模式的第四例。
     */
    fun attack(entityId: Int, swing: Boolean) {
        sendInPlay("攻击实体") { ServerboundAttackPacket(entityId) }
        if (swing) sendInPlay("挥手") { ServerboundSwingPacket(toProtocolHand(Hand.MAIN_HAND)) }
    }

    /** 挥动一只手（左键动画）。只是视觉表现，与攻击判定无关。 */
    fun swing(hand: Hand) {
        sendInPlay("挥手") { ServerboundSwingPacket(toProtocolHand(hand)) }
    }

    /**
     * 与实体交互（右键）。
     *
     * [location] 是交互点（实体坐标系里的相对位置）；null 时按协议编码为零向量，
     * 服务端按「无交互点」处理。
     */
    fun interactWith(entityId: Int, hand: Hand, sneaking: Boolean, location: Vector3d?) {
        sendInPlay("与实体交互") {
            ServerboundInteractPacket(entityId, toProtocolHand(hand), location ?: Vector3d.ZERO, sneaking)
        }
    }

    private fun toProtocolHand(hand: Hand) =
        org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand.valueOf(hand.name)

    /**
     * 关闭一个窗口。
     *
     * 这个包也没有成功回执：**客户端主动关闭时服务端不回 `container_close`**——它只在
     * 服务端自己关窗（插件调用 `closeInventory`）时才下发关闭包，因为主动关的那一端已经知道了。
     * 因此发送后立即在本地收敛（移除窗口并上报 [ProtocolEvent.ContainerClosed]）；服务端随后
     * 若再发关闭包，[forgetContainer] 找不到窗口，不会重复上报。
     *
     * 窗口 0（玩家背包）关不掉：它是玩家的身体，不是可以合上的箱子，传 0 直接忽略。
     * 没打开过的窗口也直接忽略，不发无意义的包。
     */
    fun closeContainer(windowId: Int) {
        if (windowId == Container.PLAYER_WINDOW_ID) return
        if (containers[windowId] == null) return
        sendInPlay("关闭窗口") { ServerboundContainerClosePacket(windowId) }
        forgetContainer(windowId)
    }

    /**
     * 开始、取消或完成一次挖掘。
     *
     * 三段都是独立的包，坐标与面必须**原样带回**同一组值：服务端按坐标配对“开始”与“结束”，
     * 中途改了坐标会被当成一次新的动作，前一段的进度作废。
     *
     * 服务端对这三段都会回 `block_changed_ack`（挖掘耗时为 0 的方块还会立刻下发方块变更），
     * 因此调用方可以据此判断“服务端处理到哪一步了”。
     */
    fun dig(action: DigAction, position: BlockPosition, face: BlockFace) {
        val sequence = nextSequence()
        sendInPlay("挖掘方块") { BlockInteraction.digPacket(action, position, face, sequence) }
    }

    /**
     * 一次性挖掉一块方块：开始 + 完成。
     *
     * 不等待挖掘进度——真客户端要按方块硬度、手持工具与状态效果算出耗时，再决定何时发完成；
     * 那是游戏规则，不是协议。严格的服务端会拒绝“进度不足”的完成包（无回执），
     * 需要动画或需要被服务端认可的场合，请改用 [dig] 分别发三段。
     */
    fun digBlock(position: BlockPosition, face: BlockFace) {
        dig(DigAction.START, position, face)
        dig(DigAction.FINISH, position, face)
    }

    /**
     * 对着某格方块使用物品：放置方块，或与方块交互（开箱子、按按钮、耕地）。
     *
     * 光标位置是点击点在方块内的相对坐标（三轴 0..1）。它决定“点在哪个面、哪一格”，
     * 服务端会据此验算，因此不能随便填：默认值取方块中心。
     */
    fun useOnBlock(
        position: BlockPosition,
        face: BlockFace,
        cursorX: Float,
        cursorY: Float,
        cursorZ: Float,
        hand: Hand,
        insideBlock: Boolean,
    ) {
        val sequence = nextSequence()
        sendInPlay("使用物品") {
            BlockInteraction.useOnBlockPacket(
                position = position,
                face = face,
                cursorX = cursorX,
                cursorY = cursorY,
                cursorZ = cursorZ,
                hand = hand,
                insideBlock = insideBlock,
                sequence = sequence,
            )
        }
    }

    /**
     * 对着空气使用手里的物品（吃、喝、投掷、拉弓）。
     *
     * 朝向取自本端当前视角：服务端拿它决定投掷物飞出去的方向，填 0 会让投掷物总是朝正南。
     */
    fun useItem(hand: Hand) {
        val sequence = nextSequence()
        val current = pose.get()
        sendInPlay("使用物品") {
            BlockInteraction.useItemPacket(hand, current.yaw, current.pitch, sequence)
        }
    }

    /** 取下一个交互序号。从 1 开始：0 在某些服务端实现里被当作“没有序号”。 */
    private fun nextSequence(): Int = blockSequence.incrementAndGet()

    /**
     * 上行包的统一前置条件。
     *
     * 三项检查的顺序有讲究：关闭优先于阶段，阶段优先于连接状态——被关闭的连接上
     * “还没到 play”是误导性的说法。晚于 play 阶段之前发送聊天会被服务端当作协议错误
     * 断开，因此这里必须拦住，而不是交给协议库静默排队（`send` 在未连接时会把包放进
     * 待发队列，等连接建立后再发——那正好是我们不想要的行为）。
     */
    private fun sendInPlay(what: String, packet: () -> Packet) {
        check(!shutdownStarted.get()) { "连接已关闭，不能发送$what" }
        check(lastPhase == ProtocolPhase.PLAY) {
            "只有进入 play 阶段后才能发送$what，当前阶段为 ${lastPhase ?: "未连接"}"
        }
        check(isConnected) { "连接已断开，不能发送$what" }
        send(packet())
    }

    private fun reportClosed(event: DisconnectedEvent) {
        if (!closedReported.compareAndSet(false, true)) return

        val reason = when {
            localClose.get() -> ProtocolCloseReason.Local
            event.cause != null -> ProtocolCloseReason.Failure(
                kind = classify(event.cause),
                cause = event.cause,
            )
            else -> ProtocolCloseReason.Server(ComponentText.render(event.reason))
        }
        emit(ProtocolEvent.Closed(reason))

        // 服务端主动断开时没人会再调用 shutdown()，线程回收必须在这里触发。
        shutdownLoopGroup()
    }

    /**
     * 按“连接是否建立过”与异常类型归层：连接建立之前的失败都是连接失败，
     * 之后的 IOException/超时是传输问题，其余归协议问题。
     */
    private fun classify(cause: Throwable): ProtocolFailureKind = when {
        !reachedServer.get() -> ProtocolFailureKind.CONNECT
        cause is IOException || cause is TimeoutException -> ProtocolFailureKind.NETWORK
        else -> ProtocolFailureKind.PROTOCOL
    }

    private fun shutdownLoopGroup() {
        val group = loopGroup ?: return
        group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        // 从组内线程等待自己终止会自锁；这种调用只可能来自回调内部，跳过等待即可。
        if (group.any { executor -> executor.inEventLoop() }) return
        try {
            group.awaitTermination(SHUTDOWN_TIMEOUT_MILLIS + 500, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun emit(event: ProtocolEvent) {
        listener.onEvent(event)
    }

    private fun ServerStatusInfo?.toStatus(latencyMillis: Long): ServerStatus {
        val version = this?.versionInfo
        val players = this?.playerInfo
        return ServerStatus(
            versionName = version?.versionName,
            protocolVersion = version?.protocolVersion,
            onlinePlayers = players?.onlinePlayers,
            maxPlayers = players?.maxPlayers,
            description = this?.description?.let(ComponentText::render).orEmpty(),
            latencyMillis = latencyMillis,
        )
    }

    private companion object {
        const val THREAD_NAME_PREFIX: String = "pickaxe-mcpl"
        const val SHUTDOWN_TIMEOUT_MILLIS: Long = 500

        /**
         * 申请下载区块时每 tick 期望的区块数。
         *
         * 只影响服务端的发送节奏；无头客户端不做渲染，取一个偏保守的值即可。
         */
        const val DESIRED_CHUNKS_PER_TICK: Float = 8.0f

        /** 客户端声明的视野距离（区块半径）。出生点的初始加载量随它线性增长。 */
        const val CLIENT_VIEW_DISTANCE: Int = 8

        /**
         * 自定义名称在实体元数据表里的下标。
         *
         * 0–7 是 `Entity` 层共有的字段，下标 2 是其中唯一与显示相关的文本字段，
         * 对所有实体都成立，不需要知道实体层级。
         */
        const val CUSTOM_NAME_INDEX: Int = 2

        /**
         * 生物生命值在实体元数据表里的绝对下标。
         *
         * `Entity` 层占 0–7，`LivingEntity` 层从 8 开始，其中血量是该层的第 1 项。
         * 这个下标**只在生物层级成立**：同样的 9 对船、掉落物、展示实体是别的字段。
         * 读取前必须先用 [LivingEntityTypes] 判定类型，不能只看下标取值。
         */
        const val HEALTH_INDEX: Int = 9

        /**
         * 每条玩家消息回送的确认步长。
         *
         * 协议语义是“自上次确认以来又看到了几条”，逐条确认时恒为 1；
         * 攒批确认会把延迟暴露给服务端的重发逻辑，没有必要。
         */
        const val CHAT_ACK_PER_MESSAGE: Int = 1

        /**
         * last-seen 位集的位宽。
         *
         * 上行聊天包用固定 20 位表示“最近 20 条消息里哪些被确认过”。本端不发带签名的消息，
         * 位集恒为空，但宽度必须与协议一致——否则写出的字节数不对，整包解码失败。
         */
        const val CHAT_ACK_WINDOW: Int = 20
    }
}
