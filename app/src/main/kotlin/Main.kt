package org.pickaxe.app

import kotlinx.coroutines.runBlocking
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.ClientException
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.Session
import org.pickaxe.protocol.ProtocolVersion
import org.pickaxe.protocol.ServerAddress
import kotlin.system.exitProcess

private val usage = """
    用法: pickaxecraft <命令> [参数]

    命令:
      status <host> [port] [--timeout <秒>]
          查询服务端状态并打印版本、人数与延迟

      connect <host> [port] [--username <名称>] [--timeout <秒>]
          离线登录并持续打印会话事件，Ctrl+C 关闭会话后退出

      --version   打印客户端版本与锁定的游戏/协议/服务端版本
      --help      打印本帮助

    不带参数时同样打印本帮助。
""".trimIndent()

private val versionInfo = """
    pickaxecraft ${BuildInfo.CLIENT_VERSION}
    minecraft ${ProtocolVersion.MINECRAFT_VERSION}
    protocol ${ProtocolVersion.PROTOCOL_VERSION}
    minestom ${BuildInfo.MINESTOM_VERSION}
""".trimIndent()

/** 未指定 --timeout 时的默认超时，单位秒。 */
private const val DEFAULT_TIMEOUT_SECONDS: Long = 15

/** 参数错误：用户可以修正，与网络失败区分开。 */
private const val EXIT_USAGE: Int = 2

/** 运行期失败：连接被拒、服务端断开、超时。 */
private const val EXIT_FAILED: Int = 1

public fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val exitCode = when (command) {
        null -> {
            println(usage)
            0
        }
        in setOf("--help", "-h") -> {
            println(usage)
            0
        }
        "--version" -> {
            println(versionInfo)
            0
        }
        "status" -> runStatus(args.drop(1))
        "connect" -> runConnect(args.drop(1))
        else -> {
            // 未知参数不做猜测，直接报错并给出用法，避免静默忽略。
            System.err.println("未知命令: $command")
            System.err.println(usage)
            EXIT_USAGE
        }
    }
    if (exitCode != 0) exitProcess(exitCode)
}

private fun runStatus(args: List<String>): Int {
    val parsed = parseArgs(args) ?: return EXIT_USAGE
    val client = MinecraftClient(parsed.options())
    return try {
        // runBlocking 只出现在组合根：库本身全部是挂起 API。
        val status = runBlocking { client.status(parsed.address) }
        println(
            buildString {
                appendLine("server=${parsed.address.host}:${parsed.address.port}")
                appendLine("version=${status.versionName ?: "未知"} protocol=${status.protocolVersion ?: "未知"}")
                appendLine("players=${status.onlinePlayers ?: "未知"}/${status.maxPlayers ?: "未知"}")
                appendLine("latency=${status.latencyMillis}ms")
                append("motd=${status.description}")
            },
        )
        0
    } catch (e: ClientException) {
        System.err.println("状态查询失败: ${e.message}")
        EXIT_FAILED
    } finally {
        client.close()
    }
}

