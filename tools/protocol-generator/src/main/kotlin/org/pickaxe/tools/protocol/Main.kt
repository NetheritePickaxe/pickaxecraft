package org.pickaxe.tools.protocol

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * 命令行入口：`protocol-generator <schemaDir> <outputDir>`。
 *
 * 只做一件事——把 schema 元数据渲染成 Kotlin 源码并写到目标路径；
 * 失败时直接以非零退出码终止，不做静默降级。
 */
public fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("用法: protocol-generator <schemaDir> <outputDir>")
        exitProcess(2)
    }

    val schemaDir = Path.of(args[0])
    val outputDir = Path.of(args[1])
    val target = outputDir.resolve(ProtocolVersionGenerator.RELATIVE_PATH)

    // 校验失败是配置错误，说明原因即可；打印堆栈只会把关键信息埋掉。
    val source = try {
        ProtocolVersionGenerator.generate(schemaDir)
    } catch (error: IllegalArgumentException) {
        System.err.println("生成失败: ${error.message}")
        exitProcess(1)
    }

    target.parent.createDirectories()
    target.writeText(source, Charsets.UTF_8)
    println("generated $target")
}
