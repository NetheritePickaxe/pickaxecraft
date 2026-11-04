package org.pickaxe.testing.minestom

import net.kyori.adventure.text.Component
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerFinishDiggingEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
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

/** echo 命令回给执行者的固定文本；客户端以系统消息读到它。 */
public const val ECHO_COMMAND_REPLY: String = "pickaxe-ok"

/** 出生平台高度：只铺一层够站立的石头，不生成地形，联调时无需等待区块生成。 */
private const val GROUND_HEIGHT: Int = 40

/**
 * 本进程是否已经启动过 harness。
 *
 * 不能用 `MinecraftServer.isStarted()` 代替：该方法在 `init` 之前会因
 * `serverProcess` 为 null 抛 NPE，恰好会在“首次启动”这个最需要它的时刻崩掉。
 */
private var harnessStarted: Boolean = false

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
    MinecraftServer.getCommandManager().register(EchoCommand())

    server.start(InetSocketAddress(DEFAULT_HOST, port))
    harnessStarted = true
    return instance
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
