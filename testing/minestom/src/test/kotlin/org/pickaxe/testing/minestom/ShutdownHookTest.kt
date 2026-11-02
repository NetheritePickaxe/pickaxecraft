package org.pickaxe.testing.minestom

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
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
        // 输出直接落文件而不是走管道：进程退出后不存在“读取线程还没读完”的窗口，
        // 断言看到的就是子进程写入的全部内容。
        val logFile = Files.createTempFile("harness-shutdown-", ".log").toFile()

        val javaBin = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            javaBin,
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp",
            // 测试 JVM 的 classpath 已含主源集、Minestom 与 slf4j 绑定，
            // 复用它可以避免测试自己拼一份与构建脚本漂移的依赖表。
            System.getProperty("java.class.path"),
            "org.pickaxe.testing.minestom.MainKt",
            "--port",
            port.toString(),
        ).redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()

        try {
            awaitLine(logFile, "event=server.started", STARTUP_TIMEOUT_MILLIS)

            // 先确认子进程真的在监听，避免“端口没开却断言释放成功”的假通过。
            Socket().use { it.connect(InetSocketAddress(DEFAULT_HOST, port), 2_000) }

            // POSIX 上 destroy() 发送的就是 SIGTERM，不是强制终止。
            process.destroy()
            val exited = process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val log = logFile.readText()
            val exitCode = if (exited) process.exitValue() else -1

            assertTrue(
                exited,
                "收到 SIGTERM 后 ${SHUTDOWN_TIMEOUT_SECONDS}s 内未退出，可能卡在 shutdown hook 里；输出:\n$log",
            )
            assertTrue(
                "event=server.stopping" in log,
                "缺少停止开始日志，说明 hook 未执行（exitCode=$exitCode）；输出:\n$log",
            )
            assertTrue(
                "event=server.stopped" in log,
                "缺少停止完成日志，说明 stopHarness() 未执行完（exitCode=$exitCode）；输出:\n$log",
            )

            // 端口能连上说明监听没释放，下一次联调会随机失败。
            assertFailsWith<IOException>("进程退出后端口应已释放") {
                Socket().use { it.connect(InetSocketAddress(DEFAULT_HOST, port), 1_000) }
            }

            // 通过时也把证据打到 CI 日志：这条用例的价值就在“hook 真的执行了”，
            // 只看到一个绿点无法区分它和“被跳过”。
            println("[ShutdownHookTest] exitCode=$exitCode，子进程输出:\n$log")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            logFile.delete()
        }
    }

    private fun isPosix(): Boolean = !System.getProperty("os.name").lowercase().contains("win")

    /** 轮询等待日志里出现某行；超时直接带上已有内容失败，省去二次排查。 */
    private fun awaitLine(logFile: File, text: String, timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (logFile.readText().contains(text)) return
            Thread.sleep(50)
        }
        throw AssertionError("等待 “$text” 超时（${timeoutMillis / 1000}s）；输出:\n${logFile.readText()}")
    }
}
