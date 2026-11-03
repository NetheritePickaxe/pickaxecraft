package org.pickaxe.testing.minestom

import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerLoadedEvent
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
