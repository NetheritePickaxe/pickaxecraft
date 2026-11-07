package org.pickaxe.testing.minestom

import net.kyori.adventure.text.Component
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.inventory.CreativeInventoryActionEvent
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerFinishDiggingEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerPluginMessageEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.LightingChunk
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment
import net.minestom.server.registry.RegistryKey
import net.minestom.server.utils.Unit as MinestomUnit
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
import net.minestom.server.network.packet.client.play.ClientEntityActionPacket
import net.minestom.server.network.packet.client.play.ClientInputPacket
import net.minestom.server.network.packet.client.play.ClientPlayerAbilitiesPacket
import net.minestom.server.network.packet.client.play.ClientTabCompletePacket
import net.minestom.server.network.packet.client.play.ClientVehicleMovePacket
import net.minestom.server.network.packet.server.common.PluginMessagePacket
import net.minestom.server.network.packet.server.common.TransferPacket
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket
import net.minestom.server.utils.inventory.PlayerInventoryUtils
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket
import net.minestom.server.network.packet.server.play.EntityEffectPacket
import net.minestom.server.network.packet.server.play.VehicleMovePacket
import net.minestom.server.network.packet.server.play.PlayerAbilitiesPacket
import net.minestom.server.network.packet.server.play.RemoveEntityEffectPacket
import net.minestom.server.network.packet.server.play.TabCompletePacket
import net.minestom.server.network.packet.server.play.UpdateLightPacket
import net.minestom.server.network.packet.server.play.WindowPropertyPacket
import net.minestom.server.network.packet.server.play.data.LightData
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import org.pickaxe.protocol.ProtocolVersion
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** 默认监听地址：只绑定回环，避免把测试服务暴露到局域网。 */
public const val DEFAULT_HOST: String = "127.0.0.1"

/** 默认监听端口，与 Minecraft 惯例一致。 */
public const val DEFAULT_PORT: Int = 25565

/**
 * 联调用的 echo 命令名。
 *
 * 命令上行需要一个“服务端确实执行了”的落点，而 Minestom 核心不带任何默认可用的命令，
 * 因此在 harness 里注册一个最小命令：执行即登记事实并回一条固定文本。
 */
public const val ECHO_COMMAND_NAME: String = "pickaxe"

/** 骑乘命令：把执行者放上/放下一艘船，用来验证载具与乘客链路。 */
public const val RIDE_COMMAND_NAME: String = "pickaxeride"

/**
 * 骑乘命令用的载具类型名，客户端读到的 `TrackedEntity.type` 就是它。
 *
 * 26.2 里船按木种拆分，没有中性的 `BOAT`；橡木船是最普通的那个，选它只是取一个
 * “能载人的普通实体”，本用例不关心船的材质。
 */
public const val RIDE_ENTITY_TYPE: String = "OAK_BOAT"

/**
 * 服务端“权威”下发的载具坐标，供 `/pickaxeride move` 使用。
 *
 * Minestom 自己从不下发 `move_vehicle`（它的载具移动只接受上行、不纠正），所以这里
 * 由 harness 直接发一个真实形状的包：要验证的是本端如何解释它，不是 Minestom 的载具物理。
 */
public const val RIDE_PUSH_X: Double = 6.5
public const val RIDE_PUSH_Y: Double = 40.0
public const val RIDE_PUSH_Z: Double = 0.5
public const val RIDE_PUSH_YAW: Float = 180f
public const val RIDE_PUSH_PITCH: Float = 5f

/** 转移命令：把执行者转移到本 harness 自己，用来验证完整换服链路。 */
public const val TRANSFER_COMMAND_NAME: String = "pickaxetransfer"

/** echo 命令回给执行者的固定文本；客户端以系统消息读到它。 */
public const val ECHO_COMMAND_REPLY: String = "pickaxe-ok"

/**
 * 带补全的命令名与候选。
 *
 * 补全必须由服务端算：候选依赖权限、世界状态与实体，客户端本地推不出来。
 * 这里固定一组候选，让“服务端说了算”成为可断言的事实。
 */
public const val SUGGEST_COMMAND_NAME: String = "pickaxecolor"

/** 补全候选；客户端断言直接引用，避免两侧各写一份字符串。 */
public val SUGGEST_CANDIDATES: List<String> = listOf("red", "green", "blue")

/** 出生平台高度：只铺一层够站立的石头，不生成地形，联调时无需等待区块生成。 */
private const val GROUND_HEIGHT: Int = 40

/**
 * 本进程是否已经启动过 harness。
 *
 * 不能用 `MinecraftServer.isStarted()` 代替：该方法在 `init` 之前会因
 * `serverProcess` 为 null 抛 NPE，恰好会在“首次启动”这个最需要它的时刻崩掉。
 */
private var harnessStarted: Boolean = false

/** 最近一次启动使用的端口；转移命令要回到同一个服务端。 */
private var harnessPort: Int = DEFAULT_PORT

/**
 * 服务端侧的玩家里程碑，供联调用例断言“协议链路真的走通了”。
 *
 * `SPAWNED` 来自 [PlayerSpawnEvent]：玩家被放进实例，说明 Login、配置收尾与出生
 * 都是服务端认可的。`LOADED` 来自 [PlayerLoadedEvent]：客户端回送了 player_loaded，
 * 说明 play 阶段的上行包能被服务端正常处理。
 */
public enum class PlayerMilestone {
    SPAWNED,
    LOADED,
}

private val milestones = ConcurrentHashMap<String, ConcurrentHashMap<PlayerMilestone, CompletableFuture<Unit>>>()

