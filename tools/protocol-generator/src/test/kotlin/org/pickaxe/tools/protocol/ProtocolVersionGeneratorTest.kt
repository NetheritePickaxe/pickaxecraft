package org.pickaxe.tools.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtocolVersionGeneratorTest {

    private val fixture: Path =
        Path.of(requireNotNull(javaClass.getResource("/fixture-schema")).toURI())

    @Test
    fun `同一输入重复生成结果逐字节相同`() {
        assertEquals(ProtocolVersionGenerator.generate(fixture), ProtocolVersionGenerator.generate(fixture))
    }

    @Test
    fun `生成的校验和与 source_json 声明一致`() {
        val declared = declaredSha256(fixture)
        assertTrue(
            ProtocolVersionGenerator.generate(fixture).contains("SCHEMA_SHA256: String = \"$declared\""),
            "生成结果应写入 source.json 声明的校验和",
        )
    }

    @Test
    fun `protocol_json 被改动时拒绝生成`() {
        val schemaDir = fixture.copyToTemp()

        // 只加一个换行：内容仍可解析，但校验和已经变了。
        // 没有这一步比对时，这类改动会被静默接受，生成常量随之漂移。
        schemaDir.resolve("protocol.json").appendText("\n")

        assertFailsWith<IllegalArgumentException> {
            ProtocolVersionGenerator.generate(schemaDir)
        }
    }

    @Test
    fun `source_json 声明的校验和写错时拒绝生成`() {
        val schemaDir = fixture.copyToTemp()
        val sourceFile = schemaDir.resolve("source.json")
        sourceFile.writeText(sourceFile.readText().replace(declaredSha256(fixture), "0".repeat(64)))

        val failure = assertFailsWith<IllegalArgumentException> {
            ProtocolVersionGenerator.generate(schemaDir)
        }
        assertTrue(failure.message.orEmpty().contains("source.json"), "错误信息应指出比对对象")
    }

    private fun declaredSha256(schemaDir: Path): String =
        Regex("\"protocolSha256\"\\s*:\\s*\"([0-9a-f]{64})\"")
            .find(schemaDir.resolve("source.json").readText())
            ?.groupValues
            ?.get(1)
            ?: error("fixture 的 source.json 缺少 protocolSha256")

    private fun Path.copyToTemp(): Path {
        val workDir = Files.createTempDirectory("protocol-schema-")
        val target = workDir.resolve(fileName)
        target.createDirectories()
        toFile().listFiles().orEmpty().forEach { file ->
            file.toPath().copyTo(target.resolve(file.name))
        }
        return target
    }
}
