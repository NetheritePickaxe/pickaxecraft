package org.pickaxe.testing.minestom

import org.pickaxe.protocol.ProtocolVersion
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** 联调服务的命令行用法。 */
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
    println(
        "event=server.started host=$DEFAULT_HOST port=$port " +
            "minecraft=${ProtocolVersion.MINECRAFT_VERSION} protocol=${ProtocolVersion.PROTOCOL_VERSION} " +
            "schemaCommit=${ProtocolVersion.SCHEMA_COMMIT}",
    )

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

    stopped.await()
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
