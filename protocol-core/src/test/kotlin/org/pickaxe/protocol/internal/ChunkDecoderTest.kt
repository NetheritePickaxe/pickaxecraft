package org.pickaxe.protocol.internal

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 区块解码是“协议库不负责、由本项目补齐”的部分，也是世界模型的唯一入口。
 *
 * 用例直接构造字节流：真服务端只能覆盖它自己会生成的形态（Minestom 的空层与地形），
 * 调色板的三种存储方式、直接模式与截断数据需要手工造。
 */
class ChunkDecoderTest {

    @Test
    fun `单值调色板让整段共享一个值`() {
        val data = section(blockCount = 0, blocks = singleton(0), biomes = singleton(7)).array()

        val column = ChunkDecoder.decode(x = 3, z = -2, minY = -64, sectionCount = 1, data = data)

        assertEquals(1, column.sectionCount)
        assertEquals(-64, column.minY)
        assertEquals(-48, column.maxY)
        assertEquals(0, column.blockStateAt(49, -64, -31))
        assertEquals(0, column.blockStateAt(48, -49, -32))
        assertEquals(7, column.biomeAt(49, -60, -31))
        assertTrue(column.section(0).isEmpty, "blockCount 为 0 的层应报告为空")
    }

    @Test
    fun `列外坐标与越界高度被拒绝`() {
        val data = section(blockCount = 0, blocks = singleton(0), biomes = singleton(0)).array()
        val column = ChunkDecoder.decode(x = 3, z = -2, minY = -64, sectionCount = 1, data = data)

        assertFailsWith<IllegalArgumentException> { column.blockStateAt(49, -48, -31) }
        assertFailsWith<IllegalArgumentException> { column.blockStateAt(64, -64, -31) }
    }

    @Test
    fun `间接调色板按位宽解包并做调色板映射`() {
        val bits = 4
        val palette = intArrayOf(0, 10, 20)
        val storage = LongArray(256)
        // 0 号与 15 号在同一 long 内，16 号跨到下一个 long：这正是容易写错的地方。
        storage.put(index = 0, bits = bits, id = 1)
        storage.put(index = 15, bits = bits, id = 2)
        storage.put(index = 16, bits = bits, id = 0)
        storage.put(index = 255, bits = bits, id = 1)

        val data = section(4096, indirect(bits, palette, storage), singleton(3)).array()
        val column = ChunkDecoder.decode(x = 0, z = 0, minY = -64, sectionCount = 1, data = data)

        assertEquals(10, column.blockStateAt(0, -64, 0))
        assertEquals(20, column.blockStateAt(15, -64, 0))
        assertEquals(0, column.blockStateAt(0, -63, 0))
        assertEquals(10, column.blockStateAt(15, -64, 15))
    }

    @Test
    fun `直接模式不做调色板映射`() {
        val blockBits = 15
        val blockStorage = LongArray(longsFor(4096, blockBits))
        blockStorage.put(index = 33, bits = blockBits, id = 12_345)
        blockStorage.put(index = 4095, bits = blockBits, id = 30_000)

        val biomeBits = 6
        val biomeStorage = LongArray(longsFor(64, biomeBits))
        biomeStorage.put(index = 8, bits = biomeBits, id = 42)

        val data = section(4096, direct(blockBits, blockStorage), direct(biomeBits, biomeStorage)).array()
        val column = ChunkDecoder.decode(x = 0, z = 0, minY = -64, sectionCount = 1, data = data)

        assertEquals(12_345, column.blockStateAt(1, -64, 2))
        assertEquals(30_000, column.blockStateAt(15, -49, 15))
        assertEquals(42, column.biomeAt(0, -64, 8))
    }

    @Test
    fun `数据提前结束时给出可定位的失败`() {
        val full = section(blockCount = 0, blocks = singleton(0), biomes = singleton(1)).array()
        val truncated = full.copyOf(full.size - 1)

        val error = assertFailsWith<IllegalArgumentException> {
            ChunkDecoder.decode(x = 0, z = 0, minY = -64, sectionCount = 1, data = truncated)
        }
        assertTrue(error.message.orEmpty().contains("提前结束"), "实际为 ${error.message}")
    }

    @Test
    fun `多余的尾部字节不被忽略`() {
        val data = section(blockCount = 0, blocks = singleton(0), biomes = singleton(0)).array() + byteArrayOf(0)

        val error = assertFailsWith<IllegalArgumentException> {
            ChunkDecoder.decode(x = 0, z = 0, minY = -64, sectionCount = 1, data = data)
        }
        assertTrue(error.message.orEmpty().contains("剩余"), "实际为 ${error.message}")
    }

    @Test
    fun `方块位宽低于协议下限时失败`() {
        val data = Bytes().short(0).short(0).byte(3).array()

        val error = assertFailsWith<IllegalArgumentException> {
            ChunkDecoder.decode(x = 0, z = 0, minY = -64, sectionCount = 1, data = data)
        }
        assertTrue(error.message.orEmpty().contains("区间"), "实际为 ${error.message}")
    }

    /** 一个 section：非空气数、流体数、方块容器、生物群系容器。 */
    private fun section(blockCount: Int, blocks: Bytes, biomes: Bytes): Bytes =
        Bytes().short(blockCount).short(0).raw(blocks.array()).raw(biomes.array())

    private fun singleton(value: Int): Bytes = Bytes().byte(0).varInt(value)

    private fun indirect(bits: Int, palette: IntArray, storage: LongArray): Bytes =
        Bytes().byte(bits).varInt(palette.size).also { writer ->
            palette.forEach { writer.varInt(it) }
        }.longs(storage)

    private fun direct(bits: Int, storage: LongArray): Bytes =
        Bytes().byte(bits).longs(storage)

    private fun longsFor(entries: Int, bits: Int): Int {
        val perLong = Long.SIZE_BITS / bits
        return (entries + perLong - 1) / perLong
    }

    private fun LongArray.put(index: Int, bits: Int, id: Int) {
        val perLong = Long.SIZE_BITS / bits
        val longIndex = index / perLong
        val shift = (index % perLong) * bits
        this[longIndex] = this[longIndex] or (id.toLong() shl shift)
    }

    /** 大端写出，与协议一致；只覆盖解码测试用到的类型。 */
    private class Bytes {
        private val out = ByteArrayOutputStream()

        fun byte(value: Int): Bytes {
            out.write(value and 0xFF)
            return this
        }

        fun short(value: Int): Bytes {
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
            return this
        }

        fun varInt(value: Int): Bytes {
            var remaining = value
            while ((remaining and -128) != 0) {
                out.write((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            out.write(remaining)
            return this
        }

        fun long(value: Long): Bytes {
            for (shift in 56 downTo 0 step 8) {
                out.write(((value ushr shift) and 0xFF).toInt())
            }
            return this
        }

        fun longs(values: LongArray): Bytes {
            values.forEach { long(it) }
            return this
        }

        fun raw(bytes: ByteArray): Bytes {
            out.write(bytes)
            return this
        }

        fun array(): ByteArray = out.toByteArray()
    }
}
