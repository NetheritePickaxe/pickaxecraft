package smoke

import kotlinx.coroutines.runBlocking
import org.pickaxe.client.ClientException
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.MinecraftClient
import org.pickaxe.protocol.ServerAddress
import kotlin.system.exitProcess

/**
 * 发布冒烟入口（Maven 消费者）：只用 `client-core` 的公共 API 查询一次状态。
 *
 * 与 Gradle fixture 相同，但依赖全部来自 POM：如果 runtime 依赖没有随 POM 发布，
 * 这里会在真正发状态查询时报 `NoClassDefFoundError`，而不是编译失败。
 */
fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 25565
    val client = MinecraftClient(ClientOptions(statusTimeoutMillis = 5_000))
    try {
        val status = runBlocking { client.status(ServerAddress(host, port)) }
        println(
            "smoke=maven ok=true host=$host port=$port " +
                "version=${status.versionName} protocol=${status.protocolVersion} " +
                "players=${status.onlinePlayers}/${status.maxPlayers} " +
                "java=${System.getProperty("java.version")}",
        )
    } catch (e: ClientException) {
        System.err.println("smoke=maven ok=false host=$host port=$port error=${e.message}")
        exitProcess(1)
    } finally {
        client.close()
    }
}
