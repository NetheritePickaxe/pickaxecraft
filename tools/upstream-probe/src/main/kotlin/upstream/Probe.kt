package upstream

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.pickaxe.client.ClientException
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.SessionState
import org.pickaxe.protocol.ServerAddress
import kotlin.system.exitProcess

/** 单次探针的整体上限：登录 + 首批区块不应超过这个时间。 */
private const val PROBE_TIMEOUT_MILLIS: Long = 60_000

/** 等待首批区块的上限。 */
private const val CHUNK_TIMEOUT_MILLIS: Long = 20_000

/**
 * 上游探针：用指定版本的 MCProtocolLib 消费已发布的 `client-core`。
 *
 * 三个步骤对应三个契约：
 * 1. 状态查询：握手与状态包仍能编码/解码；
 * 2. 离线登录：登录状态机与配置阶段注册表仍能走通；
 * 3. 进入世界并收到区块：适配层的解码路径仍能工作。
 *
 * 任一步失败都以非零码退出；输出保持 `probe=upstream ...` 的单行摘要，
 * 便于工作流直接贴进 issue。
 */
fun main(args: Array<String>) {
    val mcplVersion = args.getOrNull(0) ?: "26.2"
    val host = args.getOrNull(1) ?: "127.0.0.1"
    val port = args.getOrNull(2)?.toIntOrNull() ?: 25565
    val address = ServerAddress(host, port)

    val client = MinecraftClient(ClientOptions(statusTimeoutMillis = 10_000, connectTimeoutMillis = 10_000))
    try {
        runBlocking {
            withTimeout(PROBE_TIMEOUT_MILLIS) {
                val status = client.status(address)
                println(
                    "probe=upstream step=status ok=true mcpl=$mcplVersion " +
                        "serverVersion=${status.versionName} protocol=${status.protocolVersion}",
                )

                val session = client.connect(address, "UpstreamProbe")
                try {
                    check(session.state.value == SessionState.PLAY) {
                        "登录后应进入 PLAY，实际为 ${session.state.value}"
                    }
                    val deadline = System.currentTimeMillis() + CHUNK_TIMEOUT_MILLIS
                    while (session.loadedChunks == 0 && System.currentTimeMillis() < deadline) {
                        delay(50)
                    }
                    check(session.loadedChunks > 0) {
                        "登录后 ${CHUNK_TIMEOUT_MILLIS}ms 内没有收到区块"
                    }
                    println(
                        "probe=upstream ok=true mcpl=$mcplVersion server=$host:$port " +
                            "chunks=${session.loadedChunks} revision=${session.chunkRevision}",
                    )
                } finally {
                    session.close()
                }
            }
        }
    } catch (e: ClientException) {
        System.err.println("probe=upstream ok=false mcpl=$mcplVersion server=$host:$port error=${e::class.simpleName}: ${e.message}")
        exitProcess(1)
    } catch (e: IllegalStateException) {
        System.err.println("probe=upstream ok=false mcpl=$mcplVersion server=$host:$port error=${e.message}")
        exitProcess(1)
    } finally {
        client.close()
    }
}
