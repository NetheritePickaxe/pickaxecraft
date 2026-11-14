package org.pickaxe.androidhost

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.Session
import org.pickaxe.client.SessionState
import org.pickaxe.protocol.ServerAddress
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * M3 运行时证据的执行体。
 *
 * 由一个 Activity 持有，按意图参数依次跑 probe → status → join → cycles，
 * 每一步都把结果追加到 `report.txt` 并同步打到 logcat。
 *
 * 这里刻意不用 AndroidX 的 ViewModel：那份实现依赖 AndroidX 生命周期。
 * Activity 通过 `onRetainNonConfigurationInstance` 把同一个实例交给重建后的
 * Activity，语义相同（客户端比界面活得久），依赖为零。
 */
class HostRunner {

    /**
     * 只启动一次：整个进程一份。
     *
     * 放在伴生对象而不是实例上——Activity 重建时 `onRetainNonConfigurationInstance`
     * 通常能带回同一个实例，但进程还活着而实例没被带回的路径确实存在（观察到过两次运行
     * 同时写同一个报告文件）。报告是证据，重复运行会让它自相矛盾。
     */
    fun start(context: Context, intent: Intent, render: (String) -> Unit) {
        if (!started.compareAndSet(false, true)) return

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val report = File(dir, "report.txt")
        report.writeText("")

        val params = Params.from(intent)
        Thread({ run(params, Reporter(report, render)) }, "m3-host-worker").start()
    }

    /** 一次运行的参数；全部来自 `am start -e/-ei` 的 extra。 */
    private data class Params(
        val action: String,
        val host: String,
        val port: Int,
        val username: String,
        val cycles: Int,
    ) {
        companion object {
            fun from(intent: Intent): Params = Params(
                action = intent.getStringExtra("action") ?: "all",
                host = intent.getStringExtra("host") ?: "127.0.0.1",
                port = intent.getIntExtra("port", 25565),
                username = intent.getStringExtra("username") ?: "AndroidHost",
                cycles = intent.getIntExtra("cycles", 20),
            )
        }
    }

    /**
     * 报告写入器：文件、logcat 与界面三处同一份内容。
     *
     * 每行单独 `appendText`（每次打开、写入、关闭）而不是持有一个常开的缓冲流：
     * 证据文件的价值在于“进程被杀时已经写下的部分仍然完整”，多出的几次系统调用
     * 相对于一次真机开关会话可以忽略。
     */
    private class Reporter(private val file: File, private val render: (String) -> Unit) {

        private val startedAt = System.nanoTime()

        @Synchronized
        fun line(text: String) {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            val row = "t=${elapsed}ms $text"
            Log.i(TAG, row)
            file.appendText(row + "\n")
            render(row)
        }
    }

    private fun run(params: Params, out: Reporter) {
        val address = ServerAddress(params.host, params.port)
        out.line("event=host.started action=${params.action} server=${address.host}:${address.port} user=${params.username}")
        try {
            runBlocking {
                if (params.action == "all" || params.action == "probe") probe(out)
                if (params.action == "all" || params.action == "status") status(out, address)
                if (params.action == "all" || params.action == "join") join(out, address, params.username)
                if (params.action == "all" || params.action == "cycles") cycles(out, address, params.username, params.cycles)
            }
            out.line("event=host.done")
        } catch (t: Throwable) {
            // 失败就是失败：报告里留下可复现的一行，不写“通过”。
            // 堆栈一起写进文件：ART 上的类初始化失败，类型名往往不足以定位。
            out.line("event=host.failed type=${t.javaClass.name} message=${t.message}")
            out.line(t.stackTraceToString())
            Log.e(TAG, "M3 host run failed", t)
        }
    }

    // ---------------------------------------------------------------- probe