/**
 * 服务端侧的事实登记簿，键是「类别|玩家|内容」。
 *
 * 里程碑、聊天、命令共用一张表：它们的语义都是“服务端真的收到了某件事”，
 * 只是键的形状不同。用例据此断言服务端侧结果，而不是只看客户端自己的回显。
 */
private val facts = ConcurrentHashMap<String, CompletableFuture<Unit>>()

/** 玩家名 → 当前乘坐的载具实体；下车后移除。 */
private val riders = ConcurrentHashMap<String, Entity>()

private fun fact(key: String): CompletableFuture<Unit> = facts.computeIfAbsent(key) { CompletableFuture() }

private fun recordFact(key: String) {
    fact(key).complete(Unit)
}

private fun awaitFact(key: String, timeout: Duration): Boolean {
    val future = fact(key)
    return try {
        future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        true
    } catch (e: java.util.concurrent.TimeoutException) {
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

private fun milestoneFuture(username: String, milestone: PlayerMilestone): CompletableFuture<Unit> =
    milestones
        .computeIfAbsent(username) { ConcurrentHashMap() }
        .computeIfAbsent(milestone) { CompletableFuture() }

private fun recordMilestone(player: Player, milestone: PlayerMilestone) {
    milestoneFuture(player.username, milestone).complete(Unit)
}

/**
 * 等待某个玩家达到指定里程碑。
 *
 * 超时返回 false 而不是抛异常：用例可以据此打印服务端实际进度，定位卡在哪一步。
 */
public fun awaitMilestone(username: String, milestone: PlayerMilestone, timeout: Duration): Boolean {
    val future = milestoneFuture(username, milestone)
    return try {
        future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        true
    } catch (e: java.util.concurrent.TimeoutException) {
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

/** 等待服务端收到某玩家发出的聊天正文；超时返回 false。 */
public fun awaitPlayerChat(username: String, message: String, timeout: Duration): Boolean =
    awaitFact(chatFactKey(username, message), timeout)

/** 等待某玩家的命令执行到服务端的 echo 命令；[input] 是不带斜杠的完整命令串。 */
public fun awaitCommand(username: String, input: String, timeout: Duration): Boolean =
    awaitFact(commandFactKey(username, input), timeout)

/**
 * 服务端观察到的一次移动。
 *
 * 坐标是**客户端提交的目标位置**（[PlayerMoveEvent.newPosition]），不是服务端最终落点：
 * 两者在事件被取消或被其它监听器改写时会不同，而这里要证明的正是“包里的坐标确实是这个”。
 */
public data class ObservedMove(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
)

/**
 * 每个玩家被观察到的移动序列，按到达顺序。
 *
 * 用序列而不是“事实集合”：移动是连续量，按坐标做键会因精度差异永远对不上；
 * 用例需要的是“有没有出现过一次落在容差内的移动”。
 */
private val moves = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<ObservedMove>>()

/** 某玩家到目前为止被观察到的移动；没有时返回空列表。 */
public fun observedMoves(username: String): List<ObservedMove> = moves[username]?.toList().orEmpty()

/**
 * 等待某玩家出现一次满足条件的移动；超时返回 false。
 *
 * 轮询而不是注册回调：调用方通常在协议事件到达后才开始等，早先到达的移动必须能被看到。
 */
public fun awaitPlayerMove(username: String, timeout: Duration, predicate: (ObservedMove) -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (observedMoves(username).any(predicate)) return true
        Thread.sleep(5)
    }
    return observedMoves(username).any(predicate)
}

/**
 * 服务端观察到的一次载具移动（`move_vehicle` 上行）。
 *
 * 只记录包里的值：Minestom 的内置监听器在 `PlayerPacketEvent` **之后**才把坐标应用到
 * 载具上，所以这里证明的是“包确实到了、值确实是这些”，落点另用 [ridePosition] 核对。
 */
public data class ObservedVehicleMove(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
)

private val vehicleMoves = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<ObservedVehicleMove>>()

/** 某玩家到目前为止被观察到的载具移动；没有时返回空列表。 */
public fun observedVehicleMoves(username: String): List<ObservedVehicleMove> =
    vehicleMoves[username]?.toList().orEmpty()

/** 等待某玩家出现一次满足条件的载具移动；超时返回 false。 */
public fun awaitVehicleMove(username: String, timeout: Duration, predicate: (ObservedVehicleMove) -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (observedVehicleMoves(username).any(predicate)) return true
        Thread.sleep(5)
    }
    return observedVehicleMoves(username).any(predicate)
}

/** 某玩家当前乘坐的载具坐标；不在载具上或载具已消失时返回 null。 */
public fun ridePosition(username: String): Pos? = riders[username]?.position

/**
 * 服务端观察到的一次上行插件消息。
 *
 * [data] 是事件里那份字节数组的副本：客户端发回的字节能否逐字节对得上，
 * 正是插件消息链路的验收标准。
 */
public class ObservedPluginMessage(public val channel: String, data: ByteArray) {
    public val data: ByteArray = data.copyOf()

    override fun toString(): String = "ObservedPluginMessage(channel=$channel, ${data.size} bytes)"
}

private val pluginMessages = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<ObservedPluginMessage>>()

/** 某玩家到目前为止被观察到的上行插件消息；没有时返回空列表。 */
public fun observedPluginMessages(username: String): List<ObservedPluginMessage> =
    pluginMessages[username]?.toList().orEmpty()

/** 等待某玩家发出一条满足条件的插件消息；超时返回 false。 */
public fun awaitPlayerPluginMessage(
    username: String,
    timeout: Duration,
    predicate: (ObservedPluginMessage) -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (observedPluginMessages(username).any(predicate)) return true
        Thread.sleep(5)
    }
    return observedPluginMessages(username).any(predicate)
}
/** 等待服务端把载具移动到满足条件的位置（即 Minestom 真的应用了上行包）；超时返回 false。 */
public fun awaitRidePosition(username: String, timeout: Duration, predicate: (Pos) -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        ridePosition(username)?.let { if (predicate(it)) return true }
        Thread.sleep(5)
    }
    return ridePosition(username)?.let(predicate) == true
}

