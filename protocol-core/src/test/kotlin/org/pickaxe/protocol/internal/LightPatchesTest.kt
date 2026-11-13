package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData
import org.pickaxe.protocol.world.ChunkLight
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 光照包的掩码与数组对齐。
 *
 * 协议把“哪些层有数据”写成掩码，数组只按升序铺开：第 n 个为 1 的位对应第 n 段数组。
 * 这段换算写错时数据仍然“看起来合法”，只是整片光照错位，所以必须有直接用例。
 */
class LightPatchesTest {

    @Test
    fun `掩码的第 n 个位对应第 n 段数组`() {
        val first = filled(0x11)
        val second = filled(0x22)
        val data = LightUpdateData(
            /* skyYMask = */ bitsOf(1, 3),
            /* blockYMask = */ bitsOf(2),
            /* emptySkyYMask = */ BitSet(),
            /* emptyBlockYMask = */ BitSet(),
            /* skyUpdates = */ listOf(first, second),
            /* blockUpdates = */ listOf(filled(0x33)),
        )

        val patch = lightPatch(data, layers = 4)

        assertSame(first, patch.sky.getValue(1).bytes, "第一段数组应落在掩码的第一个 1 上")
        assertSame(second, patch.sky.getValue(3).bytes, "第二段数组应落在掩码的第二个 1 上，而不是层 2")
        assertEquals(setOf(1, 3), patch.sky.keys)
        assertEquals(setOf(2), patch.block.keys)
    }

    @Test
    fun `空掩码表示全黑而不是没提到`() {
        val data = LightUpdateData(
            BitSet(),
            BitSet(),
            /* emptySkyYMask = */ bitsOf(0, 2),
            /* emptyBlockYMask = */ BitSet(),
            emptyList(),
            emptyList(),
        )

        val patch = lightPatch(data, layers = 4)

        assertEquals(setOf(0, 2), patch.sky.keys)
        assertTrue(patch.sky.values.all { it === ChunkLight.dark() }, "空层应复用共享的全零数组")
        assertTrue(patch.block.isEmpty(), "方块光的空掩码为空时不能凭空造出替换项")
    }

    @Test
    fun `掩码与数组数量不一致时失败`() {
        val tooFew = LightUpdateData(
            bitsOf(1, 2),
            BitSet(),
            BitSet(),
            BitSet(),
            listOf(filled(0)),
            emptyList(),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> { lightPatch(tooFew, layers = 4) }
                .message.orEmpty().contains("只带了 1 段"),
        )

        val tooMany = LightUpdateData(
            bitsOf(1),
            BitSet(),
            BitSet(),
            BitSet(),
            listOf(filled(0), filled(0)),
            emptyList(),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> { lightPatch(tooMany, layers = 4) }
                .message.orEmpty().contains("只声明了 1 层"),
        )
    }

    @Test
    fun `层号超出本列时失败`() {
        val data = LightUpdateData(
            bitsOf(5),
            BitSet(),
            BitSet(),
            BitSet(),
            listOf(filled(0)),
            emptyList(),
        )

        val error = assertFailsWith<IllegalArgumentException> { lightPatch(data, layers = 4) }
        assertTrue(error.message.orEmpty().contains("超出本列 4 层"), "实际为 ${error.message}")
    }

    private fun bitsOf(vararg indexes: Int): BitSet = BitSet().apply { indexes.forEach { set(it) } }

    private fun filled(value: Int): ByteArray = ByteArray(ChunkLight.LAYER_BYTES) { value.toByte() }
}
