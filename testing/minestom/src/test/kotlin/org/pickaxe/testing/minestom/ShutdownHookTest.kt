package org.pickaxe.testing.minestom

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 冷启动 Minestom 在 CI 上可能需要几十秒，留足余量避免偶发超时。 */
private const val STARTUP_TIMEOUT_MILLIS: Long = 90_000

/** 收到 SIGTERM 后，服务端要停线程池并释放端口；不应超过这个时间。 */
private const val SHUTDOWN_TIMEOUT_SECONDS: Long = 30

/**
 * 关闭路径的进程级验证。
 *
 * 必须在独立 JVM 里做，原因有两条：
 * - Minestom 是进程级单例，同进程无法二次初始化，hook 的状态也不干净；
 * - 需要真实发送 SIGTERM，只能对子进程发。
 *
 * Windows 没有 SIGTERM（`Process.destroy` 等价于强制终止，hook 不执行），
 * 因此该用例在 Windows 上跳过，由 Linux CI 覆盖。
 */
class ShutdownHookTest {

    @Test
    fun `收到 SIGTERM 时打印停止日志并释放端口`() {
        assumeTrue(isPosix(), "Windows 无法向子进程发送 SIGTERM；该用例由 Linux CI 覆盖")

        // 与生命周期测试同样的理由：端口 0 由系统分配，避免与本机 25565 冲突。
        val port = ServerSocket(0).use { it.localPort }
        HarnessProcess(port).use { harness ->
            harness.awaitLine("event=server.started", STARTUP_TIMEOUT_MILLIS)

            // 先确认子进程真的在监听，避免“端口没开却断言释放成功”的假通过。
            Socket().use { it.connect(InetSocketAddress(DEFAULT_HOST, port), 2_000) }

            // POSIX 上 destroy() 发送的就是 SIGTERM，不是强制终止。
            harness.sendSigterm()
            assertTrue(
                harness.awaitExit(SHUTDOWN_TIMEOUT_SECONDS),
                "收到 SIGTERM 后 ${SHUTDOWN_TIMEOUT_SECONDS}s 内未退出（可能卡在 shutdown hook 里）；输出:\n${harness.log()}",
            )

            // 退出不等于输出已读完：管道里的最后几行要等读取线程读到 EOF。
            // 少了这一步会随机看不到 stopping/stopped，表现为“hook 没执行”的假失败。
            harness.drain()

            val log = harness.log()
            assertTrue("event=server.stopping" in log, "缺少停止开始日志，说明 hook 未执行；输出:\n$log")
            assertTrue("event=server.stopped" in log, "缺少停止完成日志，说明 stopHarness() 未执行完；输出:\n$log")

            // 端口能连上说明监听没释放，下一次联调会随机失败。
            assertFailsWith<IOException>("进程退出后端口应已释放") {
                Socket().use { it.connect(InetSocketAddress(DEFAULT_HOST, port), 1_000) }
            }
        }
    }

    private fun isPosix(): Boolean = !System.getProperty("os.name").lowercase().contains("win")

    /**
     * 以子进程方式运行的联调服务。
     *
     * 子进程复用测试 JVM 的 classpath：它已含主源集、Minestom 与 slf4j 绑定，
     * 比测试自己拼一份依赖表更不容易与构建脚本漂移。
     */
    private class HarnessProcess(port: Int) : AutoCloseable {

        private val lines = CopyOnWriteArrayList<String>()
        private val process: Process
        private val reader: Thread

        init {
            val javaBin = File(System.getProperty("java.home"), "bin/java").path
            process = ProcessBuilder(
                javaBin,
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-cp",
                System.getProperty("java.class.path"),
                "org.pickaxe.testing.minestom.MainKt",
                "--port",
                port.toString(),
            ).redirectErrorStream(true).start()

            reader = Thread {
                process.inputStream.bufferedReader().useLines { seq -> seq.forEach(lines::add) }
            }.apply {
                isDaemon = true
                name = "harness-output"
                start()
            }
        }

        fun log(): String = lines.joinToString("\n")

        /** 轮询等待某行出现；超时直接带上完整输出失败，省去二次排查。 */
        fun awaitLine(text: String, timeoutMillis: Long) {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (System.nanoTime() < deadline) {
                if (lines.any { text in it }) return
                Thread.sleep(50)
            }
            throw AssertionError("等待 “$text” 超时（${timeoutMillis / 1000}s）；输出:\n${log()}")
        }

        fun sendSigterm() {
            process.destroy()
        }

        fun awaitExit(seconds: Long): Boolean = process.waitFor(seconds, TimeUnit.SECONDS)

        /** 等读取线程读到 EOF，保证退出后的日志已全部收进 [lines]。 */
        fun drain() {
            reader.join(DRAIN_TIMEOUT_MILLIS)
        }

        override fun close() {
            if (process.isAlive) process.destroyForcibly()
        }

        private companion object {
            const val DRAIN_TIMEOUT_MILLIS: Long = 5_000
        }
    }
}