    /**
     * ART 能力探针。
     *
     * 四条都与 minSdk 决定相关：MethodHandle 的签名多态调用（minSdk 26 的来源）、
     * Netty 的 `PlatformDependent` 在 ART 上的探测结果、mcprotocollib 的 JNDI 静态初始化
     * （需要宿主占位实现），以及 `java.util.HexFormat` 是否存在（API 34；在线认证前的已知限制）。
     */
    private fun probe(out: Reporter) {
        out.line(
            "event=probe.device sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} " +
                "abi=${Build.SUPPORTED_ABIS.joinToString(",")} model=${Build.MODEL}",
        )
        out.line(
            "event=probe.vm name=${System.getProperty("java.vm.name")} " +
                "version=${System.getProperty("java.vm.version")} " +
                "runtime=${System.getProperty("java.runtime.name")}",
        )

        // 1. MethodHandle.invokeExact：Java 源码里直接发出签名多态调用。
        try {
            val sum = MethodHandleProbe.invokeExactAdd(2, 3)
            out.line("event=probe.methodhandle ok=true result=$sum")
        } catch (t: Throwable) {
            out.line("event=probe.methodhandle ok=false type=${t.javaClass.name} message=${t.message}")
        }

        // 2. Netty 的运行时探测结果。反射调用：宿主不应该编译期依赖 io.netty。
        try {
            val cls = Class.forName("io.netty.util.internal.PlatformDependent")
            fun read(name: String): Any? = cls.getMethod(name).invoke(null)
            out.line(
                "event=probe.netty isAndroid=${read("isAndroid")} hasUnsafe=${read("hasUnsafe")} " +
                    "javaVersion=${read("javaVersion")} directPreferred=${read("directBufferPreferred")} " +
                    "canReliablyFree=${read("canReliabilyFreeDirectBuffers")} " +
                    "useDirectNoCleaner=${read("useDirectBufferNoCleaner")}",
            )

            // 直接缓冲的地址访问：Unsafe 不可用时 Netty 会走另一条路径，这里实测一次。
            try {
                val buffer = ByteBuffer.allocateDirect(1024)
                val addressMethod = cls.getMethod("directBufferAddress", ByteBuffer::class.java)
                val address = addressMethod.invoke(null, buffer)
                out.line("event=probe.netty.direct address=$address capacity=${buffer.capacity()}")
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                out.line("event=probe.netty.direct ok=false type=${cause.javaClass.name} message=${cause.message}")
            }
        } catch (t: Throwable) {
            out.line("event=probe.netty ok=false type=${t.javaClass.name} message=${t.message}")
        }

        // 3. NettyHelper 的静态初始化：它在 <clinit> 里建 JNDI 上下文，ART 上要靠宿主的
        //    com.sun.jndi.dns.DnsContextFactory 占位实现才能通过（见 host 的该文件）。
        try {
            Class.forName("org.geysermc.mcprotocollib.network.helper.NettyHelper")
            out.line("event=probe.nettyhelper ok=true srv=jndi-shim")
        } catch (t: Throwable) {
            out.line("event=probe.nettyhelper ok=false type=${t.javaClass.name} message=${t.message}")
        }

        // 4. HexFormat：设备 API 36 应当有；minSdk 26 仍需单独决策。
        try {
            val cls = Class.forName("java.util.HexFormat")
            val format = cls.getMethod("of").invoke(null)
            val parsed = cls.getMethod("parseHex", CharSequence::class.java).invoke(format, "00ff7f")
            val hex = (parsed as ByteArray).joinToString("") { "%02x".format(it) }
            out.line("event=probe.hexformat present=true api=${Build.VERSION.SDK_INT} roundtrip=$hex")
        } catch (t: Throwable) {
            out.line("event=probe.hexformat present=false api=${Build.VERSION.SDK_INT} type=${t.javaClass.name}")
        }
    }

    // --------------------------------------------------------------- status

    /** 状态查询：最轻的一条链路，验证 Netty 建连、握手与 ping 回包。 */
    private suspend fun status(out: Reporter, address: ServerAddress) {
        val client = MinecraftClient(ClientOptions(statusTimeoutMillis = STATUS_TIMEOUT_MILLIS))
        try {
            val status = client.status(address)
            out.line(
                "event=status ok=true version=${status.versionName} protocol=${status.protocolVersion} " +
                    "players=${status.onlinePlayers}/${status.maxPlayers} " +
                    "latency=${status.latencyMillis}ms motd=${status.description}",
            )
        } finally {
            client.close()
        }
    }

    // ----------------------------------------------------------------- join

