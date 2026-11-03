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
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    @BeforeAll
    fun startServer() {
        startHarness(port)
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
        const val KICK_MESSAGE: String = "测试踢出"
        const val EVENT_TIMEOUT_MILLIS: Long = 10_000
        val MILESTONE_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val THREAD_RELEASE_TIMEOUT_MILLIS: Long = 5_000
        const val POLL_INTERVAL_MILLIS: Long = 10
        const val SESSION_THREAD_PREFIX: String = "pickaxe-mcpl"
    }
}
