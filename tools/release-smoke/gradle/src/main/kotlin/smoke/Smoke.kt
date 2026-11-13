package smoke

import kotlinx.coroutines.runBlocking
import org.pickaxe.client.ClientException
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.MinecraftClient
import org.pickaxe.protocol.ServerAddress
import kotlin.system.exitProcess

/**
 * 发布冒烟入口：只用 `client-core` 的公共 API 查询一次状态。
 *
 * 不接触协议库、Netty 或 Minestom——它们由 `client-core` 的 POM 作为 runtime 依赖带入，
 * 能不能解析、能不能真正建立连接，都由这次查询证明。
 */
fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 25565
    val client = MinecraftClient(ClientOptions(statusTimeoutMillis = 5_000))
    try {
        val status = runBlocking { client.status(ServerAddress(host, port)) }
        println(
            "smoke=gradle ok=true host=$host port=$port " +
                "version=${status.versionName} protocol=${status.protocolVersion} " +
                "players=${status.onlinePlayers}/${status.maxPlayers} " +
                // 打印真实运行时版本：fixture 用 Java 21 toolchain，这一行就是“Java 21 可消费”的证据。
                "java=${System.getProperty("java.version")}",
        )
    } catch (e: ClientException) {
        System.err.println("smoke=gradle ok=false host=$host port=$port error=${e.message}")
        exitProcess(1)
    } finally {
        client.close()
    }
}