/** 等待服务端把某玩家放上了载具；超时返回 false。 */
public fun awaitRide(username: String, timeout: Duration): Boolean =
    awaitFact(rideFactKey(username, "mount"), timeout)

/** 等待服务端把某玩家从载具上放下来；超时返回 false。 */
public fun awaitDismount(username: String, timeout: Duration): Boolean =
    awaitFact(rideFactKey(username, "dismount"), timeout)

private fun rideFactKey(username: String, kind: String): String = "ride|$kind|$username"

private fun chatFactKey(username: String, message: String): String = "chat|$username|$message"

private fun commandFactKey(username: String, input: String): String = "command|$username|$input"

private fun blockFactKey(kind: String, username: String, x: Int, y: Int, z: Int): String =
    "$kind|$username|$x,$y,$z"

/** 等待某玩家在服务端真正完成一次挖掘（方块被打碎）；超时返回 false。 */
public fun awaitPlayerDig(username: String, x: Int, y: Int, z: Int, timeout: Duration): Boolean =
    awaitFact(blockFactKey("dig", username, x, y, z), timeout)

/** 等待某玩家在服务端真正放置了一个方块；超时返回 false。 */
public fun awaitPlayerPlace(username: String, x: Int, y: Int, z: Int, timeout: Duration): Boolean =
    awaitFact(blockFactKey("place", username, x, y, z), timeout)

/** 等待服务端确认某玩家攻击了一次实体；超时返回 false。 */
public fun awaitEntityAttack(username: String, timeout: Duration): Boolean =
    awaitFact(entityFactKey("attack", username), timeout)

/** 等待服务端确认某玩家与一次实体交互（右键）；超时返回 false。 */
public fun awaitEntityInteract(username: String, timeout: Duration): Boolean =
    awaitFact(entityFactKey("interact", username), timeout)

private fun entityFactKey(kind: String, username: String): String = "entity|$kind|$username"

/**
 * 在实例里放一只用于联调的生物（僵尸），返回它。
 *
 * 僵尸是 LivingEntity：出生时属性包会推给所有观察者（含本端客户端），
 * 被攻击时走 Minestom 的伤害管线并下发伤害事件包——两条链路都能给出双侧证据。
 */
public fun spawnZombie(instance: InstanceContainer, x: Double, y: Double, z: Double): EntityCreature =
    EntityCreature(EntityType.ZOMBIE).apply {
        setInstance(instance, Pos(x, y, z))
    }

/**
 * 让服务端算好 [x, y, z] 所在 section 的光照并立刻重发。
 *
 * Minestom 的光照是**懒计算 + 延时重发**：区块会在光照算完之前先发出去，内容是全 0；
 * 方块变更后默认还要等 100 tick 才重发（联调通过系统属性压到 2 tick）。这只影响
 * “什么时候算、什么时候发”，不影响算出来的值。要让用例断言“光照真的穿过协议”，
 * 就得先让服务端算一次再要求它下发——这里调的都是服务端的公开接口，
 * 交出去的是它自己算的光，不是伪造的数据。
 *
 * @return 该坐标没有已加载的区块时返回 false
 */
public fun pushLight(instance: InstanceContainer, x: Int, y: Int, z: Int): Boolean {
    // getSkyLight / getBlockLight 在 section 需要更新时会触发一次重算。
    val sky = instance.getSkyLight(x, y, z)
    val block = instance.getBlockLight(x, y, z)
    val chunk = instance.getChunk(x shr 4, z shr 4) as? LightingChunk ?: return false
    chunk.sendLighting()
    println("event=server.light pos=($x, $y, $z) sky=$sky block=$block")
    return true
}

/**
 * 服务端侧改一只生物的血量。
 *
 * Minestom 的 `LivingEntity#setHealth` 会同步写进 `LivingEntityMeta`，下一 tick 由
 * `set_entity_data` 推给观察者；这是「客户端能读到生物血量」的服务端侧入口。
 * 值会被最大生命属性截断，联调用的值必须小于默认上限。
 */
public fun setEntityHealth(entity: LivingEntity, health: Float) {
    entity.health = health
    println("event=server.health id=${entity.entityId} health=${entity.health}")
}

/**
 * 把一个带自定义名称的物品塞进玩家背包，返回是否成功。
 *
 * 背包更新会随 `set_player_inventory` 下发给客户端——这是「物品键名与自定义名
 * 能穿过协议」的服务端侧入口。
 */
public fun giveNamedItem(username: String, material: Material, count: Int, name: String?): Boolean {
    val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(username) ?: return false
    val builder = ItemStack.builder(material).amount(count)
    // null 表示不设组件（区别于“设一个空名字”——那是真实存在的组件值）。
    val stack = if (name != null) builder.set(DataComponents.CUSTOM_NAME, Component.text(name)).build()
    else builder.build()
    val added = player.inventory.addItemStack(stack)
    println("event=server.give name=$username item=${material.key()} count=$count named=$name added=$added")
    return added
}

