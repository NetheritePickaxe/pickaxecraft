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
import net.minestom.server.crypto.FilterMask
import net.minestom.server.crypto.LastSeenMessages
import net.minestom.server.crypto.MessageSignature
import net.minestom.server.crypto.SignedMessageBody
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.DeleteChatPacket
import net.minestom.server.network.packet.server.play.DisguisedChatPacket
import net.minestom.server.network.packet.server.play.PlayerChatMessagePacket
import net.minestom.server.network.packet.server.play.SystemChatPacket
import net.minestom.server.world.DimensionType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.ClientException
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.Position
import org.pickaxe.client.Session
import org.pickaxe.client.SessionState
import org.pickaxe.protocol.ProtocolVersion
import org.pickaxe.protocol.chat.ChatKind
import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.chat.ChatParameter
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import org.pickaxe.protocol.inventory.SlotClick
import org.pickaxe.protocol.ServerAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.BitSet
import java.util.UUID
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
    fun `聊天消息可读且能按编号删除`() = runBlocking {
        // 三个来源包字段各不相同：系统消息没有发送者，玩家消息带 UUID 与签名编号，
        // 伪装消息有名字但没有 UUID。这里逐项对账，重点是别把它们混成一种东西。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, CHAT_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(CHAT_USERNAME),
                    "服务端应已保存该玩家",
                )
                val typeId = chatTypeId(CHAT_FORMAT_KEY)

                // 系统消息；动作栏与聊天栏走同一个包，只差 overlay 位。
                player.sendMessage(Component.text(SYSTEM_CHAT_TEXT))
                player.sendPacket(SystemChatPacket(Component.text(ACTION_BAR_TEXT), true))

                // 玩家聊天：签名、消息编号、格式引用都在这一个包里。
                player.sendPacket(
                    PlayerChatMessagePacket(
                        12,
                        SENDER_UUID,
                        CHAT_SIGNATURE_ID,
                        MessageSignature(ByteArray(256) { SIGNATURE_FILL }),
                        SignedMessageBody.Packed(
                            PLAYER_CHAT_TEXT,
                            Instant.now(),
                            42L,
                            LastSeenMessages.Packed.EMPTY,
                        ),
                        null,
                        FilterMask(FilterMask.Type.PASS_THROUGH, BitSet()),
                        typeId,
                        Component.text(SENDER_NAME),
                        null,
                    ),
                )

                // 伪装聊天：服务端借某人的名义说话，没有 UUID 与签名。
                player.sendPacket(
                    DisguisedChatPacket(
                        Component.text(DISGUISED_TEXT),
                        typeId,
                        Component.text(DISGUISED_SENDER),
                        null,
                    ),
                )

                log.await { it is ClientEvent.ChatReceived && it.message.text == SYSTEM_CHAT_TEXT }
                val system = awaitChat(session) { it.text == SYSTEM_CHAT_TEXT }
                assertEquals(ChatKind.SYSTEM, system.kind)
                assertEquals(false, system.overlay, "普通系统消息显示在聊天栏")
                assertNull(system.sender, "系统消息没有发送者身份")

                val actionBar = awaitChat(session) { it.text == ACTION_BAR_TEXT }
                assertEquals(true, actionBar.overlay, "overlay 位应区分动作栏")

                val chat = awaitChat(session) { it.text == PLAYER_CHAT_TEXT }
                assertEquals(ChatKind.PLAYER, chat.kind)
                assertEquals(SENDER_UUID, chat.sender, "玩家消息带发送者 UUID")
                assertEquals(SENDER_NAME, chat.senderName)
                assertEquals(CHAT_SIGNATURE_ID, chat.signatureId, "带签名的消息应保留编号")
                assertNotNull(chat.timestamp, "玩家消息带签名时间")
                // 格式来自 chat_type 注册表：键与参数顺序都应与服务端一致。
                assertEquals(CHAT_FORMAT_KEY, chat.format?.key)
                assertEquals(
                    listOf(ChatParameter.SENDER, ChatParameter.CONTENT),
                    chat.format?.parameters,
                    "普通聊天的模板是「发送者 + 正文」",
                )

                val disguised = awaitChat(session) { it.text == DISGUISED_TEXT }
                assertEquals(ChatKind.DISGUISED, disguised.kind)
                assertEquals(DISGUISED_SENDER, disguised.senderName)
                assertNull(disguised.sender, "伪装消息只有名字，没有 UUID")
                assertEquals(CHAT_FORMAT_KEY, disguised.format?.key)

                assertEquals(
                    listOf(SYSTEM_CHAT_TEXT, ACTION_BAR_TEXT, PLAYER_CHAT_TEXT, DISGUISED_TEXT),
                    session.chat().map { it.text },
                    "聊天记录按到达顺序保存",
                )

                // 删除只对带编号的消息生效：服务端撤回后记录里不该再有它。
                player.sendPacket(DeleteChatPacket(MessageSignature.Packed(CHAT_SIGNATURE_ID, null)))
                log.await { it is ClientEvent.ChatDeleted && it.signatureId == CHAT_SIGNATURE_ID }
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.chat().none { it.signatureId == CHAT_SIGNATURE_ID }
                }
                assertEquals(
                    listOf(SYSTEM_CHAT_TEXT, ACTION_BAR_TEXT, DISGUISED_TEXT),
                    session.chat().map { it.text },
                    "只删除被引用的那条，其余保持原顺序",
                )
            } finally {
                log.close()
                session.close()
            }

            assertEquals(0, session.chatCount, "关闭后不应继续持有聊天记录")
        } finally {
            client.close()
        }
    }

    @Test
    fun `发送的聊天与命令服务端可见`() = runBlocking {
        // 上行有两条独立的证据链：服务端收到原文（PlayerChatEvent / 命令执行器），
        // 以及消息被广播回来。只断言回显会漏掉“服务端其实没收到、回显是本端插的”这种情况。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, SAY_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }

                session.sendChat(SAY_TEXT)
                assertTrue(
                    withContext(Dispatchers.IO) { awaitPlayerChat(SAY_USERNAME, SAY_TEXT, MILESTONE_TIMEOUT) },
                    "服务端应收到聊天原文：$SAY_TEXT",
                )

                // 广播回来的那条才是服务端认可的版本；本端不在本地插入副本。
                // Minestom 用系统消息广播玩家聊天，因此这里是 SYSTEM 而不是 PLAYER；
                // harness 把默认的可翻译模板换成了字面文本，回显才能与正文对账。
                val expectedEcho = "$SAY_USERNAME: $SAY_TEXT"
                log.await { it is ClientEvent.ChatReceived && it.message.text == expectedEcho }
                val echoed = awaitChat(session) { it.text == expectedEcho }
                assertEquals(ChatKind.SYSTEM, echoed.kind, "Minestom 以系统消息广播玩家聊天")

                // 命令：调用方写前导斜杠，包里不带；带空格的参数必须原样到达服务端。
                session.sendCommand("/$ECHO_COMMAND_NAME $COMMAND_ARGS")
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitCommand(SAY_USERNAME, "$ECHO_COMMAND_NAME $COMMAND_ARGS", MILESTONE_TIMEOUT)
                    },
                    "服务端应执行 echo 命令并看到完整参数",
                )
                val reply = awaitChat(session) { it.text == ECHO_COMMAND_REPLY }
                assertEquals(ChatKind.SYSTEM, reply.kind, "命令输出以系统消息回到本端")

                // 本地校验失败不应把会话打断：这些消息一个字节都不该发出去。
                assertFailsWith<IllegalArgumentException> { session.sendChat("x".repeat(257)) }
                assertFailsWith<IllegalArgumentException> { session.sendCommand("/") }
                assertEquals(SessionState.PLAY, session.state.value, "校验失败后会话应仍在 play")
            } finally {
                log.close()
                session.close()
            }

            // 关闭后发送被拒绝：连接已回收，写包只会进待发队列。
            val rejected = assertFailsWith<IllegalStateException> { session.sendChat("closed") }
            assertTrue(
                "CLOSED" in rejected.message.orEmpty() && "聊天" in rejected.message.orEmpty(),
                "拒绝原因应说明当前状态与操作，实际为 ${rejected.message}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `移动与转向服务端可见`() = runBlocking {
        // 上行的证据在服务端：PlayerMoveEvent 里带着客户端提交的目标坐标。只断言本地快照
        // 会漏掉“包根本没发出去”这种情况。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, MOVE_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
                val spawn = assertNotNull(session.world.value?.position, "出生后应已同步位置")
                assertEquals(GROUND_LEVEL, spawn.y, POSITION_TOLERANCE, "出生点应在平台顶面")

                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(MOVE_USERNAME),
                    "服务端应已保存该玩家",
                )

                // 一次坐标与朝向同时变化的移动：协议里应当是一个 pos_rot 包。
                val target = Position(
                    x = spawn.x + MOVE_DISTANCE,
                    y = spawn.y,
                    z = spawn.z + MOVE_DISTANCE,
                    yaw = 45f,
                    pitch = 10f,
                )
                session.moveTo(target)

                // 服务端接受移动时不回包，因此快照只能由本端声明推进。
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position == target }
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitPlayerMove(MOVE_USERNAME, MILESTONE_TIMEOUT) { move ->
                            abs(move.x - target.x) < POSITION_TOLERANCE &&
                                abs(move.z - target.z) < POSITION_TOLERANCE &&
                                move.yaw == target.yaw &&
                                move.pitch == target.pitch
                        }
                    },
                    "服务端应收到这次移动，实际观察到 ${observedMoves(MOVE_USERNAME)}",
                )
                assertEquals(target.x, player.position.x, POSITION_TOLERANCE, "服务端位置应与声明一致")
                assertEquals(target.z, player.position.z, POSITION_TOLERANCE, "服务端位置应与声明一致")

                // 只改朝向：协议里的 rot 包不带坐标，服务端必须保留原位置。
                session.look(LOOK_YAW, LOOK_PITCH)
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitPlayerMove(MOVE_USERNAME, MILESTONE_TIMEOUT) { move ->
                            move.yaw == LOOK_YAW && move.pitch == LOOK_PITCH
                        }
                    },
                    "服务端应收到这次转向，实际观察到 ${observedMoves(MOVE_USERNAME)}",
                )
                assertEquals(LOOK_YAW, player.position.yaw, "转向后服务端朝向应更新")
                assertEquals(target.x, player.position.x, POSITION_TOLERANCE, "仅转向不应挪动位置")

                // 服务端可以随时改写位置：传送到达后快照必须换成服务端的坐标，后续移动
                // 从新坐标接上（本地自说自话的坐标不能覆盖服务端的权威值）。
                player.teleport(Pos(CORRECTION_X, GROUND_LEVEL, CORRECTION_Z, 0f, 0f)).join()
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val position = session.world.value?.position
                    position != null && abs(position.x - CORRECTION_X) < POSITION_TOLERANCE
                }

                // 等确认包回到服务端，否则 Minestom 会把移动当成“传送未确认”丢掉。
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    abs(player.position.x - CORRECTION_X) < POSITION_TOLERANCE
                }
                session.moveTo(Position(CORRECTION_X, GROUND_LEVEL, CORRECTION_Z + MOVE_DISTANCE, LOOK_YAW, LOOK_PITCH))
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitPlayerMove(MOVE_USERNAME, MILESTONE_TIMEOUT) { move ->
                            abs(move.z - (CORRECTION_Z + MOVE_DISTANCE)) < POSITION_TOLERANCE
                        }
                    },
                    "传送之后应能从服务端给的新坐标继续移动，实际观察到 ${observedMoves(MOVE_USERNAME)}",
                )

                // 本地非法值一个字节都不该发出去，且会话不能被这个错误打断。
                assertFailsWith<IllegalArgumentException> {
                    session.moveTo(spawn.copy(x = Double.NaN))
                }
                assertEquals(SessionState.PLAY, session.state.value, "校验失败后会话应仍在 play")
            } finally {
                log.close()
                session.close()
            }

            val rejected = assertFailsWith<IllegalStateException> {
                session.moveTo(Position(0.0, 0.0, 0.0, 0f, 0f))
            }
            assertTrue(
                "CLOSED" in rejected.message.orEmpty() && "移动" in rejected.message.orEmpty(),
                "拒绝原因应说明当前状态与操作，实际为 ${rejected.message}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `背包与容器内容可从会话读到`() = runBlocking {
        // 背包有两套编号（窗口槽位与背包槽位），容器窗口又在自己内容后面接上玩家背包。
        // 这里按“服务端放的物品应出现在哪个槽位”逐格对账，而不是只看列表非空。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, BAG_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                // 出生时服务端就下发窗口 0 的整包内容：背包不是“打开才有”。
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.inventory() != null }
                assertTrue(assertNotNull(session.inventory()).isPlayerInventory, "窗口 0 就是玩家背包")

                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(BAG_USERNAME),
                    "服务端应已保存该玩家",
                )

                // Minestom 的槽位 0 是快捷栏第一格，协议窗口里是 36：中间那次换算是本层的责任。
                player.inventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 3))
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val item = session.inventory()?.slot(PlayerInventoryLayout.HOTBAR_FIRST)
                    item?.itemId == Material.DIAMOND.id() && item.count == 3
                }

                // 打开箱子：类型与标题来自打开屏幕包，内容随后整包下发。
                val chest = Inventory(InventoryType.CHEST_3_ROW, Component.text(CHEST_TITLE))
                chest.setItemStack(0, ItemStack.of(Material.GOLD_INGOT, 2))
                player.openInventory(chest)

                log.await { it is ClientEvent.ContainerOpened }
                val opened = log.snapshot().filterIsInstance<ClientEvent.ContainerOpened>().first()
                assertEquals(CHEST_TYPE, opened.type, "箱子的容器类型应原样来自服务端")
                assertEquals(CHEST_TITLE, opened.title, "标题应渲染成纯文本")

                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.container()?.slot(0)?.itemId == Material.GOLD_INGOT.id()
                }
                // 打开容器期间，玩家背包仍然从窗口 0 读：Minestom 的容器整包只带容器自己的
                // 27 格（原版会把后面的背包段一起发），因此把背包当成“容器窗口的一部分”
                // 在两种服务端上都不成立——窗口 0 才是玩家背包的权威来源。
                assertEquals(
                    Material.DIAMOND.id(),
                    session.inventory()?.slot(PlayerInventoryLayout.HOTBAR_FIRST)?.itemId,
                    "打开容器不应影响玩家背包",
                )

                // 单槽更新：箱子第 6 格放一个苹果，其他槽位必须保持不变。
                chest.setItemStack(5, ItemStack.of(Material.APPLE, 1))
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.container()?.slot(5)?.itemId == Material.APPLE.id()
                }
                assertEquals(Material.GOLD_INGOT.id(), session.container()?.slot(0)?.itemId, "单槽更新不应清掉别的格子")

                player.closeInventory()
                log.await { it is ClientEvent.ContainerClosed }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.container() == null }
                assertNotNull(session.inventory(), "关掉箱子不影响玩家自己的背包")

                // 手持槽位：服务端接受时不回包，本地立即推进；服务端字段是这次上行的证据。
                session.selectHotbarSlot(HELD_SLOT)
                log.await { it is ClientEvent.HeldSlotChanged && it.slot == HELD_SLOT }
                assertEquals(HELD_SLOT, session.heldSlot, "接受无回执，本地在发送后立即推进")
                awaitCondition(EVENT_TIMEOUT_MILLIS) { player.heldSlot.toInt() == HELD_SLOT }
                assertNull(session.heldItem(), "第 $HELD_SLOT 格此时是空的")

                player.inventory.setItemStack(HELD_SLOT, ItemStack.of(Material.STONE, 7))
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val held = session.heldItem()
                    held?.itemId == Material.STONE.id() && held.count == 7
                }

                assertFailsWith<IllegalArgumentException> {
                    session.selectHotbarSlot(PlayerInventoryLayout.HOTBAR_SIZE)
                }
                assertEquals(SessionState.PLAY, session.state.value, "校验失败后会话应仍在 play")
            } finally {
                log.close()
                session.close()
            }

            assertNull(session.inventory(), "关闭后不应继续持有背包")
            val rejected = assertFailsWith<IllegalStateException> { session.selectHotbarSlot(0) }
            assertTrue(
                "CLOSED" in rejected.message.orEmpty() && "快捷栏" in rejected.message.orEmpty(),
                "拒绝原因应说明当前状态与操作，实际为 ${rejected.message}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `生命、饥饿、经验与装备对客户端可见`() = runBlocking {
        // 三件事各走独立的路：生命/饥饿一个包、经验一个包、装备一个包。
        // 装备能读到，靠的是它是独立包——不像实体元数据那样依赖实体层级。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, STATE_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(STATE_USERNAME),
                    "服务端应已保存该玩家",
                )

                player.setHealth(STATE_HEALTH)
                player.setFood(STATE_FOOD)
                player.setFoodSaturation(STATE_SATURATION)

                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val status = session.status.value
                    status.health == STATE_HEALTH &&
                        status.food == STATE_FOOD &&
                        status.foodSaturation == STATE_SATURATION
                }
                log.await { it is ClientEvent.StatusChanged && it.status.health == STATE_HEALTH }

                player.setLevel(STATE_LEVEL)
                player.setExp(STATE_EXPERIENCE_BAR)
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val status = session.status.value
                    status.level == STATE_LEVEL && status.experienceBar == STATE_EXPERIENCE_BAR
                }
                // 经验包里的总量由服务端决定；Minestom 恒发 0，这里只断言“读得到”，不假设数值。
                assertNotNull(session.status.value.totalExperience, "经验包应带总量字段")
                assertEquals(STATE_HEALTH, session.status.value.health, "经验包不应影响生命值")

                // 装备：独立包，进实体快照；清空时槽位应当消失而不是留下“数量 0 的物品”。
                val zombie = EntityCreature(EntityType.ZOMBIE)
                zombie.setNoGravity(true)
                zombie.setInstance(instance, Pos(6.5, GROUND_LEVEL, 6.5)).join()
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.entity(zombie.entityId) != null }

                zombie.setEquipment(EquipmentSlot.HELMET, ItemStack.of(Material.GOLDEN_HELMET))
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.entity(zombie.entityId)?.equipment?.get(EQUIPMENT_HELMET)?.itemId ==
                        Material.GOLDEN_HELMET.id()
                }
                assertEquals(
                    1,
                    session.entity(zombie.entityId)?.equipment?.get(EQUIPMENT_HELMET)?.count,
                    "装备的物品堆数量应为 1",
                )

                zombie.setEquipment(EquipmentSlot.HELMET, ItemStack.AIR)
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.entity(zombie.entityId)?.equipment?.containsKey(EQUIPMENT_HELMET) == false
                }
                zombie.remove()
            } finally {
                log.close()
                session.close()
            }

            assertNull(session.status.value.health, "关闭后不应继续持有生命状态")
        } finally {
            client.close()
        }
    }

    @Test
    fun `容器点击能把物品搬到背包`() = runBlocking {
        // 点击不做本地预测：每一步都以“服务端事实 + 服务端回给客户端的内容”双侧对账。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, CLICK_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.inventory() != null }

                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(CLICK_USERNAME),
                    "服务端应已保存该玩家",
                )

                val chest = Inventory(InventoryType.CHEST_3_ROW, Component.text(CLICK_CHEST_TITLE))
                chest.setItemStack(CLICK_SLOT, ItemStack.of(Material.GOLD_INGOT, GOLD_COUNT))
                chest.setItemStack(SHIFT_SLOT, ItemStack.of(Material.APPLE, APPLE_COUNT))
                chest.setItemStack(THROW_SLOT, ItemStack.of(Material.DIRT, DIRT_COUNT))
                player.openInventory(chest)

                log.await { it is ClientEvent.ContainerOpened }
                val windowId = log.snapshot().filterIsInstance<ClientEvent.ContainerOpened>().first().windowId
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.container(windowId)?.slot(CLICK_SLOT)?.itemId == Material.GOLD_INGOT.id()
                }

                // ① 左键点箱子第 0 格：整堆到光标上。光标由服务端回给本端（本端不预测）。
                session.clickSlot(windowId, CLICK_SLOT, SlotClick.Pickup())
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.container(windowId)?.cursor?.itemId == Material.GOLD_INGOT.id()
                }
                assertEquals(GOLD_COUNT, session.container(windowId)?.cursor?.count, "左键拿整堆")
                assertNull(session.container(windowId)?.slot(CLICK_SLOT), "箱子该格应已空")
                assertEquals(windowId, session.activeWindowId, "开着箱子时活动窗口就是它")

                // ② 再左键点窗口里的快捷栏第一格（3 行箱子：27 格容器 + 27 格背包之后）：物品落进背包。
                session.clickSlot(windowId, CHEST_HOTBAR_FIRST_SLOT, SlotClick.Pickup())
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slot(PlayerInventoryLayout.HOTBAR_FIRST)?.itemId ==
                        Material.GOLD_INGOT.id()
                }
                assertEquals(
                    GOLD_COUNT,
                    session.inventory()?.slot(PlayerInventoryLayout.HOTBAR_FIRST)?.count,
                    "背包槽位（36）拿到整堆",
                )
                assertEquals(
                    GOLD_COUNT,
                    player.inventory.getItemStack(0).amount(),
                    "服务端背包第 0 格与客户端第 36 格是同一格",
                )
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.container(windowId)?.cursor == null }

                // ③ Shift 点击：服务端自己决定落到背包哪一格，本端只认结果。
                // 一次点击会改两个容器，服务端先发背包整包、再发容器整包，两边都要等，不能只看一半。
                session.clickSlot(windowId, SHIFT_SLOT, SlotClick.QuickMove())
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slots?.any { it?.itemId == Material.APPLE.id() } == true
                }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.container(windowId)?.slot(SHIFT_SLOT) == null }
                assertEquals(
                    APPLE_COUNT,
                    session.inventory()?.slots?.firstOrNull { it?.itemId == Material.APPLE.id() }?.count,
                    "Shift 点击整堆转移，数量不变",
                )
                assertTrue(chest.getItemStack(SHIFT_SLOT).isAir, "服务端箱子里这一格也应已空")

                // ④ 丢弃整堆：箱子这一格清空（掉落物实体不在本用例的断言范围）。
                session.clickSlot(windowId, THROW_SLOT, SlotClick.Throw(wholeStack = true))
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.container(windowId)?.slot(THROW_SLOT) == null }
                assertTrue(chest.getItemStack(THROW_SLOT).isAir, "服务端箱子里这一格应已空")

                // ⑤ 关闭窗口在发出时就生效（服务端接受时不回包），事件因此早于服务端处理到达；
                // 服务端侧的状态要等一个来回。
                session.closeContainer(windowId)
                log.await { it is ClientEvent.ContainerClosed && it.windowId == windowId }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { player.getOpenInventory() == null }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.container() == null }
                assertNotNull(session.inventory(), "关掉箱子不影响玩家自己的背包")
                assertEquals(Container.PLAYER_WINDOW_ID, session.activeWindowId)

                // 越界槽位与已经不存在的窗口都在本地被挡下，不会发出坏包。
                assertFailsWith<IllegalArgumentException> {
                    session.clickSlot(PlayerInventoryLayout.SIZE, SlotClick.Pickup())
                }
                assertFailsWith<IllegalArgumentException> { session.clickSlot(-1, SlotClick.Pickup()) }
                assertFailsWith<IllegalStateException> { session.clickSlot(windowId, 0, SlotClick.Pickup()) }
                assertEquals(SessionState.PLAY, session.state.value, "校验失败后会话应仍在 play")
            } finally {
                log.close()
                session.close()
            }
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

    /**
     * 从聊天记录里等一条满足条件的消息。
     *
     * 用历史而不是事件流取内容：事件只通知“来了一条”，记录才是可回看的结果，
     * 两者一致本身就是断言的一部分。
     */
    private suspend fun awaitChat(session: Session, predicate: (ChatMessage) -> Boolean): ChatMessage {
        var found: ChatMessage? = null
        awaitCondition(EVENT_TIMEOUT_MILLIS) {
            found = session.chat().lastOrNull(predicate)
            found != null
        }
        return assertNotNull(found)
    }

    /**
     * 服务端 `chat_type` 注册表里的格式 id（包内编码是 id + 1，0 表示内联定义）。
     *
     * 从服务端注册表反查而不是写死下标：条目顺序由服务端数据决定，写死会在
     * 换版本时变成“格式恒为空”的静默失败。
     */
    private fun chatTypeId(translationKey: String): Int {
        val registry = MinecraftServer.getChatTypeRegistry()
        val key = assertNotNull(
            registry.keys().firstOrNull { registry.get(it)?.chat()?.translationKey() == translationKey },
            "服务端 chat_type 注册表应包含 $translationKey",
        )
        return registry.getId(key) + 1
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

        /** 聊天用例：正文、名字与编号都由服务端侧写死，客户端只负责读出来。 */
        const val CHAT_USERNAME: String = "Chatter"
        const val SYSTEM_CHAT_TEXT: String = "服务器公告"
        const val ACTION_BAR_TEXT: String = "动作栏提示"
        const val PLAYER_CHAT_TEXT: String = "你好，世界"
        const val SENDER_NAME: String = "Alice"
        const val DISGUISED_TEXT: String = "* Bob 挥了挥手"
        const val DISGUISED_SENDER: String = "Bob"
        const val CHAT_FORMAT_KEY: String = "chat.type.text"
        const val CHAT_SIGNATURE_ID: Int = 7
        const val SIGNATURE_FILL: Byte = 0x5A
        val SENDER_UUID: UUID = UUID.fromString("6f2a1c39-0d4f-4a2b-9c8e-1b7d3f5a0e21")

        /** 聊天上行用例：正文与命令参数都由服务端侧核对，客户端只负责发。 */
        const val SAY_USERNAME: String = "SayBot"
        const val SAY_TEXT: String = "你好，服务端"
        const val COMMAND_ARGS: String = "hello world"

        /** 移动上行用例：目标坐标由客户端给定，服务端侧的 PlayerMoveEvent 是唯一证据。 */
        const val MOVE_USERNAME: String = "MoveBot"
        const val MOVE_DISTANCE: Double = 2.0
        const val LOOK_YAW: Float = 90f
        const val LOOK_PITCH: Float = 15f

        /** 服务端强制传送的目标：与出生点同区块，避开“移动进未加载区块”的分支。 */
        const val CORRECTION_X: Double = 5.5
        const val CORRECTION_Z: Double = 1.5

        /** 背包用例：容器类型与标题由服务端给出，槽位换算由客户端负责。 */
        const val BAG_USERNAME: String = "BagBot"
        const val CHEST_TITLE: String = "测试箱子"
        const val CHEST_TYPE: String = "GENERIC_9X3"
        const val HELD_SLOT: Int = 3

        /** 点击用例的账号、箱子标题与三个格子上的物品数量。 */
        const val CLICK_USERNAME: String = "ClickBot"
        const val CLICK_CHEST_TITLE: String = "点击箱子"
        const val CLICK_SLOT: Int = 0
        const val SHIFT_SLOT: Int = 1
        const val THROW_SLOT: Int = 2
        const val GOLD_COUNT: Int = 3
        const val APPLE_COUNT: Int = 2
        const val DIRT_COUNT: Int = 5

        /**
         * 3 行箱子窗口里快捷栏第一格的窗口槽位：27 格容器 + 27 格主背包，之后才是快捷栏。
         *
         * Minestom 的打开容器整包只带容器自己的槽位，所以这一格在快照里并不存在——
         * 但它仍然是合法点击目标，这也正是“容器窗口不校验槽位上限”的原因。
         */
        const val CHEST_HOTBAR_FIRST_SLOT: Int = 54

        /** 自身状态用例：数值由服务端设置，客户端只负责读出来。 */
        const val STATE_USERNAME: String = "StateBot"
        const val STATE_HEALTH: Float = 7.5f
        const val STATE_FOOD: Int = 12
        const val STATE_SATURATION: Float = 3.5f
        const val STATE_LEVEL: Int = 5
        const val STATE_EXPERIENCE_BAR: Float = 0.5f
        const val EQUIPMENT_HELMET: String = "HELMET"

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
