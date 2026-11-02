package org.pickaxe.tools.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProtocolVersionGeneratorTest {

    private val fixture: Path =
        Path.of(requireNotNull(javaClass.getResource("/fixture-schema")).toURI())

    @Test
    fun `同一输入重复生成结果逐字节相同`() {
        assertEquals(ProtocolVersionGenerator.generate(fixture), ProtocolVersionGenerator.generate(fixture))
    }

    @Test
    fun `校验和反映 protocol_json 的实际内容`() {
        val workDir = Files.createTempDirectory("protocol-schema-")
        val schemaDir = fixture.copyDirectoryTo(workDir)

        val before = schemaSha256(schemaDir)
        schemaDir.resolve("protocol.json").appendText("\n")
        val after = schemaSha256(schemaDir)

        assertNotEquals(before, after)
    }

    private fun schemaSha256(schemaDir: Path): String =
        ProtocolVersionGenerator.generate(schemaDir)
            .lineSequence()
            .first { "SCHEMA_SHA256" in it }

    private fun Path.copyDirectoryTo(targetDir: Path): Path {
        val target = targetDir.resolve(fileName)
        target.createDirectories()
        toFile().listFiles().orEmpty().forEach { file ->
            file.toPath().copyTo(target.resolve(file.name))
        }
        return target
    }
}