/**
 * 塞入一个**不设任何组件**的物品。
 *
 * 与 [giveNamedItem] 的区别是连名字都不设：组件补丁为空，服务端只会下发 id 与数量，
 * 耐久与堆叠上限只能由客户端查自己的物品原型表补齐——这是验证原型合并的入口。
 */
public fun givePlainItem(username: String, material: Material, count: Int): Boolean {
    val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(username) ?: return false
    val added = player.inventory.addItemStack(ItemStack.of(material, count))
    println("event=server.give.plain name=$username item=${material.key()} count=$count added=$added")
    return added
}

/**
 * 塞一把把常用组件都设满的钻石剑，用于验证客户端能完整读出物品组件。
 *
 * 附魔用 `RegistryKey` 而不是枚举常量：附魔是可由服务端扩展的动态注册表，键名由配置阶段
 * 下发的 `minecraft:enchantment` 注册表还原，客户端不该硬编码一张表。
 */
public fun giveComponentItem(username: String): Boolean {
    val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(username) ?: return false
    val stack = ItemStack.builder(Material.DIAMOND_SWORD)
        .set(DataComponents.CUSTOM_NAME, Component.text(COMPONENT_SWORD_NAME))
        .set(DataComponents.ITEM_NAME, Component.text(COMPONENT_ITEM_NAME))
        .set(DataComponents.LORE, listOf(Component.text("第一行"), Component.text("第二行")))
        .set(DataComponents.DAMAGE, COMPONENT_DAMAGE)
        .set(DataComponents.MAX_DAMAGE, COMPONENT_MAX_DAMAGE)
        .set(DataComponents.REPAIR_COST, COMPONENT_REPAIR_COST)
        .set(DataComponents.UNBREAKABLE, MinestomUnit.INSTANCE)
        .set(DataComponents.ENCHANTMENTS, EnchantmentList(RegistryKey.of<Enchantment>("sharpness"), 3))
        .build()
    val added = player.inventory.addItemStack(stack)
    println("event=server.item.components name=$username added=$added")
    return added
}

public const val COMPONENT_SWORD_NAME: String = "试剑"
public const val COMPONENT_ITEM_NAME: String = "钻石剑"
public const val COMPONENT_DAMAGE: Int = 7
public const val COMPONENT_MAX_DAMAGE: Int = 2000
public const val COMPONENT_REPAIR_COST: Int = 2

/**
 * 修改玩家的属性基值（服务端侧），并把新值打印出来。
 *
 * Minestom 在属性变化时会**主动把属性包推给本人**（PLAY 阶段直接 sendPacket），
 * 因此客户端不需要任何观察者就能收到这次变化——这正是属性链路最省事的地方。
 */
public fun setPlayerAttribute(username: String, attribute: Attribute, value: Double): Boolean {
    val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(username) ?: return false
    player.getAttribute(attribute).setBaseValue(value)
    println("event=server.attribute name=$username attribute=${attribute.key()} value=$value")
    return true
}

/**
 * 服务端观察到的上行 `player_input` 包序列。
 *
 * 这些包没有回执，也没有对应的高层事件；`PlayerPacketEvent` 是唯一能证明
 * “服务端真的收到了这一组按键位”的落点。
 */
private val inputs = ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<ClientInputPacket>>()

/** 某玩家到目前为止被观察到的输入包；没有时返回空列表。 */
public fun observedInputs(username: String): List<ClientInputPacket> = inputs[username]?.toList().orEmpty()

/** 等待一次满足条件的输入包；超时返回 false。 */
public fun awaitPlayerInput(username: String, timeout: Duration, predicate: (ClientInputPacket) -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (observedInputs(username).any(predicate)) return true
        Thread.sleep(5)
    }
    return observedInputs(username).any(predicate)
}

/** 等待服务端观察到某类上行包（键见 [PlayerPacketEvent] 监听器）。 */
public fun awaitPlayerPacket(username: String, kind: String, timeout: Duration): Boolean =
    awaitFact(packetFactKey(username, kind), timeout)

private fun packetFactKey(username: String, kind: String): String = "packet|$username|$kind"

private fun onlinePlayer(username: String): Player? =
    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(username)

/** 直接给玩家下发一份权威能力包（flags 位与原版一致：1 无敌 / 2 飞行中 / 4 可飞 / 8 创造）。 */
public fun sendAbilities(username: String, flags: Int, flySpeed: Float, walkSpeed: Float): Boolean {
    val player = onlinePlayer(username) ?: return false
    player.sendPacket(PlayerAbilitiesPacket(flags.toByte(), flySpeed, walkSpeed))
    println("event=server.abilities name=$username flags=$flags flySpeed=$flySpeed walkSpeed=$walkSpeed")
    return true
}

/** 给某实体下发一个状态效果；[effectKey] 是 `minecraft:*`，由 Minestom 注册表解析。 */
public fun sendMobEffect(
    username: String,
    entityId: Int,
    effectKey: String,
    amplifier: Int,
    duration: Int,
    flags: Int,
): Boolean {
    val player = onlinePlayer(username) ?: return false
    val effect = PotionEffect.fromKey(effectKey) ?: return false
    player.sendPacket(EntityEffectPacket(entityId, Potion(effect, amplifier, duration, flags.toByte())))
    println("event=server.effect name=$username entity=$entityId effect=$effectKey amplifier=$amplifier duration=$duration")
    return true
}

