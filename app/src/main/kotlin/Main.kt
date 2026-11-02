package org.pickaxe.app

import org.pickaxe.protocol.ProtocolVersion
import kotlin.system.exitProcess

private val usage = """
    用法: pickaxecraft <命令>

    命令:
      --version   打印客户端版本与锁定的游戏/协议/服务端版本
      --help      打印本帮助

    不带参数时同样打印本帮助。
""".trimIndent()

private val versionInfo = """
    pickaxecraft ${BuildInfo.CLIENT_VERSION}
    minecraft ${ProtocolVersion.MINECRAFT_VERSION}
    protocol ${ProtocolVersion.PROTOCOL_VERSION}
    minestom ${BuildInfo.MINESTOM_VERSION}
""".trimIndent()

public fun main(args: Array<String>) {
    when {
        args.isEmpty() -> println(usage)
        args.size == 1 && args[0] in setOf("--help", "-h") -> println(usage)
        args.size == 1 && args[0] == "--version" -> println(versionInfo)
        else -> {
            // 未知参数不做猜测，直接报错并给出用法，避免静默忽略。
            System.err.println("未知参数: ${args.joinToString(" ")}")
            System.err.println(usage)
            exitProcess(2)
        }
    }
}
