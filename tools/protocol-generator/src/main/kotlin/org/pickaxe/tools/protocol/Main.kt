package org.pickaxe.tools.protocol

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * 命令行入口：`protocol-generator <schemaDir> <outputDir>`。
 *
 * 只做一件事——把 schema 元数据渲染成 Kotlin 源码并写到目标路径；
 * 失败时直接以非零退出码终止，不做静默降级。
 */
public fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("用法: protocol-generator <schemaDir> <outputDir>")
        kotlin.system.exitProcess(2)
    }

    val schemaDir = Path.of(args[0])
    val outputDir = Path.of(args[1])
    val target = outputDir.resolve(ProtocolVersionGenerator.RELATIVE_PATH)

    target.parent.createDirectories()
    target.writeText(ProtocolVersionGenerator.generate(schemaDir), Charsets.UTF_8)
    println("generated $target")
}