/** 移除某实体的一个状态效果。 */
public fun sendRemoveMobEffect(username: String, entityId: Int, effectKey: String): Boolean {
    val player = onlinePlayer(username) ?: return false
    val effect = PotionEffect.fromKey(effectKey) ?: return false
    player.sendPacket(RemoveEntityEffectPacket(entityId, effect))
    println("event=server.effect.remove name=$username entity=$entityId effect=$effectKey")
    return true
}

/** 下发一次窗口数值属性（熔炉进度之类）。 */
public fun sendWindowProperty(username: String, windowId: Int, property: Int, value: Int): Boolean {
    val player = onlinePlayer(username) ?: return false
    player.sendPacket(WindowPropertyPacket(windowId, property.toShort(), value.toShort()))
    println("event=server.window.property name=$username window=$windowId property=$property value=$value")
    return true
}

/** 下发一次跨服转移请求；本 harness 只验证客户端收到，不会真的连第二个服务端。 */
public fun sendTransfer(username: String, host: String, port: Int): Boolean {
    val player = onlinePlayer(username) ?: return false
    player.sendPacket(TransferPacket(host, port))
    println("event=server.transfer name=$username host=$host port=$port")
    return true
}

/**
 * 下发一次插件消息（原样字节）。
 *
 * 品牌串用 [PluginMessagePacket.brandPacket] 或把 `data` 传成 varint 前缀编码的字符串；
 * 这里不代做编码——下游要验证的正是「客户端对裸字节的解码是否正确」。
 */
public fun sendPluginMessage(username: String, channel: String, data: ByteArray): Boolean {
    val player = onlinePlayer(username) ?: return false
    player.sendPacket(PluginMessagePacket(channel, data))
    println("event=server.plugin name=$username channel=$channel bytes=${data.size}")
    return true
}

/** 下发一次 `minecraft:brand` 品牌消息（varint 长度前缀 + UTF-8，由 Minestom 编码）。 */
public fun sendBrand(username: String, brand: String): Boolean {
    val player = onlinePlayer(username) ?: return false
    player.sendPacket(PluginMessagePacket.brandPacket(brand))
    println("event=server.brand name=$username brand=$brand")
    return true
}

/** 改玩家游戏模式；Minestom 会广播玩家列表的 UPDATE_GAME_MODE（本人也在内）。 */
public fun setGameMode(username: String, gameMode: String): Boolean {
    val player = onlinePlayer(username) ?: return false
    val mode = GameMode.valueOf(gameMode)
    val changed = player.setGameMode(mode)
    println("event=server.gamemode name=$username mode=$mode changed=$changed")
    return changed
}

/**
 * 读服务端视角下玩家背包某格的物品；[windowSlot] 是窗口 0 编号（与本客户端一致），
 * 内部按 Minestom 的表换算成它自己的槽位编号。
 *
 * 拖拽与创造模式的验收都靠它：服务端背包才是权威结果，客户端读到的内容必须与它对账。
 */
public fun serverInventorySlot(username: String, windowSlot: Int): ItemStack? {
    val player = onlinePlayer(username) ?: return null
    val minestomSlot = PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(windowSlot)
    return player.inventory.getItemStack(minestomSlot)
}

/** 等待服务端收到某玩家对指定窗口槽位的创造模式写入；超时返回 false。 */
public fun awaitCreativeAction(username: String, windowSlot: Int, timeout: Duration): Boolean =
    awaitFact("creative|$username|$windowSlot", timeout)

/**
 * 向 [username] 的客户端下发一条破坏进度包。
 *
 * Minestom 不会为挖掘广播裂纹（它的挖掘是简化实现），这条链路只能由 harness 以
 * 服务端权威的形状直接下发：[breakerEntityId] 是破坏者的实体 id，[stage] 是线上
 * 字节——0–9 为阶段 1–10，10 为 RESET（进度消失），与协议编码一一对应。
 */
public fun sendBlockBreakAnimation(
    username: String,
    breakerEntityId: Int,
    x: Int,
    y: Int,
    z: Int,
    stage: Byte,
): Boolean {
    val player = onlinePlayer(username) ?: return false
    player.sendPacket(BlockBreakAnimationPacket(breakerEntityId, Vec(x.toDouble(), y.toDouble(), z.toDouble()), stage))
    println("event=server.break_animation name=$username breaker=$breakerEntityId pos=($x,$y,$z) stage=$stage")
    return true
}

/** 回复一次命令补全；matches 的顺序与内容由服务端侧写死，客户端只负责读。 */
public fun sendTabComplete(
    username: String,
    transactionId: Int,
    start: Int,
    length: Int,
    matches: List<String>,
): Boolean {
    val player = onlinePlayer(username) ?: return false
    player.sendPacket(
        TabCompletePacket(transactionId, start, length, matches.map { TabCompletePacket.Match(it, null) }),
    )
    println("event=server.suggestions name=$username transaction=$transactionId matches=$matches")
    return true
}

/**
 * 启动一个仅供联调的最小服务端：离线认证 + 40 格高的石头平面。
 *
 * 启动前会校验本仓库锁定的协议号与 Minestom 自带常量一致；不一致直接抛错，
 * 避免用错版本的服务端去验证客户端。
 *
 * 必须用 [MinecraftServer.start] 而不是 `process().start()`：后者只打开监听端口，
 * 既不启动线程调度器也不启动 tick 线程，玩家会一直停在 `playWaitingPlayers` 队列里，
 * 表现为服务端只剩 `player.configuring` 日志、客户端收不到 Login 包。
 *
 * @return 出生实例，测试可用来触发区块生成或检查方块。
 */
