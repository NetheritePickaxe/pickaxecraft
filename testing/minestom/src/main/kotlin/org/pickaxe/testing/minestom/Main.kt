package org.pickaxe.testing.minestom

import org.pickaxe.protocol.ProtocolVersion
import java.util.concurrent.CountDownLatch

/**
 * 启动联调服务端并阻塞到收到停止信号。
 *
 * 关闭走 JVM shutdown hook（Ctrl+C 或 SIGTERM），显式调用服务端停止，
 * 不依赖进程被强制杀死，保证端口与线程正常释放。
 */
public fun main() {
    startHarness()
    println(
        "event=server.started host=$DEFAULT_HOST port=$DEFAULT_PORT " +
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
