package org.pickaxe.testing.minestom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
import net.minestom.server.world.DimensionType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.ClientException
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.Session
import org.pickaxe.client.SessionState
import org.pickaxe.protocol.ProtocolVersion
import org.pickaxe.protocol.ServerAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 客户端公共 API 与真实 Minestom 的联调。
 *
 * 用例只依赖 `client-core`：不再出现 `ClientNetworkSession`、`Packet` 或 `ByteBuf`。
 * 这同时是适配层的验收条件——协议库一旦从编译类路径泄漏到上层，这里就写不出来。
 *
 * Minestom 是进程级单例，整个类共用一个服务端；`forkEvery = 1` 让每个测试类独占 JVM。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientSessionTest {

    private val port: Int = ServerSocket(0).use { it.localPort }
    private val address: ServerAddress = ServerAddress(DEFAULT_HOST, port)

    private lateinit var instance: InstanceContainer

    @BeforeAll
    fun startServer() {
        instance = startHarness(port)
        // 配置阶段就踢人：这条路径不需要玩家出生，能稳定覆盖“服务端主动断开”。
        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            if (event.player.username == KICKED_USERNAME) {
                event.player.kick(Component.text(KICK_MESSAGE))
            }
        }
    }

    @AfterAll
    fun stopServer() {
        stopHarness()
    }

    @Test
    fun `状态查询返回锁定的版本与实测延迟`() = runBlocking {
        MinecraftClient().use { client ->
            val status = client.status(address)

            assertEquals(ProtocolVersion.MINECRAFT_VERSION, status.versionName, "版本名应与锁定值一致")
            assertEquals(ProtocolVersion.PROTOCOL_VERSION, status.protocolVersion, "协议号应与锁定值一致")
            assertTrue(status.latencyMillis >= 0, "Ping 往返耗时不应为负")
            assertTrue(status.description.isNotBlank(), "MOTD 应已渲染成非空文本")
        }
    }

    @Test
    fun `离线登录进入 play 并收到注册表`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, "LoginBot")
            val log = EventLog(this, session)
            try {
                assertEquals(SessionState.PLAY, session.state.value, "连接成功应停在上层可见的 play 状态")
                assertTrue(session.isOpen(), "进入 play 后连接应保持")

                log.await { it is ClientEvent.StateChanged && it.state == SessionState.PLAY }
                val states = log.snapshot()
                    .filterIsInstance<ClientEvent.StateChanged>()
                    .map { it.state }
                assertTrue(SessionState.CONFIGURATION in states, "应观察到配置阶段，实际为 $states")

                log.await { it is ClientEvent.RegistryReceived }
                val entryCount = log.snapshot()
                    .filterIsInstance<ClientEvent.RegistryReceived>()
                    .sumOf { it.entryCount }
                assertTrue(entryCount > 0, "配置阶段应收到非空的注册表数据")
            } finally {
                log.close()
                session.close()
            }

            assertEquals(SessionState.CLOSED, session.state.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun `登录后完成出生并进入世界`() = runBlocking {
        val username = "SpawnBot"
        val client = MinecraftClient()
        try {
            val session = client.connect(address, username)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.JoinedWorld }
                val joined = log.snapshot().filterIsInstance<ClientEvent.JoinedWorld>().first()
                assertEquals("minecraft:overworld", joined.dimension, "应进入服务端的默认维度")
                assertTrue(joined.entityId > 0, "服务端应分配实体 id")

                log.await { it is ClientEvent.PositionUpdated }
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }

                // 自动回送的传送确认与区块批次确认，必须在服务端侧看到结果：
                // SPAWNED 来自 PlayerSpawnEvent，LOADED 来自客户端上行 player_loaded。
                assertTrue(
                    withContext(Dispatchers.IO) { awaitMilestone(username, PlayerMilestone.SPAWNED, MILESTONE_TIMEOUT) },
                    "服务端应完成出生：$username 未触发 PlayerSpawnEvent",
                )
                assertTrue(
                    withContext(Dispatchers.IO) { awaitMilestone(username, PlayerMilestone.LOADED, MILESTONE_TIMEOUT) },
                    "服务端应收到 player_loaded：$username 未触发 PlayerLoadedEvent",
                )

                val world = assertNotNull(session.world.value, "进入世界后应有会话级世界状态")
                assertEquals(joined.entityId, world.entityId, "世界状态与事件应一致")
                assertEquals("minecraft:overworld", world.dimension)
                assertNotNull(world.position, "出生后应已同步位置")
                assertTrue(world.chunksLoaded > 0, "出生后应收到区块，实际 ${world.chunksLoaded}")
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `出生后读到的方块状态与服务端一致`() = runBlocking {
        // 服务端侧放一个可辨认的方块，客户端读到的必须是同一个全局状态 id。
        // 这条断言把“解出来的世界”和“服务端真实世界”绑在一起，而不是只验证结构非空。
        val markerX = 1
        val markerY = 41
        val markerZ = 2
        instance.setBlock(markerX, markerY, markerZ, Block.GOLD_BLOCK)

        val client = MinecraftClient()
        try {
            val session = client.connect(address, "ChunkBot")
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.chunk(0, 0) != null }

                val column = assertNotNull(session.chunk(0, 0), "出生点所在区块应已解码")
                assertEquals(0, column.x)
                assertEquals(0, column.z)
                assertEquals(OVERWORLD_MIN_Y, column.minY, "主世界最低层应为 $OVERWORLD_MIN_Y")
                assertEquals(OVERWORLD_SECTIONS, column.sectionCount, "384 格高应拆成 $OVERWORLD_SECTIONS 个 section")
                assertEquals(OVERWORLD_MIN_Y + OVERWORLD_HEIGHT, column.maxY)

                assertEquals(
                    instance.getBlock(markerX, markerY, markerZ).stateId(),
                    session.blockStateAt(markerX, markerY, markerZ),
                    "客户端读到的方块状态应与服务端一致",
                )
                assertEquals(
                    instance.getBlock(0, 39, 0).stateId(),
                    session.blockStateAt(0, 39, 0),
                    "地面方块也应对得上",
                )
                assertEquals(
                    instance.getBlock(markerX, 200, markerZ).stateId(),
                    session.blockStateAt(markerX, 200, markerZ),
                    "空中应为空气",
                )
                assertNull(session.blockStateAt(4000, 40, 4000), "未加载的区块应返回 null")

                val biome = assertNotNull(session.biomeAt(markerX, markerY, markerZ), "应能读到生物群系")
                val biomeCount = assertNotNull(session.registries.value["minecraft:worldgen/biome"])
                assertTrue(biome in 0 until biomeCount, "生物群系 id $biome 应落在注册表 $biomeCount 项之内")
            } finally {
                log.close()
                session.close()
            }

            assertNull(session.blockStateAt(markerX, markerY, markerZ), "关闭后不应继续持有区块")
        } finally {
            client.close()
        }
    }

    @Test
    fun `实体的生成、移动与移除对客户端可见`() = runBlocking {
        val client = MinecraftClient()
        var removedId = 0
        try {
            val session = client.connect(address, "EntityBot")
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }

                // 静止的僵尸：位置与朝向都由构造参数决定，便于逐项对账。
                val zombie = EntityCreature(EntityType.ZOMBIE)
                zombie.setNoGravity(true)
                zombie.setInstance(instance, Pos(ZOMBIE_START_X, GROUND_LEVEL, 3.5)).join()
                removedId = zombie.entityId

                log.await { it is ClientEvent.EntitySpawned && it.entity.id == removedId }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.entity(removedId) != null }

                val spawned = assertNotNull(session.entity(removedId), "实体生成后应可在会话中查到")
                assertEquals("ZOMBIE", spawned.type, "实体类型应来自协议枚举")
                assertEquals(zombie.uuid, spawned.uuid, "UUID 应与服务端一致")
                assertEquals(zombie.position.x, spawned.x, POSITION_TOLERANCE, "生成位置 x 应一致")
                assertEquals(zombie.position.y, spawned.y, POSITION_TOLERANCE, "生成位置 y 应一致")
                assertEquals(zombie.position.z, spawned.z, POSITION_TOLERANCE, "生成位置 z 应一致")
                assertTrue(removedId in session.entities().keys, "快照应包含该实体")

                // 绝对位移：服务端的传送带完整坐标，客户端直接替换。
                zombie.teleport(Pos(TELEPORT_X, GROUND_LEVEL, 3.5, 90f, 20f)).join()
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val current = session.entity(removedId)
                    current != null && current.x == TELEPORT_X && current.yaw == 90f && current.pitch == 20f
                }

                // 相对位移：服务端每 tick 只发 1/4096 格的增量（short 编码），
                // 客户端必须自己叠加。漏加或重复叠加都会在下面的对账里暴露。
                zombie.setVelocity(Vec(MOVE_SPEED, 0.0, 0.0))
                awaitCondition(EVENT_TIMEOUT_MILLIS) { zombie.position.x > TELEPORT_X + 1.0 }
                zombie.setVelocity(Vec.ZERO)

                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val current = session.entity(removedId)
                    current != null && abs(current.x - zombie.position.x) < POSITION_TOLERANCE
                }

                // 移除：事件与缓存都要跟上。
                zombie.remove()
                log.await { it is ClientEvent.EntityRemoved && removedId in it.ids }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.entity(removedId) == null }
                assertTrue(removedId !in session.entities().keys, "移除后快照里不应再有该实体")
            } finally {
                log.close()
                session.close()
            }

            assertEquals(0, session.entityCount, "关闭后不应继续持有实体")
        } finally {
            client.close()
        }
    }

    @Test
    fun `重生切换维度后世界状态与缓存一起更新`() = runBlocking {
        // 下界：minY=0、256 格高，与主世界的 -64/384 完全不同，能直接暴露“仍按旧维度解码”。
        val nether = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.THE_NETHER)
        nether.setGenerator(Generator { unit -> unit.modifier().fillHeight(0, GROUND_LEVEL.toInt(), Block.NETHERRACK) })

        val client = MinecraftClient()
        try {
            val session = client.connect(address, "RespawnBot")
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.chunk(0, 0) != null }
                assertEquals(OVERWORLD_MIN_Y, session.chunk(0, 0)?.minY, "出生维度应是主世界")

                // 旧维度的实体不应跨越重生。
                val zombie = EntityCreature(EntityType.ZOMBIE)
                zombie.setNoGravity(true)
                zombie.setInstance(instance, Pos(2.5, GROUND_LEVEL, 2.5)).join()
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.entity(zombie.entityId) != null }

                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername("RespawnBot"),
                    "服务端应已保存该玩家",
                )
                player.setInstance(nether, Pos(0.5, GROUND_LEVEL, 0.5)).join()

                log.await { it is ClientEvent.Respawned }
                val respawned = log.snapshot().filterIsInstance<ClientEvent.Respawned>().first()
                assertEquals(NETHER_DIMENSION, respawned.dimension, "事件应报告新维度")
                assertEquals(NETHER_DIMENSION, session.world.value?.dimension, "世界快照应换到新维度")

                // 新维度用下界高度重新解码：minY=0、16 个 section，方块也换成下界岩。
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.chunk(0, 0)?.minY == NETHER_MIN_Y }
                assertEquals(NETHER_SECTIONS, session.chunk(0, 0)?.sectionCount, "256 格高应拆成 $NETHER_SECTIONS 个 section")
                assertEquals(
                    nether.getBlock(0, 39, 0).stateId(),
                    session.blockStateAt(0, 39, 0),
                    "新维度的方块应与该维度服务端一致",
                )
                assertNotEquals(
                    instance.getBlock(0, 39, 0).stateId(),
                    session.blockStateAt(0, 39, 0),
                    "同一坐标在主世界是石头：地板方块确实换了",
                )

                // 实体与位置都要跟着新维度重置：旧实体不再存在，位置由新的同步包给出。
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.entity(zombie.entityId) == null }
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val position = session.world.value?.position
                    position != null && abs(position.y - GROUND_LEVEL) < 1.0
                }
                zombie.remove()
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `玩家列表与自定义名称可从会话查到`() = runBlocking {
        // 玩家列表回答“谁是谁”：实体包只带 UUID，名字必须从列表里关联出来。
        // 因此这里要两个会话——被观察者自己不会收到自己的实体生成包。
        val observer = MinecraftClient()
        val subject = MinecraftClient()
        try {
            val watcherSession = observer.connect(address, WATCHER_USERNAME)
            val log = EventLog(this, watcherSession)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                // 自己的条目也会下发：玩家列表是“服务器上都有谁”，不只是别人。
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    watcherSession.players().values.any { entry -> entry.name == WATCHER_USERNAME }
                }

                val subjectSession = subject.connect(address, SUBJECT_USERNAME)
                try {
                    assertTrue(
                        withContext(Dispatchers.IO) {
                            awaitMilestone(SUBJECT_USERNAME, PlayerMilestone.SPAWNED, MILESTONE_TIMEOUT)
                        },
                        "服务端应先完成 $SUBJECT_USERNAME 的出生",
                    )
                    var online: Player? = null
                    awaitCondition(EVENT_TIMEOUT_MILLIS) {
                        online = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(SUBJECT_USERNAME)
                        online != null
                    }
                    val serverPlayer = assertNotNull(online, "服务端应已保存该玩家")

                    log.await {
                        it is ClientEvent.PlayerListChanged && it.updated.any { entry -> entry.name == SUBJECT_USERNAME }
                    }
                    awaitCondition(EVENT_TIMEOUT_MILLIS) {
                        watcherSession.player(serverPlayer.uuid)?.name == SUBJECT_USERNAME
                    }

                    // 关联的核心：实体 id → UUID → 名字。
                    awaitCondition(EVENT_TIMEOUT_MILLIS) { watcherSession.entity(serverPlayer.entityId) != null }
                    assertEquals(
                        SUBJECT_USERNAME,
                        watcherSession.nameOf(serverPlayer.entityId),
                        "玩家实体的名字应从玩家列表关联出来",
                    )
                    assertEquals(
                        serverPlayer.uuid,
                        watcherSession.entity(serverPlayer.entityId)?.uuid,
                        "实体快照里的 UUID 应与玩家列表一致",
                    )

                    // 非玩家实体没有名字可关联，自定义名称才是它的名字来源。
                    val zombie = EntityCreature(EntityType.ZOMBIE)
                    zombie.setNoGravity(true)
                    zombie.set(DataComponents.CUSTOM_NAME, Component.text(ZOMBIE_NAME))
                    zombie.setInstance(instance, Pos(4.5, GROUND_LEVEL, 4.5)).join()

                    awaitCondition(EVENT_TIMEOUT_MILLIS) {
                        watcherSession.entity(zombie.entityId)?.customName == ZOMBIE_NAME
                    }
                    assertNull(watcherSession.nameOf(zombie.entityId), "非玩家实体不应解析出玩家名")

                    // 改名走的是元数据增量包，与本层关心的字段不一致时不该产生事件。
                    zombie.set(DataComponents.CUSTOM_NAME, Component.text(RENAMED_ZOMBIE_NAME))
                    awaitCondition(EVENT_TIMEOUT_MILLIS) {
                        watcherSession.entity(zombie.entityId)?.customName == RENAMED_ZOMBIE_NAME
                    }
                    zombie.remove()
                } finally {
                    subjectSession.close()
                }
            } finally {
                log.close()
                watcherSession.close()
            }

            assertEquals(0, watcherSession.playerCount, "关闭后不应继续持有玩家列表")
        } finally {
            observer.close()
            subject.close()
        }
    }

    @Test
    fun `两个客户端互不影响`() = runBlocking {
        val first = MinecraftClient()
        val second = MinecraftClient()
        try {
            val alpha = first.connect(address, "Alpha")
            val beta = second.connect(address, "Beta")
            try {
                assertEquals("Alpha", alpha.username)
                assertEquals("Beta", beta.username)
                assertEquals(SessionState.PLAY, alpha.state.value)
                assertEquals(SessionState.PLAY, beta.state.value)

                alpha.close()

                assertEquals(SessionState.CLOSED, alpha.state.value)
                assertTrue(beta.isOpen(), "关闭一个会话不应影响另一个")
            } finally {
                alpha.close()
                beta.close()
            }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `重复关闭无副作用且网络线程被回收`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, "Closer")
            session.close()
            session.close()

            assertEquals(SessionState.CLOSED, session.state.value)
            awaitCondition(THREAD_RELEASE_TIMEOUT_MILLIS) { sessionThreads().isEmpty() }
        } finally {
            client.close()
        }
    }

    @Test
    fun `连接失败给出可区分的错误并允许再次连接`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val client = MinecraftClient(ClientOptions(connectTimeoutMillis = 2_000))
        try {
            val failure = assertFailsWith<ClientException.ConnectFailed> {
                client.connect(ServerAddress(DEFAULT_HOST, closedPort), "Nobody")
            }
            assertTrue(failure.message?.isNotBlank() == true, "连接失败应带上下文")

            // 失败不能留下“已有活动会话”的残留状态。
            val session = client.connect(address, "AfterFailure")
            try {
                assertEquals(SessionState.PLAY, session.state.value)
            } finally {
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `服务端主动断开被报告为服务端原因`() = runBlocking {
        val client = MinecraftClient()
        try {
            val failure = assertFailsWith<ClientException.ServerDisconnected> {
                client.connect(address, KICKED_USERNAME)
            }
            assertTrue(
                KICK_MESSAGE in failure.reason,
                "原因应包含服务端下发的文本，实际为 ${failure.reason}",
            )

            // 被踢的会话不能影响后续连接。
            val session = client.connect(address, "AfterKick")
            session.close()
        } finally {
            client.close()
        }
    }

    @Test
    fun `事件缓冲写满时终止会话而不是静默丢弃`() = runBlocking {
        val client = MinecraftClient(ClientOptions(eventBufferCapacity = 1))
        try {
            // 不消费事件流：缓冲很快写满，按 M1 策略应终止会话。
            val failure = assertFailsWith<ClientException.EventOverflow> { client.connect(address, "SlowConsumer") }
            assertEquals(1, failure.capacity, "错误应带上实际生效的缓冲容量")
        } finally {
            client.close()
        }
    }

    private fun Session.isOpen(): Boolean = state.value != SessionState.CLOSED

    private fun sessionThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(SESSION_THREAD_PREFIX) }

    private suspend fun awaitCondition(timeoutMillis: Long, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** 后台收集会话事件。事件流是单消费者，因此只能由它持有。 */
    private inner class EventLog(scope: CoroutineScope, session: Session) {

        private val collected = CopyOnWriteArrayList<ClientEvent>()

        private val job = scope.launch { session.events.collect { collected += it } }

        fun snapshot(): List<ClientEvent> = collected.toList()

        suspend fun await(predicate: (ClientEvent) -> Boolean) {
            withTimeout(EVENT_TIMEOUT_MILLIS) {
                while (collected.none(predicate)) {
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        }

        fun close() {
            job.cancel()
        }
    }

    private companion object {
        const val KICKED_USERNAME: String = "Kicked"
        const val WATCHER_USERNAME: String = "Watcher"
        const val SUBJECT_USERNAME: String = "Subject"
        const val ZOMBIE_NAME: String = "名牌僵尸"
        const val RENAMED_ZOMBIE_NAME: String = "改名僵尸"
        const val KICK_MESSAGE: String = "测试踢出"
        const val EVENT_TIMEOUT_MILLIS: Long = 10_000
        val MILESTONE_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val THREAD_RELEASE_TIMEOUT_MILLIS: Long = 5_000
        const val POLL_INTERVAL_MILLIS: Long = 10
        const val SESSION_THREAD_PREFIX: String = "pickaxe-mcpl"

        /** Minestom 默认主世界的垂直范围，harness 没有自定义维度类型。 */
        const val OVERWORLD_MIN_Y: Int = -64
        const val OVERWORLD_HEIGHT: Int = 384
        const val OVERWORLD_SECTIONS: Int = OVERWORLD_HEIGHT / 16

        /** 重生用例切到的维度：高度 256、最低层 0，与主世界完全不同。 */
        const val NETHER_DIMENSION: String = "minecraft:the_nether"
        const val NETHER_MIN_Y: Int = 0
        const val NETHER_SECTIONS: Int = 256 / 16

        /** 石头平台的顶面高度，玩家与实体都站在这一层。 */
        const val GROUND_LEVEL: Double = 40.0

        /** 僵尸的生成位置与传送目标；都在出生点所在区块内，不触发额外区块加载。 */
        const val ZOMBIE_START_X: Double = 3.5
        const val TELEPORT_X: Double = 6.5

        /** 水平速度（格/秒），足以在几百毫秒内跨过一整格。 */
        const val MOVE_SPEED: Double = 4.0

        /**
         * 位置对账容差。
         *
         * 相对移动以 1/4096 格编码，叠加多步后会留下少量量化误差；这个量级远小于
         * 一格，足够区分“真的对上了”和“漏加/重复加了一次增量”。
         */
        const val POSITION_TOLERANCE: Double = 0.02
    }
}