public fun startHarness(port: Int = DEFAULT_PORT): InstanceContainer {
    // Minestom 是进程级单例，重复初始化会抛出难以定位的注册表错误，这里提前给出可读原因。
    check(!harnessStarted) {
        "Minestom 只允许初始化一次；当前进程已启动过服务端，请在新进程中启动联调服务。"
    }

    val ourVersion = ProtocolVersion.PROTOCOL_VERSION
    require(ourVersion == MinecraftServer.PROTOCOL_VERSION) {
        "协议版本不一致：本仓库锁定 $ourVersion，Minestom 为 ${MinecraftServer.PROTOCOL_VERSION}。" +
            "请同步 schema 或 Minestom 版本。"
    }

    val server = MinecraftServer.init(Auth.Offline())

    val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
    // Minestom 默认用 DynamicChunk，它不计算光照：发出去的区块光照全是 0。真实服务端不是这样，
    // 因此联调实例改用 LightingChunk——这样“光照穿过协议”才是一个可核对的场景。
    instance.setChunkSupplier { inst, chunkX, chunkZ -> LightingChunk(inst, chunkX, chunkZ) }
    instance.setGenerator(Generator { unit -> unit.modifier().fillHeight(0, GROUND_HEIGHT, Block.fromKey("stone")) })

    // 玩家进入 configuration 阶段前必须有出生实例。缺这一步时 ConnectionManager 会抛
    // “You need to specify a spawning instance in the AsyncPlayerConfigurationEvent”，
    // 客户端停在配置阶段，看不到任何错误提示。
    MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
        println("event=player.configuring name=${event.player.username}")
        event.spawningInstance = instance
        // 玩家默认重生点是 Pos.ZERO，会站在石头平面内部（y=0）；显式放到平台顶上，
        // 这样“脚下方块是石头、上方是空气”才是一个可核对的事实。
        event.player.setRespawnPoint(Pos(0.5, GROUND_HEIGHT.toDouble(), 0.5))
    }
    MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent::class.java) { event ->
        println("event=player.spawned name=${event.player.username}")
        recordMilestone(event.player, PlayerMilestone.SPAWNED)
    }
    MinecraftServer.getGlobalEventHandler().addListener(PlayerLoadedEvent::class.java) { event ->
        println("event=player.loaded name=${event.player.username}")
        recordMilestone(event.player, PlayerMilestone.LOADED)
    }
    // 聊天上行：服务端收到的原文就是证据。客户端自己的回显还要经过广播与解码，
    // 只能证明“链路能回环”，不能证明服务端认可了这条消息。
    MinecraftServer.getGlobalEventHandler().addListener(PlayerChatEvent::class.java) { event ->
        println("event=player.chat name=${event.player.username} message=${event.rawMessage}")
        recordFact(chatFactKey(event.player.username, event.rawMessage))
        // Minestom 默认用可翻译组件（`chat.type.text`）广播，而本客户端没有语言文件，
        // 只能渲染出键名——那样回显就无法与正文对账。这里换成字面文本，让“服务端把这段
        // 字原样发回来了”成为可断言的事实；真实服务端走带签名的玩家聊天包，是另一条路径。
        event.formattedMessage = Component.text("${event.player.username}: ${event.rawMessage}")
    }
    // 移动上行：事件里带的是客户端提交的目标位置，是“包里的坐标真的到了服务端”的直接证据。
    // 只落 onGround 的状态包不会触发这个事件（服务端只刷新落地状态），联调用例对此另作断言。
    MinecraftServer.getGlobalEventHandler().addListener(PlayerMoveEvent::class.java) { event ->
        val target = event.newPosition
        println(
            "event=player.move name=${event.player.username} " +
                "x=${target.x()} y=${target.y()} z=${target.z()} " +
                "yaw=${target.yaw()} pitch=${target.pitch()}",
        )
        moves.computeIfAbsent(event.player.username) { java.util.concurrent.CopyOnWriteArrayList() }
            .add(
                ObservedMove(
                    x = target.x(),
                    y = target.y(),
                    z = target.z(),
                    yaw = target.yaw(),
                    pitch = target.pitch(),
                    onGround = event.isOnGround,
                ),
            )
    }
    // 方块交互：完成挖掘与放置都登记事实并打印。开始挖掘不登记——它只是“服务端开始计时”，
    // 方块还没有变化；完成才是「世界真的变了」的那一步。
    MinecraftServer.getGlobalEventHandler().addListener(PlayerFinishDiggingEvent::class.java) { event ->
        val position = event.blockPosition
        println(
            "event=player.dig.finish name=${event.player.username} " +
                "x=${position.blockX()} y=${position.blockY()} z=${position.blockZ()}",
        )
        recordFact(
            blockFactKey("dig", event.player.username, position.blockX(), position.blockY(), position.blockZ()),
        )
    }
    MinecraftServer.getGlobalEventHandler().addListener(PlayerBlockPlaceEvent::class.java) { event ->
        val position = event.blockPosition
        println(
            "event=player.place name=${event.player.username} block=${event.block.name()} " +
                "x=${position.blockX()} y=${position.blockY()} z=${position.blockZ()}",
        )
        recordFact(
            blockFactKey("place", event.player.username, position.blockX(), position.blockY(), position.blockZ()),
        )
    }
    // 实体攻击：Minestom 只在包里目标有效时调用事件（不在视野/已死则静默忽略）。
    // 默认实现不自动结算伤害，这里应用一次固定伤害，让「攻击真的打中了」同时具备
    // 服务端事实（事件）与客户端证据（damage_event 包 + 属性/血量变化）。
    MinecraftServer.getGlobalEventHandler().addListener(EntityAttackEvent::class.java) { event ->
        val attackerName = (event.entity as? Player)?.username ?: "unknown"
        println(
            "event=entity.attacked name=$attackerName target=${event.target.entityType.name()} " +
                "id=${event.target.entityId}",
        )
        recordFact(entityFactKey("attack", attackerName))
        (event.target as? net.minestom.server.entity.LivingEntity)?.damage(DamageType.PLAYER_ATTACK, 3.0f)
    }
    // 实体交互（右键）：登记「谁右键了谁」，手与交互点都来自包本身。
    MinecraftServer.getGlobalEventHandler().addListener(PlayerEntityInteractEvent::class.java) { event ->
        println(
            "event=entity.interacted name=${event.player.username} target=${event.target.entityType.name()} " +
                "id=${event.target.entityId} hand=${event.hand}",
        )
        recordFact(entityFactKey("interact", event.player.username))
    }
    // 上行包事实：输入、实体动作、飞行请求与补全请求都没有回执包，
    // PlayerPacketEvent 是唯一能证明“服务端确实收到了”的落点。
    MinecraftServer.getGlobalEventHandler().addListener(PlayerPacketEvent::class.java) { event ->
        val name = event.player.username
        when (val packet = event.packet) {
            is ClientInputPacket -> {
                inputs.computeIfAbsent(name) { java.util.concurrent.CopyOnWriteArrayList() }.add(packet)
                recordFact(packetFactKey(name, "input"))
                if (packet.shift()) recordFact(packetFactKey(name, "sneak"))
                if (packet.sprint()) recordFact(packetFactKey(name, "sprint"))
                println("event=player.input name=$name shift=${packet.shift()} sprint=${packet.sprint()}")
            }
            is ClientEntityActionPacket -> {
                recordFact(packetFactKey(name, "command.${packet.action()}"))
                println("event=player.command name=$name action=${packet.action()} entity=${packet.playerId()}")
            }
            is ClientPlayerAbilitiesPacket -> {
                val flying = (packet.flags().toInt() and 0x02) != 0
                recordFact(packetFactKey(name, "flying.$flying"))
                println("event=player.abilities name=$name flying=$flying")
            }
            is ClientVehicleMovePacket -> {
                val position = packet.position()
                vehicleMoves.computeIfAbsent(name) { java.util.concurrent.CopyOnWriteArrayList() }
                    .add(
                        ObservedVehicleMove(
                            x = position.x(),
                            y = position.y(),
                            z = position.z(),
                            yaw = position.yaw(),
                            pitch = position.pitch(),
                            onGround = packet.onGround(),
                        ),
                    )
                println(
                    "event=player.vehicle name=$name x=${position.x()} y=${position.y()} " +
                        "z=${position.z()} yaw=${position.yaw()} pitch=${position.pitch()} " +
                        "onGround=${packet.onGround()}",
                )
            }
            is ClientTabCompletePacket -> {
                recordFact(packetFactKey(name, "suggest.${packet.text()}"))
                println("event=player.suggest name=$name transaction=${packet.transactionId()} text=${packet.text()}")
            }
            // 拖拽：add 与 begin 不产生可观察动作，end（-999 + 按钮 2/6/10）才结算整次拖拽，
            // 记录它就是「服务端按哪组槽位结算了哪种拖拽」的直接证据。
            is ClientClickWindowPacket -> {
                if (
                    packet.clickType() == ClientClickWindowPacket.ClickType.QUICK_CRAFT &&
                    packet.slot() == (-999).toShort() &&
                    packet.button().toInt() in intArrayOf(2, 6, 10)
                ) {
                    recordFact(packetFactKey(name, "drag.${packet.button()}"))
                    println("event=player.drag name=$name endButton=${packet.button()}")
                }
            }
            else -> Unit
        }
    }
    // 上行插件消息：PlayerPluginMessageEvent 是「客户端发了这条 channel + 字节」的直接证据。
    MinecraftServer.getGlobalEventHandler().addListener(PlayerPluginMessageEvent::class.java) { event ->
        val name = event.player.username
        pluginMessages.computeIfAbsent(name) { java.util.concurrent.CopyOnWriteArrayList() }
            .add(ObservedPluginMessage(event.identifier, event.message))
        println(
            "event=player.plugin name=$name channel=${event.identifier} bytes=${event.message.size} " +
                "utf8=${event.messageString}",
        )
    }
    // 创造模式写入：事件里的槽位是 Minestom 内部编号，换算回窗口 0 编号再登记，
    // 与 serverInventorySlot 的入参保持同一套编号。
    MinecraftServer.getGlobalEventHandler().addListener(CreativeInventoryActionEvent::class.java) { event ->
        val name = event.player.username
        val windowSlot = PlayerInventoryUtils.convertMinestomSlotToWindowSlot(event.slot)
        recordFact("creative|$name|$windowSlot")
        println(
            "event=player.creative name=$name windowSlot=$windowSlot " +
                "item=${event.clickedItem.material().name()} count=${event.clickedItem.amount()}",
        )
    }
    MinecraftServer.getCommandManager().register(EchoCommand())
    MinecraftServer.getCommandManager().register(SuggestColorCommand())
    MinecraftServer.getCommandManager().register(TransferCommand())
    MinecraftServer.getCommandManager().register(RideCommand())

    harnessPort = port
    server.start(InetSocketAddress(DEFAULT_HOST, port))
    harnessStarted = true
    return instance
}

