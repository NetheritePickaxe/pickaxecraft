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
import org.pickaxe.protocol.world.ChunkColumn
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
            // 区块属于本次会话；不清理会让已关闭的会话继续持有整个出生点视野。
            chunks.clear()
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

    /**
     * 按世界坐标读取生物群系 id（`minecraft:worldgen/biome` 注册表下标）；
     * 所在区块未加载时返回 null。
     */
    public fun biomeAt(x: Int, y: Int, z: Int): Int? =
        chunk(x shr 4, z shr 4)?.biomeAt(x, y, z)

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

        is ProtocolEvent.ChunksReceived -> {
            mutableWorld.update { it.orEmpty().copy(chunksLoaded = event.total) }
            emit(ClientEvent.ChunksReceived(event.chunks, event.total))
            true
        }

        // 区块与卸载在 onProtocolEvent 里已写入缓存，不经过 Actor；列出来是为了让
        // 这里的穷尽分支跟上协议事件的增长——新增事件没有处理策略时应当编译失败。
        is ProtocolEvent.ChunkLoaded, is ProtocolEvent.ChunkUnloaded -> true

        // 登录会话不会收到状态查询结果。
        is ProtocolEvent.StatusReceived -> true

        is ProtocolEvent.Closed -> {
            recordClose(event.reason)
            false
        }
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