private fun runConnect(args: List<String>): Int {
    val parsed = parseArgs(args) ?: return EXIT_USAGE
    val client = MinecraftClient(parsed.options())

    // Ctrl+C 时先关闭客户端再退出：会话的网络线程与连接都在这里释放。
    // 退出码仍由信号决定，hook 只负责收尾。
    val hook = Thread({ client.close() }, "pickaxecraft-shutdown")
    Runtime.getRuntime().addShutdownHook(hook)

    return try {
        runBlocking {
            val session = client.connect(parsed.address, parsed.username)
            println(
                "state=${session.state.value} user=${parsed.username} " +
                    "server=${parsed.address.host}:${parsed.address.port}",
            )
            var chunkReported = false
            session.events.collect { event ->
                println(describe(event))
                // 重生换维度后重新打印一次摘要：同一行输出在新旧维度之间应当明显不同。
                if (event is ClientEvent.Respawned) chunkReported = false
                // 首批区块到齐后打印一次世界摘要：这是“区块真的解出来了”的现场证据。
                if (!chunkReported && event is ClientEvent.ChunksReceived && session.world.value?.position != null) {
                    chunkReported = true
                    println(describeChunk(session))
                }
            }
        }
        0
    } catch (e: ClientException) {
        System.err.println("连接失败: ${e.message}")
        EXIT_FAILED
    } finally {
        client.close()
        // 关闭过程中 JVM 可能已在退出，移除失败无害。
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

private fun describe(event: ClientEvent): String = when (event) {
    is ClientEvent.StateChanged -> "event=state state=${event.state}"
    is ClientEvent.RegistryReceived -> "event=registry id=${event.registryId} entries=${event.entryCount}"
    is ClientEvent.JoinedWorld -> "event=joined entity=${event.entityId} dimension=${event.dimension} mode=${event.gameMode}"
    is ClientEvent.PositionUpdated -> with(event.position) {
        "event=position x=$x y=$y z=$z yaw=$yaw pitch=$pitch"
    }
    is ClientEvent.Respawned -> "event=respawned dimension=${event.dimension} mode=${event.gameMode}"
    is ClientEvent.ChunksReceived -> "event=chunks batch=${event.chunks} total=${event.total}"
    is ClientEvent.EntitySpawned -> with(event.entity) {
        "event=entity.spawned id=$id type=$type x=$x y=$y z=$z"
    }
    is ClientEvent.EntityRemoved -> "event=entity.removed ids=${event.ids}"
    ClientEvent.Closed -> "event=closed"
    is ClientEvent.Failed -> "event=failed kind=${event.error::class.simpleName} message=${event.error.message}"
}

/** 出生点所在区块的摘要：维度范围、缓存数量与脚下方块。 */
private fun describeChunk(session: Session): String {
    val position = session.world.value?.position ?: return "chunk=unknown reason=位置尚未同步"
    val x = position.x.toInt()
    val y = position.y.toInt()
    val z = position.z.toInt()
    val column = session.chunk(x shr 4, z shr 4) ?: return "chunk=unloaded at (${x shr 4}, ${z shr 4})"

    val below = if (column.sectionAt(y - 1) == null) "越界" else session.blockStateAt(x, y - 1, z).toString()
    return "chunk x=${column.x} z=${column.z} minY=${column.minY} sections=${column.sectionCount} " +
        "cached=${session.loadedChunks} entities=${session.entityCount} " +
        "block=($x, $y, $z):${session.blockStateAt(x, y, z)} below=$below"
}

/** 解析后的命令行参数。 */
private class ParsedArgs(
    val address: ServerAddress,
    val username: String,
    val timeoutSeconds: Long,
) {
    fun options(): ClientOptions = ClientOptions(
        connectTimeoutMillis = timeoutSeconds * 1000,
        loginTimeoutMillis = timeoutSeconds * 1000,
        statusTimeoutMillis = timeoutSeconds * 1000,
    )
}

/** 解析 `host [port] [--username 名称] [--timeout 秒]`；不合法时打印原因并返回 null。 */
private fun parseArgs(args: List<String>): ParsedArgs? {
    if (args.isEmpty()) {
        System.err.println("缺少 host")
        return null
    }

    val host = args.first()
    var port = ServerAddress.DEFAULT_PORT
    var username = MinecraftClient.DEFAULT_USERNAME
    var timeoutSeconds = DEFAULT_TIMEOUT_SECONDS

    var index = 1
    // 第二个位置参数若是纯数字则视为端口。
    args.getOrNull(index)?.takeIf { value -> value.all(Char::isDigit) }?.let { value ->
        port = value.toInt()
        index++
    }

    while (index < args.size) {
        val option = args[index]
        val value = args.getOrNull(index + 1)
        when (option) {
            "--username" -> {
                if (value == null) {
                    System.err.println("--username 缺少取值")
                    return null
                }
                username = value
                index += 2
            }
            "--timeout" -> {
                val seconds = value?.toLongOrNull()
                if (seconds == null || seconds <= 0) {
                    System.err.println("--timeout 需要一个正数秒数")
                    return null
                }
                timeoutSeconds = seconds
                index += 2
            }
            else -> {
                System.err.println("未知参数: $option")
                return null
            }
        }
    }

    return try {
        ParsedArgs(ServerAddress(host, port), username, timeoutSeconds)
    } catch (e: IllegalArgumentException) {
        System.err.println("参数不合法: ${e.message}")
        null
    }
}