    /**
     * 一次完整连接：PLAY + 首批区块 + 线程增量，然后关闭并核对缓存与线程回收。
     *
     * 线程快照在连接前后各取一次；新增的 `pickaxe-mcpl` 线程就是客户端自持的
     * EventLoopGroup，关闭后必须消失——这是 M3 DoD 的第 4 条。进程级的 binder、
     * ART、协程调度器线程不在判定范围内（它们不随会话回收）。
     */
    private suspend fun join(out: Reporter, address: ServerAddress, username: String) {
        val before = threadNames()
        val clientBefore = clientThreads()
        out.line("event=join.begin threads=${before.size} client=${clientBefore.size}")

        val client = MinecraftClient(
            ClientOptions(
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                loginTimeoutMillis = LOGIN_TIMEOUT_MILLIS,
            ),
        )
        try {
            coroutineScope {
                val session = client.connect(address, username)
                val watcher = watchEvents(session, out)
                try {
                    await(out, "play 与首批区块", JOIN_TIMEOUT_MILLIS, describe = { session.describe() }) {
                        session.state.value == SessionState.PLAY &&
                            session.world.value?.position != null &&
                            session.loadedChunks > 0
                    }
                    val position = session.world.value?.position
                    out.line(
                        "event=join.play state=${session.state.value} x=${position?.x} y=${position?.y} " +
                            "z=${position?.z} chunks=${session.loadedChunks} entities=${session.entityCount} " +
                            "brand=${session.serverBrand.value ?: "-"}",
                    )
                    val added = clientThreads() - clientBefore
                    out.line("event=threads.connected added=${added.size} names=$added total=${threadNames().size}")
                } finally {
                    session.close()
                }
                watcher.join()
                await(out, "会话进入 CLOSED", CLOSE_TIMEOUT_MILLIS) {
                    session.state.value == SessionState.CLOSED
                }
                await(out, "关闭后区块缓存清空", CLOSE_TIMEOUT_MILLIS) { session.loadedChunks == 0 }
                out.line("event=join.closed state=${session.state.value} chunks=${session.loadedChunks}")

                // 线程回收是异步的：graceful shutdown 有自己的超时，这里等它收敛。
                // 只看客户端自持的线程：binder、ART 与协程调度器属于进程，不随会话回收。
                val deadline = System.currentTimeMillis() + CLOSE_TIMEOUT_MILLIS
                var leaked = clientThreads() - clientBefore
                while (leaked.isNotEmpty() && System.currentTimeMillis() < deadline) {
                    delay(100)
                    leaked = clientThreads() - clientBefore
                }
                out.line(
                    "event=threads.after threads=${threadNames().size} " +
                        "client=${(clientThreads() - clientBefore).size} leaked=$leaked",
                )
            }
        } finally {
            client.close()
        }
    }

    // --------------------------------------------------------------- cycles

    /**
     * 连续开关若干次，观察线程数与堆占用是否单调增长。
     *
     * 复用同一个 `MinecraftClient`（它要求上一会话先 CLOSED），与测试里的用法一致；
     * 每轮都等到缓存清空、线程回落才进入下一轮。
     */
    private suspend fun cycles(out: Reporter, address: ServerAddress, username: String, count: Int) {
        out.line("event=cycles.begin count=$count")
        val client = MinecraftClient(
            ClientOptions(
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                loginTimeoutMillis = LOGIN_TIMEOUT_MILLIS,
            ),
        )
        try {
            for (index in 1..count) {
                val before = threadNames()
                val clientBefore = clientThreads()
                val usedBefore = usedHeapMegabytes()
                coroutineScope {
                    val session = client.connect(address, username)
                    val watcher = watchEvents(session, out)
                    try {
                        await(out, "第 $index 轮进入 play", JOIN_TIMEOUT_MILLIS, describe = { session.describe() }) {
                            session.state.value == SessionState.PLAY &&
                                session.world.value?.position != null &&
                                session.loadedChunks > 0
                        }
                        val chunks = session.loadedChunks
                        val added = clientThreads() - clientBefore
                        session.close()
                        watcher.join()
                        await(out, "第 $index 轮进入 CLOSED", CLOSE_TIMEOUT_MILLIS) {
                            session.state.value == SessionState.CLOSED
                        }
                        await(out, "第 $index 轮缓存清空", CLOSE_TIMEOUT_MILLIS) { session.loadedChunks == 0 }
                        val deadline = System.currentTimeMillis() + CLOSE_TIMEOUT_MILLIS
                        var leaked = clientThreads() - clientBefore
                        while (leaked.isNotEmpty() && System.currentTimeMillis() < deadline) {
                            delay(100)
                            leaked = clientThreads() - clientBefore
                        }
                        out.line(
                            "event=cycle index=$index chunks=$chunks chunksAfterClose=${session.loadedChunks} " +
                                "threadsBefore=${before.size} threadsAfter=${threadNames().size} " +
                                "added=${added.size} leaked=$leaked heapBefore=${usedBefore}MB " +
                                "heapAfter=${usedHeapMegabytes()}MB",
                        )
                    } finally {
                        if (session.state.value != SessionState.CLOSED) session.close()
                        watcher.cancel()
                    }
                }
                // 让 ART 有机会执行一次 GC 与线程退出，避免下一轮读到上一轮残留。
                if (index % 5 == 0) {
                    System.gc()
                    delay(500)
                }
            }
            out.line("event=cycles.done count=$count threads=${threadNames().size} heap=${usedHeapMegabytes()}MB")
        } finally {
            client.close()
        }
    }

