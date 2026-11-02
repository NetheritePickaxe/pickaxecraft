package org.pickaxe.testing.minestom

import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.Generator
import org.pickaxe.protocol.ProtocolVersion
import java.net.InetSocketAddress

/** 默认监听地址：只绑定回环，避免把测试服务暴露到局域网。 */
public const val DEFAULT_HOST: String = "127.0.0.1"

/** 默认监听端口，与 Minecraft 惯例一致。 */
public const val DEFAULT_PORT: Int = 25565

/**
 * 启动一个仅供联调的最小服务端：离线认证 + 40 格高的石头平面。
 *
 * 启动前会校验本仓库锁定的协议号与 Minestom 自带常量一致；不一致直接抛错，
 * 避免用错版本的服务端去验证客户端。
 */
public fun startHarness(port: Int = DEFAULT_PORT) {
    val ourVersion = ProtocolVersion.PROTOCOL_VERSION
    require(ourVersion == MinecraftServer.PROTOCOL_VERSION) {
        "协议版本不一致：本仓库锁定 $ourVersion，Minestom 为 ${MinecraftServer.PROTOCOL_VERSION}。" +
            "请同步 schema 或 Minestom 版本。"
    }

    MinecraftServer.init(Auth.Offline())

    val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
    instance.setGenerator(Generator { unit -> unit.modifier().fillHeight(0, 40, Block.fromKey("stone")) })

    MinecraftServer.process().start(InetSocketAddress(DEFAULT_HOST, port))
}

/** 显式停止服务端；重复调用无副作用。 */
public fun stopHarness() {
    if (MinecraftServer.isStarted() && !MinecraftServer.isStopping()) {
        MinecraftServer.process().stop()
    }
}