/**
 * 骑乘命令：`/<名字>` 上船，`/<名字> stop` 下船。
 *
 * 载具就在玩家脚下生成，不用真实载具物理——本命令要验证的是「乘客关系走协议」，
 * 不是船会不会浮起来。
 */
private class RideCommand : Command(RIDE_COMMAND_NAME) {
    init {
        addSyntax({ sender, _ -> dismount(sender) }, ArgumentType.Literal("stop"))
        addSyntax({ sender, _ -> pushVehicle(sender) }, ArgumentType.Literal("move"))
        setDefaultExecutor { sender, _ -> mount(sender) }
    }

    private fun mount(sender: CommandSender) {
        val player = sender as? Player ?: return
        val instance = player.instance
        val vehicle = Entity(EntityType.OAK_BOAT)
        vehicle.setInstance(instance, player.position)
        vehicle.addPassenger(player)
        riders[player.username] = vehicle
        println(
            "event=server.ride name=${player.username} vehicle=${vehicle.entityId} " +
                "type=$RIDE_ENTITY_TYPE passengers=${vehicle.getPassengers().map { it.entityId }}",
        )
        recordFact(rideFactKey(player.username, "mount"))
    }

    /** 以服务端身份下发一次权威载具坐标；客户端应把它折到载具快照与自己的位置上。 */
    private fun pushVehicle(sender: CommandSender) {
        val player = sender as? Player ?: return
        val position = Pos(RIDE_PUSH_X, RIDE_PUSH_Y, RIDE_PUSH_Z, RIDE_PUSH_YAW, RIDE_PUSH_PITCH)
        player.sendPacket(VehicleMovePacket(position))
        println("event=server.vehicle.move name=${player.username} pos=$position")
    }

