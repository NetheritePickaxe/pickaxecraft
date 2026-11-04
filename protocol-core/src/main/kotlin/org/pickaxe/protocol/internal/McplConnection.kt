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
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo
import org.geysermc.mcprotocollib.protocol.data.status.handler.ServerInfoHandler
import org.geysermc.mcprotocollib.protocol.data.status.handler.ServerPingTimeHandler
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDeleteChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoRemovePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRotateHeadPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket
import org.pickaxe.protocol.ProtocolCloseReason
import org.pickaxe.protocol.ProtocolEvent
import org.pickaxe.protocol.ProtocolFailureKind
import org.pickaxe.protocol.ProtocolListener
import org.pickaxe.protocol.ProtocolPhase
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.ServerStatus
import org.pickaxe.protocol.chat.ChatFormat
import org.pickaxe.protocol.chat.ChatKind
import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.world.ChunkColumn
import org.pickaxe.protocol.world.RemotePlayer
import org.pickaxe.protocol.world.TrackedEntity
import java.io.IOException
import java.net.InetSocketAddress
import java.util.BitSet
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
                // 注册表条目顺序即维度 id，必须在 Login 之前读出来：区块解码依赖维度高度。
                if (packet.registry.asString() == DimensionRegistry.REGISTRY_ID) {
                    dimensions = DimensionRegistry.parse(packet.entries)
                }
                if (packet.registry.asString() == ChatTypeRegistry.REGISTRY_ID) {
                    chatTypes = ChatTypeRegistry.parse(packet.entries)
                }
                emit(ProtocolEvent.RegistryData(packet.registry.asString(), packet.entries.size))
            }

            is ClientboundLoginPacket -> {
                // 注册表条目顺序即维度 id，必须在 Login 之前读出来：区块解码依赖维度高度。
                currentDimension = dimensionOf(packet.commonPlayerSpawnInfo.dimension, "Login")
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

            is ClientboundAddEntityPacket -> spawnEntity(packet)

            is ClientboundRemoveEntitiesPacket -> removeEntities(packet.entityIds)

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

            is ClientboundContainerClosePacket -> closeContainer(packet.containerId)

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

            is ClientboundSetCursorItemPacket -> {
                val container = containerFor(Container.PLAYER_WINDOW_ID)
                container.cursor = packet.contents?.toInventoryItem()
                emit(ProtocolEvent.ContainerUpdated(container.snapshot()))
            }

            is ClientboundSetHeldSlotPacket -> setHeldSlot(packet.slot)

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
     * 失败必须带坐标与维度，否则“世界缺了一块”在现场无法定位。
     */
    private fun decodeChunk(packet: ClientboundLevelChunkWithLightPacket): ChunkColumn {
        val dimension = currentDimension
            ?: throw IllegalStateException("收到区块 (${packet.x}, ${packet.z}) 时还没有确定当前维度")
        return try {
            ChunkDecoder.decode(packet.x, packet.z, dimension.minY, dimension.sectionCount, packet.chunkData)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "解码维度 ${dimension.id} 的区块 (${packet.x}, ${packet.z}) 失败：${e.message}",
                e,
            )
        }
    }

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
     * 实体元数据里的自定义名称。
     *
     * 元数据是一张“下标 → 值”的表，下标由协议版本固定，但**含义取决于实体层级**：
     * 下标 2 是所有实体共有的自定义名称，而下标 9 之类的字段只在生物层级里才成立。
     * 协议包不携带层级信息，因此本层只读下标 2；其余字段要么需要本地实体类型表，
     * 要么属于别的子系统（装备、状态效果）。
     *
     * 未知实体的元数据直接忽略，理由与移动增量相同：没有可更新的状态。
     */
    private fun applyEntityMetadata(packet: ClientboundSetEntityDataPacket) {
        val current = entities[packet.entityId] ?: return
        var customName = current.customName
        for (metadata in packet.metadata) {
            if (metadata.id != CUSTOM_NAME_INDEX) continue
            // 字段存在但为空表示服务端要求清掉名牌；字段不出现才表示“本包没提这件事”。
            val component = (metadata.value as? Optional<*>)?.orElse(null) as? Component
            customName = component?.let(ComponentText::render)
        }
        if (customName == current.customName) return
        updateEntity(packet.entityId) { it.copy(customName = customName) }
    }

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
     * 关闭窗口。
     *
     * 窗口 0 是玩家背包，服务端不会关闭它；收到就忽略——把背包当成“已关闭”会让
     * 后续查询凭空失败。
     */
    private fun closeContainer(windowId: Int) {
        if (windowId == Container.PLAYER_WINDOW_ID) return
        if (containers.remove(windowId) == null) return
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

    /** 单槽更新；未知窗口按需建容器（出生时的背包就可能先来单槽、后到整包）。 */
    private fun updateContainerSlot(windowId: Int, stateId: Int, slot: Int, item: ItemStack?) {
        val container = containerFor(windowId)
        container.stateId = stateId
        container.setSlot(slot, item?.toInventoryItem())
        emit(ProtocolEvent.ContainerUpdated(container.snapshot()))
    }

    private fun containerFor(windowId: Int): MutableContainer =
        containers.getOrPut(windowId) { MutableContainer(windowId) }

    private fun ItemStack.toInventoryItem(): InventoryItem = InventoryItem(itemId = id, count = amount)

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
         * 取 0–7 是 `Entity` 层共有的字段，下标 2 是其中唯一与显示相关的文本字段。
         * 生物层的字段从这个基址往后排（例如生命值是 9），而协议不告诉本端某个实体
         * 属于哪一层，所以本层只读所有实体都成立的下标 2。
         */
        const val CUSTOM_NAME_INDEX: Int = 2

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
