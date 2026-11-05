package org.pickaxe.testing.minestom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
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
import org.pickaxe.client.CompletionResult
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.Position
import org.pickaxe.client.Session
import org.pickaxe.client.SessionState
import org.pickaxe.protocol.ProtocolVersion
import org.pickaxe.protocol.chat.ChatKind
import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.chat.ChatParameter
import org.pickaxe.protocol.command.CommandArgumentProperty
import org.pickaxe.protocol.command.CommandArgumentType
import org.pickaxe.protocol.command.CommandNodeKind
import org.pickaxe.protocol.command.CommandTextMode
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import org.pickaxe.protocol.inventory.SlotClick
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition
import org.pickaxe.protocol.world.HeightmapType
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
import kotlin.test.assertFalse
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
    fun `区块的光照、高度图与方块实体都可读`() = runBlocking {
        // 出生前存在的方块实体随整列区块下发；出生后再放的走增量包。两条路径都要覆盖。
        val initialChest = BlockPosition(INITIAL_CHEST_X, GROUND_LEVEL.toInt(), INITIAL_CHEST_Z)
        instance.setBlock(initialChest.x, initialChest.y, initialChest.z, Block.CHEST)
        // Minestom 在区块发出时还没算完光照（发的是全 0），先让它把出生点这一层算好。
        assertTrue(
            withContext(Dispatchers.IO) { pushLight(instance, 0, GROUND_LEVEL.toInt() + 2, 0) },
            "出生点所在区块应已加载，才能预计算光照",
        )

        val client = MinecraftClient()
        try {
            val session = client.connect(address, ENVIRONMENT_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                // 高度图：石头平面填到 y=39，第一格空气是 y=40（= GROUND_LEVEL）。
                // 不查出生点那一列：其它用例会在那里挖/放方块，而高度图是整列下发时的快照。
                assertEquals(
                    GROUND_LEVEL.toInt(),
                    session.heightAt(HeightmapType.MOTION_BLOCKING, HEIGHTMAP_X, HEIGHTMAP_Z),
                    "高度图应给出该列第一格空气的世界 Y",
                )

                // 光照：露天满 15、石头内部 0；查询不到数据才是 null。
                assertEquals(
                    15,
                    session.skyLightAt(0, GROUND_LEVEL.toInt() + 2, 0),
                    "露天格子的天空光应为 15",
                )
                assertEquals(0, session.skyLightAt(0, 20, 0), "石头内部不应有天空光")
                assertEquals(
                    0,
                    session.blockLightAt(0, GROUND_LEVEL.toInt() + 2, 0),
                    "没有光源时方块光应为 0",
                )
                assertNull(session.skyLightAt(0, 400, 0), "超出本列高度应返回 null 而不是 0")

                // 整列带下来的方块实体：协议里它是 level_chunk_with_light 的一部分。
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.blockEntity(initialChest)?.type == "minecraft:chest"
                }

                // 增量路径：服务端先发 block_update，再发 block_entity_data。
                val liveChest = BlockPosition(LIVE_CHEST_X, GROUND_LEVEL.toInt(), LIVE_CHEST_Z)
                instance.setBlock(liveChest.x, liveChest.y, liveChest.z, Block.CHEST)
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.blockEntity(liveChest)?.type == "minecraft:chest"
                }
                // 缓存先写、事件后投递：事件查找必须等待式，查一次快照会在负载下偶发失败。
                log.await { it is ClientEvent.BlockEntityChanged && it.entity.position == liveChest }

                // 换成石头：服务端不发“删除方块实体”的包，缓存必须由方块变更清掉。
                instance.setBlock(liveChest.x, liveChest.y, liveChest.z, Block.STONE)
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.blockEntity(liveChest) == null }

                // 光照增量：火把点亮后重算并下发 update_light，方块光应从 0 变成非 0。
                val torch = BlockPosition(TORCH_X, GROUND_LEVEL.toInt(), TORCH_Z)
                instance.setBlock(torch.x, torch.y, torch.z, Block.TORCH)
                assertTrue(
                    withContext(Dispatchers.IO) { pushLight(instance, torch.x, torch.y, torch.z) },
                    "火把所在区块应已加载",
                )
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val level = session.blockLightAt(torch.x, torch.y, torch.z)
                    level != null && level > 0
                }
                log.await {
                    it is ClientEvent.ChunkLightUpdated &&
                        it.chunkX == (torch.x shr 4) && it.chunkZ == (torch.z shr 4)
                }
            } finally {
                log.close()
                session.close()
            }
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
    fun `命令树与补全由服务端给出`() = runBlocking {
        // 命令树与候选都只能由服务端决定：前者随 Player#spawn 下发，后者由服务端按权限与
        // 当前输入算。客户端能证明的是“包到了、形状对、区间落在该换的那一段上”，
        // 服务端侧另有补全请求原文作为独立证据。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, COMMAND_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }

                log.await { it is ClientEvent.CommandTreeReceived }
                val tree = withTimeout(EVENT_TIMEOUT_MILLIS) { session.commands.first { it != null } }!!
                assertTrue(
                    SUGGEST_COMMAND_NAME in tree.topLevelLiterals,
                    "harness 注册的命令应出现在树里，实际为 ${tree.topLevelLiterals}",
                )

                // 逐层走一遍：命令名下面是唯一的参数节点，类型与文本模式都要对得上。
                val command = tree.childrenOf(tree.rootIndex).first { it.name == SUGGEST_COMMAND_NAME }
                assertTrue(command.executable, "带默认执行器的命令应当可执行")
                val argument = tree.childrenOf(command.index).single()
                assertEquals(CommandNodeKind.ARGUMENT, argument.kind)
                assertEquals(CommandArgumentType.STRING, argument.argumentType)
                assertEquals(
                    CommandArgumentProperty.Text(CommandTextMode.SINGLE_WORD),
                    argument.property,
                    "ArgumentType.Word 在协议里是 string + 单词模式",
                )

                // 补全：候选由服务端的回调按前缀过滤，所以只有 red 回来。
                val text = "/$SUGGEST_COMMAND_NAME r"
                val result = session.complete(text)
                assertTrue(result is CompletionResult.Suggestions, "服务端应回答补全，实际为 $result")
                val suggestions = (result as CompletionResult.Suggestions).suggestions
                assertEquals(listOf("red"), suggestions.matches, "候选由服务端过滤，不该是全部三个")

                // 区间说的就是“把这几个字符换掉”：取出来必须正好是已输入的前缀。
                val replaced = text.substring(suggestions.start, suggestions.start + suggestions.length)
                assertEquals("r", replaced, "替换区间应指向最后一个词")
                assertEquals("/$SUGGEST_COMMAND_NAME red", suggestions.applyTo(text), "套回原文应得到完整命令")

                // 事件与挂起 API 是同一份数据；不想用挂起接口的调用方可以只看事件。
                log.await { it is ClientEvent.CommandSuggestionsReceived }

                // 服务端确实收到了这次请求：包里的原文就是证据（补全包本身没有其他回执）。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitPlayerPacket(COMMAND_USERNAME, "suggest.$text", MILESTONE_TIMEOUT)
                    },
                    "服务端应收到补全请求：$text",
                )

                // 选中的候选可以直接下发执行，服务端侧按真实命令跑一遍。
                session.sendCommand("/$SUGGEST_COMMAND_NAME red")
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitCommand(COMMAND_USERNAME, "/$SUGGEST_COMMAND_NAME red", MILESTONE_TIMEOUT)
                    },
                    "补全出来的命令应能在服务端执行",
                )

                // 没有补全回调的命令服务端**不回包**：这是“没有答案”，不是“没有候选”。
                // 超时刻意压短，避免把用例拖成十秒。
                assertEquals(
                    CompletionResult.NoAnswer,
                    session.complete("/$ECHO_COMMAND_NAME hi", timeoutMillis = NO_ANSWER_TIMEOUT_MILLIS),
                    "服务端不回包时应报告 NoAnswer",
                )
            } finally {
                log.close()
                session.close()
            }
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
    fun `物品键名与自定义名可读`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, ITEM_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
                assertTrue(
                    session.inventory()?.slots?.all { it == null } == true,
                    "测试开始时背包应为空",
                )

                // 服务端塞物品：Minestom 会把背包槽更新下发给客户端。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        giveNamedItem(ITEM_USERNAME, Material.STONE, 16, NAMED_ITEM_NAME) &&
                            giveNamedItem(ITEM_USERNAME, Material.DIAMOND, 1, null)
                    },
                    "服务端应能塞进物品",
                )

                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    val slots = session.inventory()?.slots.orEmpty()
                    slots.any { it?.key == "minecraft:stone" && it.count == 16 && it.customName == NAMED_ITEM_NAME } &&
                        slots.any { it?.key == "minecraft:diamond" && it.customName == null }
                }
                val stone = session.inventory()?.slots.orEmpty()
                    .first { it?.key == "minecraft:stone" }
                assertEquals(NAMED_ITEM_NAME, stone?.customName, "custom_name 组件应穿过协议")
                val diamond = session.inventory()?.slots.orEmpty()
                    .first { it?.key == "minecraft:diamond" }
                assertNull(diamond?.customName, "没有组件就没有名字，不应伪造空串")
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `物品组件可读且附魔键名来自动态注册表`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, COMPONENT_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
                assertTrue(
                    withContext(Dispatchers.IO) { giveComponentItem(COMPONENT_USERNAME) },
                    "服务端应把组件齐全的剑放进背包",
                )
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slots.orEmpty().any {
                        it?.key == "minecraft:diamond_sword" && it.customName == COMPONENT_SWORD_NAME
                    }
                }
                val sword = assertNotNull(
                    session.inventory()?.slots.orEmpty()
                        .firstOrNull { it?.key == "minecraft:diamond_sword" },
                )
                assertEquals(COMPONENT_ITEM_NAME, sword.itemName)
                assertEquals(listOf("第一行", "第二行"), sword.lore)
                assertEquals(COMPONENT_DAMAGE, sword.damage)
                assertEquals(COMPONENT_MAX_DAMAGE, sword.maxDamage)
                assertTrue(sword.unbreakable, "unbreakable 是 Unit 组件，存在即为真")
                assertEquals(COMPONENT_REPAIR_COST, sword.repairCost)
                assertEquals(
                    mapOf("minecraft:sharpness" to 3),
                    sword.enchantments,
                    "附魔 wire id 应由配置阶段下发的注册表还原成键名",
                )
                assertTrue(sword.storedEnchantments.isEmpty(), "普通武器不该有附魔书组件")
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `实体属性可读且变化会推给本人`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, ATTR_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
                val entityId = assertNotNull(session.world.value?.entityId, "出生后应知道自己的实体 id")

                // Minestom 的属性实例按需创建：出生时发的属性包可能整包为空（这正是
                // 「事件只带变化项」的一个极端例子），因此这里不断言默认值，只等变化。
                //
                // 服务端改基值：Minestom 在 onAttributeChanged 里主动推给自己（PLAY 阶段），
                // 客户端不需要任何观察者就能收到。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        setPlayerAttribute(ATTR_USERNAME, Attribute.MAX_HEALTH, RAISED_MAX_HEALTH)
                    },
                    "服务端应找到该玩家并改属性",
                )
                log.await {
                    it is ClientEvent.EntityAttributesUpdated && it.entityId == entityId &&
                        it.changed.any { a -> a.key == MAX_HEALTH_KEY }
                }
                assertEquals(
                    RAISED_MAX_HEALTH,
                    session.attribute(entityId, MAX_HEALTH_KEY)?.value,
                    "属性事件到达时缓存应已合并",
                )

                // 改第二个属性：按键合并必须保住第一个键——整表替换在这里就会现形。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        setPlayerAttribute(ATTR_USERNAME, Attribute.MOVEMENT_SPEED, RAISED_MOVE_SPEED)
                    },
                    "服务端应能改移动速度",
                )
                log.await {
                    it is ClientEvent.EntityAttributesUpdated && it.entityId == entityId &&
                        it.changed.any { a -> a.key == MOVE_SPEED_KEY }
                }
                assertEquals(
                    RAISED_MAX_HEALTH,
                    session.attribute(entityId, MAX_HEALTH_KEY)?.value,
                    "合并不得抹掉之前收到的属性",
                )
                assertEquals(
                    RAISED_MOVE_SPEED,
                    session.attribute(entityId, MOVE_SPEED_KEY)?.value,
                )
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `攻击与交互实体后两侧同时可见`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, MOB_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                val zombie = withContext(Dispatchers.IO) {
                    spawnZombie(instance, MOB_ZOMBIE_X, GROUND_LEVEL, 0.5)
                }
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.entity(zombie.entityId)?.type == "ZOMBIE"
                }
                // 共享 instance 里可能有别的用例留下的僵尸，必须按服务端给的 id 定位。
                val zombieId = zombie.entityId

                // 僵尸的属性实例同样按需创建，出生包可能为空；服务端改一次基值，
                // Minestom 会把属性包推给所有观察者（本端客户端就是观察者）。
                zombie.getAttribute(Attribute.MAX_HEALTH).setBaseValue(RAISED_MAX_HEALTH)
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.attribute(zombieId, MAX_HEALTH_KEY)?.value == RAISED_MAX_HEALTH
                }

                // 攻击：服务端只调事件不自动结算，harness 补一次固定伤害。
                session.attack(zombieId)
                assertTrue(
                    withContext(Dispatchers.IO) { awaitEntityAttack(MOB_USERNAME, MILESTONE_TIMEOUT) },
                    "服务端应确认这次攻击",
                )
                // 服务端侧：血量真的掉了。客户端侧：伤害事件包到达（第四例无回执，只能看结果）。
                awaitCondition(EVENT_TIMEOUT_MILLIS) { zombie.health < DEFAULT_MAX_HEALTH.toFloat() }
                log.await { it is ClientEvent.EntityDamaged && it.entityId == zombieId }

                // 交互（右键）：Minestom 只登记事件；本端没有任何回执，服务端事实就是全部证据。
                session.interactWith(zombieId)
                assertTrue(
                    withContext(Dispatchers.IO) { awaitEntityInteract(MOB_USERNAME, MILESTONE_TIMEOUT) },
                    "服务端应确认这次交互",
                )
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `生物血量按实体层级读取而非只看元数据下标`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, HEALTH_USERNAME)
            val log = EventLog(this, session)
            val spawned = mutableListOf<Entity>()
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                val zombie = withContext(Dispatchers.IO) {
                    spawnZombie(instance, HEALTH_ZOMBIE_X, GROUND_LEVEL, 0.5)
                }
                spawned += zombie
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.entity(zombie.entityId)?.type == "ZOMBIE" }

                // 服务端改血量 → LivingEntityMeta → set_entity_data；客户端只在确认是生物后才读下标 9。
                withContext(Dispatchers.IO) { setEntityHealth(zombie, ZOMBIE_HEALTH) }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.health(zombie.entityId) == ZOMBIE_HEALTH }
                log.await {
                    it is ClientEvent.EntityHealthChanged &&
                        it.entityId == zombie.entityId && it.health == ZOMBIE_HEALTH
                }
                assertEquals(ZOMBIE_HEALTH, session.entity(zombie.entityId)?.health)

                // 掉落物不是生物：同一下标即便带 float，也不该被当成血量。
                // 先把世界存进局部变量：apply 的隐式接收者自己也有 instance 属性（此时为 null）。
                val world = instance
                val dropped = withContext(Dispatchers.IO) {
                    ItemEntity(ItemStack.of(Material.STONE)).apply {
                        setInstance(world, Pos(HEALTH_ITEM_X, GROUND_LEVEL, 0.5))
                    }
                }
                spawned += dropped
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.entity(dropped.entityId) != null }
                assertNull(session.health(dropped.entityId), "非生物不应读到血量")
                assertFalse(
                    log.snapshot().any {
                        it is ClientEvent.EntityHealthChanged && it.entityId == dropped.entityId
                    },
                    "非生物不应产生血量事件",
                )
            } finally {
                log.close()
                session.close()
                // instance 由整个测试类共享：用完就撤，不给后续用例留下多余的实体。
                spawned.forEach { it.remove() }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `挖掘方块后服务端世界与本地缓存同时变空`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, DIG_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                // 脚下方块：出生点在平台顶上，脚底下那格是石头，两侧都能直接核对。
                val spawn = assertNotNull(session.world.value?.position, "出生后应已同步位置")
                val target = BlockPosition(spawn.x.toInt(), GROUND_LEVEL.toInt() - 1, spawn.z.toInt())
                val before = assertNotNull(session.blockStateAt(target), "出生点所在区块应已加载")
                assertEquals(
                    instance.getBlock(target.x, target.y, target.z).stateId(),
                    before,
                    "本地缓存应与服务端一致",
                )
                assertEquals(0, session.lastAcknowledgedSequence, "还没有交互被确认过")

                session.digBlock(target)

                // 服务端侧：finish 事件的登记是“方块真的被打碎了”的直接证据。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitPlayerDig(DIG_USERNAME, target.x, target.y, target.z, MILESTONE_TIMEOUT)
                    },
                    "服务端应完成这次挖掘",
                )
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    instance.getBlock(target.x, target.y, target.z) == Block.AIR
                }

                // 客户端侧：方块变更写入缓存，确认序号前进。
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.blockStateAt(target) == 0 }
                assertTrue(session.lastAcknowledgedSequence > 0, "服务端应回执挖掘动作")

                // 方块缓存走旁路（不经过 Actor），事件却要排队——两者到达有先后差。
                // CI 负载下这个窗口能拉长到秒级，因此事件查找也必须等待，不能查一次就断言。
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    log.snapshot().filterIsInstance<ClientEvent.BlocksChanged>()
                        .flatMap { it.changes }
                        .any { it.position == target }
                }
                val change = assertNotNull(
                    log.snapshot().filterIsInstance<ClientEvent.BlocksChanged>()
                        .flatMap { it.changes }
                        .firstOrNull { it.position == target },
                    "事件流里应有这条方块变更",
                )
                assertEquals(0, change.stateId)
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `放置方块后服务端与本地缓存同时可见`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, PLACE_USERNAME)
            try {
                val log = EventLog(this, session)
                try {
                    log.await { it is ClientEvent.ChunksReceived && it.total > 0 }
                    awaitCondition(EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                    val player = assertNotNull(
                        MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(PLACE_USERNAME),
                        "服务端应已保存该玩家",
                    )
                    // 放置需要手上有方块：发一组石头到主手。客户端从背包快照里看到它——
                    // 这条链路同时验证了 M4h 的槽位换算（主手 = 快捷栏第 0 格 = 窗口槽位 36）。
                    player.setItemInMainHand(ItemStack.of(Material.STONE, PLACE_COUNT))
                    awaitCondition(EVENT_TIMEOUT_MILLIS) {
                        session.heldItem()?.count == PLACE_COUNT
                    }

                    val spawn = assertNotNull(session.world.value?.position, "出生后应已同步位置")
                    // 点在旁边一块石头的顶面：离出生点两格，避开玩家自己的碰撞盒。
                    val target = BlockPosition(spawn.x.toInt() + 2, GROUND_LEVEL.toInt() - 1, spawn.z.toInt())
                    val above = target.offset(BlockFace.UP)
                    assertEquals(
                        0,
                        assertNotNull(session.blockStateAt(above), "目标格所在区块应已加载"),
                        "目标格原本应是空气",
                    )

                    session.useOnBlock(target, BlockFace.UP, cursorY = 1f)

                    assertTrue(
                        withContext(Dispatchers.IO) {
                            awaitPlayerPlace(PLACE_USERNAME, above.x, above.y, above.z, MILESTONE_TIMEOUT)
                        },
                        "服务端应完成这次放置",
                    )
                    awaitCondition(EVENT_TIMEOUT_MILLIS) {
                        instance.getBlock(above.x, above.y, above.z) == Block.STONE
                    }
                    awaitCondition(EVENT_TIMEOUT_MILLIS) {
                        session.blockStateAt(above) == instance.getBlock(above.x, above.y, above.z).stateId()
                    }
                    assertTrue(session.lastAcknowledgedSequence > 0, "服务端应回执放置动作")

                    // 方块缓存由网络线程旁路先写，事件随后才排进 Actor：查一次快照会偶发抢跑。
                    awaitCondition(EVENT_TIMEOUT_MILLIS) {
                        log.snapshot().filterIsInstance<ClientEvent.BlocksChanged>()
                            .any { event -> event.changes.any { it.position == above } }
                    }
                    val change = log.snapshot().filterIsInstance<ClientEvent.BlocksChanged>()
                        .flatMap { it.changes }
                        .firstOrNull { it.position == above }
                    assertTrue(change != null, "事件流里应有这条方块变更")
                } finally {
                    log.close()
                }
            } finally {
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `冲刺潜行与飞行的上行包服务端可见且能力由服务端权威下发`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, MOTION_USERNAME)
            val log = EventLog(this, session)
            try {
                assertTrue(awaitMilestone(MOTION_USERNAME, PlayerMilestone.SPAWNED, MILESTONE_TIMEOUT))
                // 服务端里程碑先到、客户端 JoinedWorld 还在 inbox 里排队：等客户端侧就绪。
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.selfEntityId != null }
                assertNotNull(session.selfEntityId, "出生后应拿到本端实体 id")

                // 三个动作各走不同的包：input(shift)、input(sprint)+player_command、abilities。
                session.setSneaking(true)
                session.setSprinting(true)
                session.setFlying(true)

                assertTrue(
                    awaitPlayerInput(MOTION_USERNAME, MILESTONE_TIMEOUT) { it.shift() },
                    "服务端应看到 shift 位被置起",
                )
                assertTrue(
                    awaitPlayerInput(MOTION_USERNAME, MILESTONE_TIMEOUT) { it.sprint() && !it.shift() },
                    "开启冲刺时应清掉 shift 位（原版两者互斥）",
                )
                assertTrue(
                    awaitPlayerPacket(MOTION_USERNAME, "command.START_SPRINTING", MILESTONE_TIMEOUT),
                    "服务端应收到独立的 START_SPRINTING",
                )
                assertTrue(
                    awaitPlayerPacket(MOTION_USERNAME, "flying.true", MILESTONE_TIMEOUT),
                    "服务端应收到飞行请求",
                )
                assertTrue(session.movementInput.value.sprinting, "本地按键状态应立即推进")
                assertFalse(session.abilities.value.flying, "上行请求不等于服务端已认可飞行")

                // 服务端权威答复：能力包里允许且正在飞。
                sendAbilities(MOTION_USERNAME, ABILITIES_CAN_FLY or ABILITIES_FLYING, ABILITIES_FLY_SPEED, 0.2f)
                log.await {
                    it is ClientEvent.AbilitiesChanged && it.abilities.flying && it.abilities.canFly
                }
                assertTrue(session.abilities.value.flying, "能力包到达后才算真的在飞")
                assertEquals(ABILITIES_FLY_SPEED, session.abilities.value.flySpeed)

                session.setSprinting(false)
                assertTrue(
                    awaitPlayerPacket(MOTION_USERNAME, "command.STOP_SPRINTING", MILESTONE_TIMEOUT),
                    "服务端应收到 STOP_SPRINTING",
                )
                assertFalse(session.movementInput.value.sprinting)
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `状态效果可按键合并且移除只影响被点名的效果`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, EFFECT_USERNAME)
            val log = EventLog(this, session)
            try {
                assertTrue(awaitMilestone(EFFECT_USERNAME, PlayerMilestone.SPAWNED, MILESTONE_TIMEOUT))
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.selfEntityId != null }
                val entityId = assertNotNull(session.selfEntityId)

                sendMobEffect(EFFECT_USERNAME, entityId, SPEED_KEY, EFFECT_AMPLIFIER, EFFECT_DURATION, 0x02)
                log.await {
                    it is ClientEvent.EntityEffectsChanged && it.changed.any { effect -> effect.key == SPEED_KEY }
                }
                val speed = assertNotNull(session.effect(entityId, SPEED_KEY))
                assertEquals(EFFECT_AMPLIFIER, speed.amplifier)
                assertEquals(EFFECT_DURATION, speed.durationTicks)
                assertTrue(speed.showParticles, "flags 的粒子位应保留")
                assertFalse(speed.showIcon)

                // 第二个包只带一个效果，合并后两个键都要在。
                sendMobEffect(EFFECT_USERNAME, entityId, HASTE_KEY, 0, 100, 0)
                awaitCondition(EVENT_TIMEOUT_MILLIS) { session.effects(entityId).size == 2 }

                sendRemoveMobEffect(EFFECT_USERNAME, entityId, SPEED_KEY)
                log.await { it is ClientEvent.EntityEffectsChanged && SPEED_KEY in it.removed }
                assertNull(session.effect(entityId, SPEED_KEY))
                assertTrue(
                    session.effects(entityId).containsKey(HASTE_KEY),
                    "移除一个效果不应抹掉另一个",
                )
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `容器数值属性可读且多个下标互不覆盖`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, PROPERTY_USERNAME)
            val log = EventLog(this, session)
            try {
                assertTrue(awaitMilestone(PROPERTY_USERNAME, PlayerMilestone.SPAWNED, MILESTONE_TIMEOUT))

                sendWindowProperty(PROPERTY_USERNAME, Container.PLAYER_WINDOW_ID, FURNACE_PROGRESS, 75)
                log.await {
                    it is ClientEvent.ContainerPropertyChanged &&
                        it.windowId == Container.PLAYER_WINDOW_ID &&
                        it.property == FURNACE_PROGRESS && it.value == 75
                }
                assertEquals(75, session.containerProperties(Container.PLAYER_WINDOW_ID)[FURNACE_PROGRESS])

                sendWindowProperty(PROPERTY_USERNAME, Container.PLAYER_WINDOW_ID, FURNACE_TOTAL, 200)
                awaitCondition(EVENT_TIMEOUT_MILLIS) {
                    session.containerProperties(Container.PLAYER_WINDOW_ID).size == 2
                }
                assertEquals(75, session.containerProperties(Container.PLAYER_WINDOW_ID)[FURNACE_PROGRESS])
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

        /** 方块交互用例：挖出生点脚下的那格石头，把服务端发的石头放到旁边两格的顶面。 */
        const val DIG_USERNAME: String = "DigBot"
        const val PLACE_USERNAME: String = "PlaceBot"
        const val PLACE_COUNT: Int = 8

        /** 属性用例：自查默认最大生命，然后由 harness 改基值并等事件。 */
        const val ATTR_USERNAME: String = "AttrBot"
        const val MAX_HEALTH_KEY: String = "minecraft:max_health"
        const val MOVE_SPEED_KEY: String = "minecraft:movement_speed"
        const val DEFAULT_MAX_HEALTH: Double = 20.0
        const val RAISED_MAX_HEALTH: Double = 40.0
        const val RAISED_MOVE_SPEED: Double = 1.5

        /** 实体交互用例：生成一只僵尸，攻击它、右键它，两侧对账。 */
        const val MOB_USERNAME: String = "MobBot"
        const val MOB_ZOMBIE_X: Double = 2.5
        const val ATTACK_DAMAGE: Double = 3.0

        /** 生物血量用例：僵尸改血量，掉落物作为「同下标但不是血量」的反例。 */
        const val HEALTH_USERNAME: String = "HealthBot"
        const val HEALTH_ZOMBIE_X: Double = 8.5
        const val HEALTH_ITEM_X: Double = 9.5
        const val ZOMBIE_HEALTH: Float = 7.5f

        /** 物品键名用例：服务端塞一块带名字的石头与一颗无名钻石。 */
        const val ITEM_USERNAME: String = "ItemBot"
        const val NAMED_ITEM_NAME: String = "试金石"

        /** 物品组件用例：一把把常用组件设满的钻石剑。 */
        const val COMPONENT_USERNAME: String = "ComponentBot"

        /** 运动状态用例：上行包由 PlayerPacketEvent 登记，能力由服务端权威下发。 */
        const val MOTION_USERNAME: String = "MotionBot"
        const val ABILITIES_FLYING: Int = 0x02
        const val ABILITIES_CAN_FLY: Int = 0x04
        const val ABILITIES_FLY_SPEED: Float = 0.08f

        /** 状态效果用例：两个键合并后移除其中一个。 */
        const val EFFECT_USERNAME: String = "EffectBot"
        const val SPEED_KEY: String = "minecraft:speed"
        const val HASTE_KEY: String = "minecraft:haste"
        const val EFFECT_AMPLIFIER: Int = 1
        const val EFFECT_DURATION: Int = 200

        /** 命令树与补全用例：候选由服务端的回调按前缀过滤。 */
        const val COMMAND_USERNAME: String = "CommandBot"

        /** 服务端不回包时的等待上限：只为证明“没有答案”不会挂住调用方。 */
        const val NO_ANSWER_TIMEOUT_MILLIS: Long = 800

        /** 容器属性用例：熔炉进度与总时长两个下标。 */
        const val PROPERTY_USERNAME: String = "PropertyBot"
        const val FURNACE_PROGRESS: Int = 2
        const val FURNACE_TOTAL: Int = 3

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

        /** 区块环境用例：出生前就摆好的箱子、出生后放的箱子、以及火把，都在出生区块内。 */
        const val ENVIRONMENT_USERNAME: String = "EnvBot"
        const val INITIAL_CHEST_X: Int = 3
        const val INITIAL_CHEST_Z: Int = 3
        const val LIVE_CHEST_X: Int = 5
        const val LIVE_CHEST_Z: Int = 3
        const val TORCH_X: Int = 7
        const val TORCH_Z: Int = 3

        /** 高度图用例的列：出生区块内、但没有别的用例在那里改方块。 */
        const val HEIGHTMAP_X: Int = 9
        const val HEIGHTMAP_Z: Int = 9

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
