package org.pickaxe.testing.minestom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.Position
import org.pickaxe.client.SessionState
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 世界与实体交互：移动、骑乘、挖掘与放置、破坏进度、实体属性与状态效果。
 *
 * 每条用例都同时看两侧：客户端缓存/事件，以及服务端实际收到的包或世界状态。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SessionWorldInteractionTest : SessionTestBase() {

    @Test
    fun `骑乘时乘客关系可读，载具移动走独立包`() = runBlocking {
        // 协议里既没有“上车”也没有“下车”包，`move_vehicle` 又不带实体 id：两件事都只能从
        // “某个载具的乘客列表里有我”推出来。因此这里按顺序验证三段事实：
        // 乘客列表到达 → 玩家位置包被封住 → 载具坐标由载具包推进（上行与下行各一次）。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, RIDE_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("骑乘时乘客关系可读，载具移动走独立包") { it is ClientEvent.ChunksReceived && it.total > 0 }
                val self = assertNotNull(
                    withTimeout(EVENT_TIMEOUT_MILLIS) { session.world.first { it?.entityId != null } },
                    "进入世界后才能确定自己的实体 id",
                ).entityId

                session.sendCommand("/$RIDE_COMMAND_NAME")
                log.await("骑乘时乘客关系可读，载具移动走独立包") { it is ClientEvent.VehicleChanged && it.vehicleId != null }
                val vehicleId = assertNotNull(session.vehicle.value, "上车后应记录载具 id")

                // 乘客列表是整份替换，判据是“列表里有自己的实体 id”。
                val passengers = log.snapshot().filterIsInstance<ClientEvent.PassengersChanged>().last()
                assertEquals(vehicleId, passengers.vehicleId, "事件应指明是哪个载具的列表")
                assertEquals(listOf(self), passengers.passengerIds, "自己应当出现在乘客列表里")
                assertEquals(listOf(self), session.passengers(vehicleId), "缓存应与事件给出同一份列表")
                assertEquals(RIDE_ENTITY_TYPE, session.entity(vehicleId)?.type, "载具应已被跟踪成实体")
                assertTrue(
                    withContext(Dispatchers.IO) { awaitRide(RIDE_USERNAME, MILESTONE_TIMEOUT) },
                    "服务端应确认这次上车",
                )

                // 骑乘时玩家位置包是非法的：原版客户端在载具上根本不发它们，服务端也只按
                // 载具包改坐标。这里要的是显式失败，而不是发出去被悄悄忽略。
                val blocked = assertFailsWith<IllegalStateException> {
                    session.moveTo(Position(1.0, RIDE_GROUND_Y, 1.0, 0f, 0f))
                }
                assertTrue(
                    blocked.message.orEmpty().contains("steerVehicle"),
                    "错误信息应指向载具通道，实际为 ${blocked.message}",
                )

                // 上行：绝对坐标。服务端侧看到的就是发出去的值，Minestom 随后把它应用到载具上。
                val steer = Position(2.5, RIDE_GROUND_Y, 0.5, 90f, 0f)
                session.steerVehicle(steer, onGround = true)
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitVehicleMove(RIDE_USERNAME, MILESTONE_TIMEOUT) { move ->
                            abs(move.x - steer.x) < 1e-9 &&
                                abs(move.y - steer.y) < 1e-9 &&
                                abs(move.z - steer.z) < 1e-9 &&
                                abs(move.yaw - steer.yaw) < 1e-9 &&
                                move.onGround
                        }
                    },
                    "服务端应收到载具移动包，实际观察到 ${observedVehicleMoves(RIDE_USERNAME)}",
                )
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitRidePosition(RIDE_USERNAME, MILESTONE_TIMEOUT) { pos ->
                            abs(pos.x() - steer.x) < 1e-6 && abs(pos.z() - steer.z) < 1e-6
                        }
                    },
                    "服务端应把载具移到包里给的坐标，实际为 ${ridePosition(RIDE_USERNAME)}",
                )

                // 下行：Minestom 从不纠正载具，所以由 harness 发一个真实形状的权威包。
                // 本端要把它折到载具快照上，同时推进“我在哪”——骑乘时没有别的来源。
                val yawBefore = session.world.value!!.position!!.yaw
                session.sendCommand("/$RIDE_COMMAND_NAME move")
                awaitCondition("骑乘时乘客关系可读，载具移动走独立包", EVENT_TIMEOUT_MILLIS) {
                    session.entity(vehicleId)?.let { abs(it.x - RIDE_PUSH_X) < 1e-6 } == true
                }
                with(session.entity(vehicleId)!!) {
                    assertEquals(RIDE_PUSH_Y, y, 1e-6, "载具快照应按权威包更新")
                    assertEquals(RIDE_PUSH_YAW, yaw, 1e-4f, "载具朝向同样来自权威包")
                }
                with(session.world.value!!.position!!) {
                    assertEquals(RIDE_PUSH_X, x, 1e-6, "骑乘时“我在哪”跟着载具走")
                    assertEquals(RIDE_PUSH_Z, z, 1e-6)
                    assertEquals(yawBefore, yaw, 1e-4f, "朝向仍归乘客自己，不该被载具包改掉")
                }

                // 下车就是一条空列表：没有单独的“下车包”，也没有回执。
                session.sendCommand("/$RIDE_COMMAND_NAME stop")
                log.await("骑乘时乘客关系可读，载具移动走独立包") { it is ClientEvent.VehicleChanged && it.vehicleId == null }
                assertNull(session.vehicle.value, "下车后不该还挂在载具上")
                assertTrue(session.passengers(vehicleId).isEmpty(), "空列表应从缓存里移除这个载具")
                assertTrue(
                    withContext(Dispatchers.IO) { awaitDismount(RIDE_USERNAME, MILESTONE_TIMEOUT) },
                    "服务端应确认这次下车",
                )

                // 下车后玩家位置包恢复可用：这正是“骑乘时封住”的对照面。
                val onFoot = session.world.value!!.position!!
                session.moveTo(Position(onFoot.x + 1.0, onFoot.y, onFoot.z, onFoot.yaw, onFoot.pitch))
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
                log.await("移动与转向服务端可见") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("移动与转向服务端可见", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
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
                awaitCondition("移动与转向服务端可见", EVENT_TIMEOUT_MILLIS) { session.world.value?.position == target }
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
                awaitCondition("移动与转向服务端可见", EVENT_TIMEOUT_MILLIS) {
                    val position = session.world.value?.position
                    position != null && abs(position.x - CORRECTION_X) < POSITION_TOLERANCE
                }

                // 等确认包回到服务端，否则 Minestom 会把移动当成“传送未确认”丢掉。
                awaitCondition("移动与转向服务端可见", EVENT_TIMEOUT_MILLIS) {
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
    fun `破坏进度可观察且清除后条目消失`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, DESTRUCTION_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("破坏进度可观察且清除后条目消失") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("破坏进度可观察且清除后条目消失", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                // 破坏者是一个真实实体：观察端能在实体表里对上 breakerEntityId。
                val digger = EntityCreature(EntityType.ZOMBIE)
                digger.setNoGravity(true)
                digger.setInstance(instance, Pos(DESTRUCTION_DIGGER_X, GROUND_LEVEL, 1.5)).join()
                awaitCondition("破坏进度可观察且清除后条目消失", EVENT_TIMEOUT_MILLIS) { session.entity(digger.entityId) != null }

                val target = BlockPosition(DESTRUCTION_X, GROUND_LEVEL.toInt() - 1, DESTRUCTION_Z)

                // 阶段递增：1 → 5 → 10；坐标、破坏者 id 与服务端下发逐项对账。
                for (stage in listOf(1, 5, 10)) {
                    assertTrue(
                        withContext(Dispatchers.IO) {
                            sendBlockBreakAnimation(
                                DESTRUCTION_USERNAME,
                                digger.entityId,
                                target.x,
                                target.y,
                                target.z,
                                (stage - 1).toByte(),
                            )
                        },
                        "服务端应能下发阶段 $stage 的破坏进度",
                    )
                    log.await("破坏进度可观察且清除后条目消失") {
                        it is ClientEvent.BlockDestructionChanged &&
                            it.position == target && it.destruction?.stage == stage
                    }
                    awaitCondition("破坏进度可观察且清除后条目消失", EVENT_TIMEOUT_MILLIS) { session.destruction(target)?.stage == stage }
                    val observed = assertNotNull(session.destruction(target), "阶段 $stage 应可在会话中查到")
                    assertEquals(digger.entityId, observed.breakerEntityId, "破坏者应与实体表对上")
                    assertEquals(target, observed.position, "坐标应与被挖的方块一致")
                    assertTrue(target in session.destructions().keys, "快照应包含该坐标的进度")
                }

                // RESET：条目消失，而不是变成一个阶段值；查询也回到 null。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        sendBlockBreakAnimation(
                            DESTRUCTION_USERNAME,
                            digger.entityId,
                            target.x,
                            target.y,
                            target.z,
                            10,
                        )
                    },
                    "服务端应能下发 RESET",
                )
                log.await("破坏进度可观察且清除后条目消失") {
                    it is ClientEvent.BlockDestructionChanged &&
                        it.position == target && it.destruction == null
                }
                awaitCondition("破坏进度可观察且清除后条目消失", EVENT_TIMEOUT_MILLIS) { session.destruction(target) == null }
                assertTrue(session.destructions().isEmpty(), "RESET 后快照不应再有该坐标")

                digger.remove()
            } finally {
                log.close()
                session.close()
            }

            assertTrue(session.destructions().isEmpty(), "关闭后不应继续持有破坏进度")
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
                log.await("实体属性可读且变化会推给本人") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("实体属性可读且变化会推给本人", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
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
                log.await("实体属性可读且变化会推给本人") {
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
                log.await("实体属性可读且变化会推给本人") {
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
                log.await("攻击与交互实体后两侧同时可见") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("攻击与交互实体后两侧同时可见", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                val zombie = withContext(Dispatchers.IO) {
                    spawnZombie(instance, MOB_ZOMBIE_X, GROUND_LEVEL, 0.5)
                }
                awaitCondition("攻击与交互实体后两侧同时可见", EVENT_TIMEOUT_MILLIS) {
                    session.entity(zombie.entityId)?.type == "ZOMBIE"
                }
                // 共享 instance 里可能有别的用例留下的僵尸，必须按服务端给的 id 定位。
                val zombieId = zombie.entityId

                // 僵尸的属性实例同样按需创建，出生包可能为空；服务端改一次基值，
                // Minestom 会把属性包推给所有观察者（本端客户端就是观察者）。
                zombie.getAttribute(Attribute.MAX_HEALTH).setBaseValue(RAISED_MAX_HEALTH)
                awaitCondition("攻击与交互实体后两侧同时可见", EVENT_TIMEOUT_MILLIS) {
                    session.attribute(zombieId, MAX_HEALTH_KEY)?.value == RAISED_MAX_HEALTH
                }

                // 攻击：服务端只调事件不自动结算，harness 补一次固定伤害。
                session.attack(zombieId)
                assertTrue(
                    withContext(Dispatchers.IO) { awaitEntityAttack(MOB_USERNAME, MILESTONE_TIMEOUT) },
                    "服务端应确认这次攻击",
                )
                // 服务端侧：血量真的掉了。客户端侧：伤害事件包到达（第四例无回执，只能看结果）。
                awaitCondition("攻击与交互实体后两侧同时可见", EVENT_TIMEOUT_MILLIS) { zombie.health < DEFAULT_MAX_HEALTH.toFloat() }
                log.await("攻击与交互实体后两侧同时可见") { it is ClientEvent.EntityDamaged && it.entityId == zombieId }

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
                log.await("生物血量按实体层级读取而非只看元数据下标") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("生物血量按实体层级读取而非只看元数据下标", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                val zombie = withContext(Dispatchers.IO) {
                    spawnZombie(instance, HEALTH_ZOMBIE_X, GROUND_LEVEL, 0.5)
                }
                spawned += zombie
                awaitCondition("生物血量按实体层级读取而非只看元数据下标", EVENT_TIMEOUT_MILLIS) { session.entity(zombie.entityId)?.type == "ZOMBIE" }

                // 服务端改血量 → LivingEntityMeta → set_entity_data；客户端只在确认是生物后才读下标 9。
                withContext(Dispatchers.IO) { setEntityHealth(zombie, ZOMBIE_HEALTH) }
                awaitCondition("生物血量按实体层级读取而非只看元数据下标", EVENT_TIMEOUT_MILLIS) { session.health(zombie.entityId) == ZOMBIE_HEALTH }
                log.await("生物血量按实体层级读取而非只看元数据下标") {
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
                awaitCondition("生物血量按实体层级读取而非只看元数据下标", EVENT_TIMEOUT_MILLIS) { session.entity(dropped.entityId) != null }
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
                log.await("出生后收到首批区块") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("出生后位置同步", EVENT_TIMEOUT_MILLIS, session = session) { session.world.value?.position != null }

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
                awaitCondition("服务端方块应变为空气", EVENT_TIMEOUT_MILLIS, session = session, events = log) {
                    instance.getBlock(target.x, target.y, target.z) == Block.AIR
                }

                // 客户端侧：方块变更写入缓存，确认序号前进。
                awaitCondition("客户端缓存应变为空气", EVENT_TIMEOUT_MILLIS, session = session) { session.blockStateAt(target) == 0 }
                assertTrue(session.lastAcknowledgedSequence > 0, "服务端应回执挖掘动作")

                // 方块缓存走旁路（不经过 Actor），事件却要排队——两者到达有先后差。
                // CI 负载下这个窗口能拉长到秒级，因此事件查找也必须等待，不能查一次就断言。
                awaitCondition("事件流应包含该方块变更", EVENT_TIMEOUT_MILLIS, session = session) {
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
                    log.await("放置方块后服务端与本地缓存同时可见") { it is ClientEvent.ChunksReceived && it.total > 0 }
                    awaitCondition("放置方块后服务端与本地缓存同时可见", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                    val player = assertNotNull(
                        MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(PLACE_USERNAME),
                        "服务端应已保存该玩家",
                    )
                    // 放置需要手上有方块：发一组石头到主手。客户端从背包快照里看到它——
                    // 这条链路同时验证了 M4h 的槽位换算（主手 = 快捷栏第 0 格 = 窗口槽位 36）。
                    player.setItemInMainHand(ItemStack.of(Material.STONE, PLACE_COUNT))
                    awaitCondition("放置方块后服务端与本地缓存同时可见", EVENT_TIMEOUT_MILLIS) {
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
                    awaitCondition("放置方块后服务端与本地缓存同时可见", EVENT_TIMEOUT_MILLIS) {
                        instance.getBlock(above.x, above.y, above.z) == Block.STONE
                    }
                    awaitCondition("放置方块后服务端与本地缓存同时可见", EVENT_TIMEOUT_MILLIS) {
                        session.blockStateAt(above) == instance.getBlock(above.x, above.y, above.z).stateId()
                    }
                    assertTrue(session.lastAcknowledgedSequence > 0, "服务端应回执放置动作")

                    // 方块缓存由网络线程旁路先写，事件随后才排进 Actor：查一次快照会偶发抢跑。
                    awaitCondition("放置方块后服务端与本地缓存同时可见", EVENT_TIMEOUT_MILLIS) {
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
                awaitCondition("冲刺潜行与飞行的上行包服务端可见且能力由服务端权威下发", EVENT_TIMEOUT_MILLIS) { session.selfEntityId != null }
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
                log.await("冲刺潜行与飞行的上行包服务端可见且能力由服务端权威下发") {
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
                awaitCondition("状态效果可按键合并且移除只影响被点名的效果", EVENT_TIMEOUT_MILLIS) { session.selfEntityId != null }
                val entityId = assertNotNull(session.selfEntityId)

                sendMobEffect(EFFECT_USERNAME, entityId, SPEED_KEY, EFFECT_AMPLIFIER, EFFECT_DURATION, 0x02)
                log.await("状态效果可按键合并且移除只影响被点名的效果") {
                    it is ClientEvent.EntityEffectsChanged && it.changed.any { effect -> effect.key == SPEED_KEY }
                }
                val speed = assertNotNull(session.effect(entityId, SPEED_KEY))
                assertEquals(EFFECT_AMPLIFIER, speed.amplifier)
                assertEquals(EFFECT_DURATION, speed.durationTicks)
                assertTrue(speed.showParticles, "flags 的粒子位应保留")
                assertFalse(speed.showIcon)

                // 第二个包只带一个效果，合并后两个键都要在。
                sendMobEffect(EFFECT_USERNAME, entityId, HASTE_KEY, 0, 100, 0)
                awaitCondition("状态效果可按键合并且移除只影响被点名的效果", EVENT_TIMEOUT_MILLIS) { session.effects(entityId).size == 2 }

                sendRemoveMobEffect(EFFECT_USERNAME, entityId, SPEED_KEY)
                log.await("状态效果可按键合并且移除只影响被点名的效果") { it is ClientEvent.EntityEffectsChanged && SPEED_KEY in it.removed }
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
}
