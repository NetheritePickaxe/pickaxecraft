package org.pickaxe.tools.protocol

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 从 schema 目录生成协议版本常量源码。
 *
 * 输入只包含四个文件：
 * - `version.json`：上游 minecraft-data 的版本元数据，保持原样不改动；
 * - `source.json`：本仓库补充的来源元数据（仓库、分支、提交、许可证）；
 * - `protocol.json`：上游协议数据，生成器读取它计算 SHA-256 并与 `source.json` 的锁定值比对；
 * - 其余文件不参与生成。
 *
 * 输出不含时间戳、不含绝对路径，保证同一输入得到逐字节相同的输出。
 */
public object ProtocolVersionGenerator {

    /** 生成文件的相对路径（相对于 Kotlin 源集根目录）。 */
    public const val RELATIVE_PATH: String = "org/pickaxe/protocol/ProtocolVersion.kt"

    /** 读取 schema 目录并生成 `ProtocolVersion.kt` 的源码文本。 */
    public fun generate(schemaDir: Path): String {
        val versionFile = schemaDir.resolve("version.json")
        val sourceFile = schemaDir.resolve("source.json")
        val protocolFile = schemaDir.resolve("protocol.json")

        val version = json.decodeFromString<MinecraftVersionFile>(versionFile.readTextUtf8())
        val source = json.decodeFromString<SchemaSourceFile>(sourceFile.readTextUtf8())
        val protocolSha256 = sha256Of(protocolFile)

        // 校验和只有与锁定值比较才有意义：只计算不比对时，protocol.json 被改动、
        // 或被行尾转换破坏，构建依然会成功，生成的常量也只是“变了但没人发现”。
        require(protocolSha256 == source.protocolSha256) {
            buildString {
                appendLine("protocol.json 校验和与 source.json 声明不一致，schema 已偏离锁定来源：")
                appendLine("  实际: $protocolSha256")
                appendLine("  声明: ${source.protocolSha256}")
                append("若确实要切换版本，请更新 source.json 与 SOURCE.md 并说明来源。")
            }
        }

        return buildString {
            appendLine("// 由 tools:protocol-generator 生成，请勿手工编辑。")
            appendLine("// 输入：schema/${version.minecraftVersion}/")
            appendLine("package org.pickaxe.protocol")
            appendLine()
            appendLine("/** 锁定的 Minecraft 协议版本元数据。 */")
            appendLine("public object ProtocolVersion {")
            appendLine("    /** Minecraft Java Edition 版本号。 */")
            appendLine("    public const val MINECRAFT_VERSION: String = \"${version.minecraftVersion}\"")
            appendLine()
            appendLine("    /** 网络协议号。 */")
            appendLine("    public const val PROTOCOL_VERSION: Int = ${version.version}")
            appendLine()
            appendLine("    /** schema 来源提交。 */")
            appendLine("    public const val SCHEMA_COMMIT: String = \"${source.commit}\"")
            appendLine()
            appendLine("    /** protocol.json 的 SHA-256，用于校验 schema 未被改动。 */")
            appendLine("    public const val SCHEMA_SHA256: String = \"$protocolSha256\"")
            appendLine("}")
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }
}

@Serializable
private data class MinecraftVersionFile(
    val version: Int,
    val minecraftVersion: String,
)

@Serializable
private data class SchemaSourceFile(
    val commit: String,
    /**
     * 锁定的 `protocol.json` 校验和。
     *
     * 没有这个字段时，生成器只能“算出”哈希却无从判断对错，等于没有校验。
     */
    val protocolSha256: String,
)

private fun Path.readTextUtf8(): String = toFile().readText(Charsets.UTF_8)

private fun sha256Of(path: Path): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    path.toFile().inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