    // -------------------------------------------------------------- helpers

    /**
     * 把会话事件里“为什么结束”的部分写进报告。
     *
     * 协议层的诊断日志走 slf4j，Android 上没有绑定等于静默；`ClientEvent.Failed`
     * 是宿主任一层次都能看到的唯一失败原因，必须落到文件里。
     */
    private fun CoroutineScope.watchEvents(session: Session, out: Reporter): Job = launch {
        session.events.collect { event ->
            when (event) {
                is ClientEvent.Failed -> out.line(
                    "event=session.failed type=${event.error.javaClass.simpleName} " +
                        "message=${event.error.message} chain=${causeChain(event.error)}",
                )

                is ClientEvent.StateChanged -> out.line("event=session.state state=${event.state}")
                else -> Unit
            }
        }
    }

    /** 异常链压成一行：ART 上真正的原因常常在第二、三层。 */
    private fun causeChain(t: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = t
        while (current != null && parts.size < 6) {
            parts += "${current.javaClass.simpleName}(${current.message})"
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    /**
     * 轮询等待条件成立；超时写一条带上下文的失败行并抛出。
     *
     * 抛 `TimeoutException` 而不是继续：证据需要明确的失败点，而不是一个看起来跑完的报告。
     * 等待期间每 [PROGRESS_INTERVAL_MILLIS] 写一次现状——真机慢的时候，“卡在哪个状态”
     * 比“超时了”更有用。
     */
    private suspend fun await(
        out: Reporter,
        what: String,
        timeoutMillis: Long,
        describe: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val startedAt = System.currentTimeMillis()
        var lastReport = startedAt
        try {
            withTimeout(timeoutMillis) {
                while (!condition()) {
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= PROGRESS_INTERVAL_MILLIS) {
                        lastReport = now
                        out.line("event=wait.progress what=$what elapsed=${now - startedAt}ms ${describe()}")
                    }
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            out.line("event=wait.timeout what=$what limit=${timeoutMillis}ms ${describe()}")
            throw TimeoutException(what)
        }
    }

    private fun threadNames(): Set<String> = Thread.getAllStackTraces().keys.mapTo(HashSet()) { it.name }

    /** 客户端自持线程（会话生命周期内必须成对出现/消失）。 */
    private fun clientThreads(): Set<String> = threadNames().filterTo(HashSet()) { it.startsWith(CLIENT_THREAD_PREFIX) }

    private fun usedHeapMegabytes(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / MEGABYTE
    }

    /** 等待期的一条现状：状态、位置与缓存规模，超时行与进度行共用。 */
    private fun Session.describe(): String {
        val position = world.value?.position
        return "state=${state.value} pos=$position chunks=$loadedChunks entities=$entityCount"
    }

    companion object {
        private const val TAG: String = "PickaxeHost"

        /** 进程内只跑一次：报告文件只能对应一次运行。 */
        private val started = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 客户端自持线程名前缀，与 `docs/architecture.md` 的线程表一致。 */
        private const val CLIENT_THREAD_PREFIX: String = "pickaxe-mcpl"

        /** 单次等待的轮询间隔：比一次网络往返小得多，又不会烧 CPU。 */
        private const val POLL_INTERVAL_MILLIS: Long = 100

        /** 等待期写现状的间隔：真机上几十秒的等待要能看出卡在哪个状态。 */
        private const val PROGRESS_INTERVAL_MILLIS: Long = 5_000

        private const val CONNECT_TIMEOUT_MILLIS: Long = 15_000
        private const val LOGIN_TIMEOUT_MILLIS: Long = 30_000
        private const val STATUS_TIMEOUT_MILLIS: Long = 15_000

        /** 进入 play 并等到区块：真机比桌面慢，给足余量。 */
        private const val JOIN_TIMEOUT_MILLIS: Long = 45_000

        /** 关闭路径的等待上限：覆盖 graceful shutdown 的线程回收。 */
        private const val CLOSE_TIMEOUT_MILLIS: Long = 15_000

        private const val MEGABYTE: Long = 1024 * 1024
    }
}
