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
        val output = CopyOnWriteArrayList<String>()
        val process = spawnHarness(port, output)
        try {
            awaitStarted(output)

            // 先确认子进程真的在监听，避免“端口没开却断言释放成功”的假通过。
            Socket().use { it.connect(InetSocketAddress(DEFAULT_HOST, port), 2_000) }

            // POSIX 上 destroy() 发送的就是 SIGTERM，不是强制终止。
            process.destroy()
            assertTrue(
                process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "收到 SIGTERM 后 ${SHUTDOWN_TIMEOUT_SECONDS}s 内未退出（可能卡在 shutdown hook 里）；输出:\n${output.joinToString("\n")}",
            )

            val log = output.joinToString("\n")
            assertTrue(
                log.contains("event=server.stopping"),
                "缺少停止开始日志，说明 hook 未执行；输出:\n$log",
            )
            assertTrue(
                log.contains("event=server.stopped"),
                "缺少停止完成日志，说明 stopHarness() 未执行完；输出:\n$log",
            )

            // 端口能连上说明监听没释放，下一次联调会随机失败。
            assertFailsWith<IOException>("进程退出后端口应已释放") {
                Socket().use { it.connect(InetSocketAddress(DEFAULT_HOST, port), 1_000) }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun spawnHarness(port: Int, output: MutableList<String>): Process {
        val javaBin = File(System.getProperty("java.home"), "bin/java").path
        // 测试 JVM 的 classpath 已含主源集、Minestom 与 slf4j 绑定，直接复用最省事，
        // 也避免测试自己去拼一份与构建脚本不一致的依赖列表。
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaBin,
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp",
            classpath,
            "org.pickaxe.testing.minestom.MainKt",
            "--port",
            port.toString(),
        ).redirectErrorStream(true).start()

        Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(output::add) }
        }.apply {
            isDaemon = true
            name = "harness-output"
        }.start()

        return process
    }

    private fun awaitStarted(output: List<String>) {
        val deadline = System.nanoTime() + STARTUP_TIMEOUT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (output.any { it.contains("event=server.started") }) return
            Thread.sleep(100)
        }
        throw AssertionError(
            "服务端未在 ${STARTUP_TIMEOUT_MILLIS / 1000}s 内启动；输出:\n${output.joinToString("\n")}",
        )
    }

    private fun isPosix(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        return !os.contains("win")
    }
}
