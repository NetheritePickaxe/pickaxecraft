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
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo
import org.geysermc.mcprotocollib.protocol.data.status.handler.ServerInfoHandler
import org.geysermc.mcprotocollib.protocol.data.status.handler.ServerPingTimeHandler
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRotateHeadPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket
import org.pickaxe.protocol.ProtocolCloseReason
import org.pickaxe.protocol.ProtocolEvent
import org.pickaxe.protocol.ProtocolFailureKind
import org.pickaxe.protocol.ProtocolListener
import org.pickaxe.protocol.ProtocolPhase
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.ServerStatus
import org.pickaxe.protocol.world.ChunkColumn
import org.pickaxe.protocol.world.TrackedEntity
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    // 以下状态只在连接自己的网络线程上读写，无需同步。
    private var positionX: Double = 0.0
    private var positionY: Double = 0.0
    private var positionZ: Double = 0.0
    private var yaw: Float = 0f
    private var pitch: Float = 0f
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

    /** 配置阶段收到的维度类型表；下标即 Login 包里的维度 id。 */
    private var dimensions: List<DimensionShape> = emptyList()

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
        positionX = if (PositionElement.X in relative) positionX + packet.position.x else packet.position.x
        positionY = if (PositionElement.Y in relative) positionY + packet.position.y else packet.position.y
        positionZ = if (PositionElement.Z in relative) positionZ + packet.position.z else packet.position.z
        yaw = if (PositionElement.Y_ROT in relative) yaw + packet.yRot else packet.yRot
        pitch = if (PositionElement.X_ROT in relative) pitch + packet.xRot else packet.xRot

        send(ServerboundAcceptTeleportationPacket(packet.id))
        emit(ProtocolEvent.PositionUpdated(positionX, positionY, positionZ, yaw, pitch))
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
    }
}
