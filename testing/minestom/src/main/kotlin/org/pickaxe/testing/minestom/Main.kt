package org.pickaxe.testing.minestom

import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.DisguisedChatPacket
import org.pickaxe.protocol.ProtocolVersion
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** 演示模式下发给进服玩家的石头数量。 */
private const val DEMO_ITEM_COUNT: Int = 16

/** 联调服务的命令行用法。 */
/** 广播间隔：比一次 CLI 连接（默认 8 秒）短，保证至少能看到一条。 */
private const val CHAT_BROADCAST_INTERVAL_SECONDS: Int = 3

/** 每隔这么多轮发一条伪装聊天。 */
private const val DISGUISED_EVERY_ROUNDS: Int = 2

/** 伪装聊天用的格式（`[发送者] 正文`），与普通聊天的键不同，便于在日志里区分。 */
private const val ANNOUNCEMENT_FORMAT: String = "chat.type.announcement"

private const val USAGE: String = "用法: minestom [--port <1..65535>]，默认端口 $DEFAULT_PORT"

/**
 * 启动联调服务端并阻塞到收到停止信号。
 *
 * 关闭走 JVM shutdown hook（Ctrl+C 或 SIGTERM），显式调用服务端停止，
 * 不依赖进程被强制杀死，保证端口与线程正常释放。
 */
public fun main(args: Array<String>) {
    val port = parsePort(args)
    startHarness(port)
    startDemoGiveItems()
    startChatBroadcast()

    // 结束日志放在 hook 内：JVM 开始关闭后主线程可能先被终止，
    // 若把“已停止”写在 await 之后，日志会随机缺失。
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("event=server.stopping")
            stopHarness()
            println("event=server.stopped")
            stopped.countDown()
        },
    )

    // 必须等 hook 注册完再宣告就绪：调用方（测试或脚本）收到这一行就可能立即发信号，
    // 若先打印后注册，信号有概率落在两者之间，表现为“hook 完全没执行”。
    println(
        "event=server.started host=$DEFAULT_HOST port=$port " +
            "minecraft=${ProtocolVersion.MINECRAFT_VERSION} protocol=${ProtocolVersion.PROTOCOL_VERSION}",
    )

    stopped.await()
}

/**
 * 演示模式：给进服玩家发一组石头，让 `--place` 有东西可放。
 *
 * 只在命令行服务端启用：联调用例的服务端不发物品，避免污染对背包内容的精确断言。
 * 这里的 listener 注册在 [startHarness] 之后，是同一事件的第二个监听器，互不影响。
 */
private fun startDemoGiveItems() {
    MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent::class.java) { event ->
        event.player.setItemInMainHand(ItemStack.of(Material.STONE, DEMO_ITEM_COUNT))
    }
}

/**
 * 定时广播一条系统消息，让命令行客户端能直接看到聊天链路。
 *
 * 只在命令行服务端启用：测试用 [startHarness] 直接起服务，广播会污染用例里对
 * 聊天记录的精确断言。
 */
private fun startChatBroadcast() {
    // 伪装聊天的格式 id 要按注册表顺序取：包内编码是 id + 1，0 表示内联定义。
    val registry = MinecraftServer.getChatTypeRegistry()
    val announcement = registry.keys()
        .firstOrNull { registry.get(it)?.chat()?.translationKey() == ANNOUNCEMENT_FORMAT }
        ?.let { registry.getId(it) + 1 }

    var round = 0
    MinecraftServer.getSchedulerManager().buildTask {
        round++
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            player.sendMessage(Component.text("服务器广播 #$round"))
            // 每隔几轮补一条伪装聊天：它带名字但没有发送者 UUID，与系统消息不是一回事。
            if (announcement != null && round % DISGUISED_EVERY_ROUNDS == 0) {
                player.sendPacket(
                    DisguisedChatPacket(
                        Component.text("控制台播报 #$round"),
                        announcement,
                        Component.text("控制台"),
                        null,
                    ),
                )
            }
        }
    }.repeat(Duration.ofSeconds(CHAT_BROADCAST_INTERVAL_SECONDS.toLong())).schedule()
}

/**
 * 解析 `--port`。
 *
 * 支持自定义端口是为了让关闭路径的测试能在独立进程里挑一个空闲端口，
 * 不必和本机 25565 抢。
 */
private fun parsePort(args: Array<String>): Int {
    var port = DEFAULT_PORT
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--port" -> {
                val value = args.getOrNull(index + 1) ?: abort("--port 缺少端口号")
                port = value.toIntOrNull() ?: abort("--port 需要整数，实际为 “$value”")
                index++
            }

            else -> abort("未知参数 “$arg”")
        }
        index++
    }
    if (port !in 1..65535) abort("端口必须在 1..65535，实际为 $port")
    return port
}

private fun abort(message: String): Nothing {
    System.err.println(message)
    System.err.println(USAGE)
    exitProcess(2)
}
