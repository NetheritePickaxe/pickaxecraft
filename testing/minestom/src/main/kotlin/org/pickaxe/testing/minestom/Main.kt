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
    val stopped = CountDownLatch(1)

    startHarness()
    println(
        "event=server.started host=$DEFAULT_HOST port=$DEFAULT_PORT " +
            "minecraft=${ProtocolVersion.MINECRAFT_VERSION} protocol=${ProtocolVersion.PROTOCOL_VERSION} " +
            "schemaCommit=${ProtocolVersion.SCHEMA_COMMIT}",
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("event=server.stopping")
            stopHarness()
            stopped.countDown()
        },
    )

    stopped.await()
    println("event=server.stopped")
}