    private fun dismount(sender: CommandSender) {
        val player = sender as? Player ?: return
        val vehicle = riders.remove(player.username) ?: return
        vehicle.removePassenger(player)
        println("event=server.ride.stop name=${player.username} vehicle=${vehicle.entityId}")
        recordFact(rideFactKey(player.username, "dismount"))
    }
}

/**
 * 转移命令：`/<名字>`，把执行者转移到本 harness 自己。
 *
 * 转移目标指向自己而不是另一个服务端，是因为 Minestom 是进程级单例，一个 JVM 里
 * 跑不了第二个服务端；同名重连正好还能证明旧连接确实关掉了。
 */
private class TransferCommand : Command(TRANSFER_COMMAND_NAME) {
    init {
        setDefaultExecutor { sender, _ ->
            val player = sender as? Player ?: return@setDefaultExecutor
            player.sendPacket(TransferPacket(DEFAULT_HOST, harnessPort))
            println("event=server.transfer name=${player.username} host=$DEFAULT_HOST port=$harnessPort")
        }
    }
}

/** 显式停止服务端；未启动或重复调用都无副作用。 */
public fun stopHarness() {
    if (!harnessStarted) return
    // 先复位状态：服务端停止过程中若再次进入（例如 hook 与测试重复调用），不会重复停止。
    harnessStarted = false
    if (MinecraftServer.isStarted() && !MinecraftServer.isStopping()) {
        MinecraftServer.process().stop()
    }
}

/**
 * 最小 echo 命令：登记「谁执行了什么」，再回一条固定文本。
 *
 * 带一个贪婪字符串参数，因此 `pickaxe hello world` 这种带空格的输入也能落到执行器；
 * 同时保留默认执行器，单独打 `/pickaxe` 也能执行。这条命令的用途是证明命令包里的一段
 * 字符串完整地穿过协议与命令解析器，不是演示命令系统。
 */
private class EchoCommand : Command(ECHO_COMMAND_NAME) {
    init {
        addSyntax({ sender, context -> echo(sender, context) }, ArgumentType.StringArray("args"))
        setDefaultExecutor { sender, context -> echo(sender, context) }
    }

    private fun echo(sender: CommandSender, context: CommandContext) {
        (sender as? Player)?.let { player ->
            println("event=player.command name=${player.username} input=${context.input}")
            recordFact(commandFactKey(player.username, context.input))
        }
        sender.sendMessage(Component.text(ECHO_COMMAND_REPLY))
    }
}

/**
 * 带补全回调的最小命令：`/<名字> <颜色>`。
 *
 * 候选按已输入的前缀过滤——真实服务端也这么做，客户端的断言才有意义（收到的是
 * “red”而不是三个都回来）。前缀从 `suggestion.input` 里按最后一个空格切出来，
 * 不用它的 `start`：那个下标由服务端自己算，本命令不该依赖它的口径。
 *
 * Minestom 在自己的补全实现里会把前导斜杠去掉再解析，因此执行到这里时 `input`
 * 可能不带斜杠；这也是不能用固定偏移取前缀的原因之一。
 */
private class SuggestColorCommand : Command(SUGGEST_COMMAND_NAME) {
    init {
        val color = ArgumentType.Word("color").setSuggestionCallback { _, _, suggestion ->
            val typed = suggestion.input
                .substringAfterLast(' ')
                .trim('\u0000')
            SUGGEST_CANDIDATES
                .filter { it.startsWith(typed, ignoreCase = true) }
                .forEach { suggestion.addEntry(SuggestionEntry(it)) }
        }
        addSyntax({ sender, context ->
            val picked = context.get<String>(color)
            (sender as? Player)?.let { player ->
                println("event=player.suggest.exec name=${player.username} color=$picked")
                recordFact(commandFactKey(player.username, "/$SUGGEST_COMMAND_NAME $picked"))
            }
            sender.sendMessage(Component.text("color=$picked"))
        }, color)
        setDefaultExecutor { sender, _ -> sender.sendMessage(Component.text("color=-")) }
    }
}
