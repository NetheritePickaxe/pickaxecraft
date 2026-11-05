package org.pickaxe.protocol.internal

import org.pickaxe.protocol.world.BlockEntity
import org.pickaxe.protocol.world.ChunkColumn
import org.pickaxe.protocol.world.ChunkLight
import org.pickaxe.protocol.world.ChunkSection
import org.pickaxe.protocol.world.HeightmapType
import org.pickaxe.protocol.world.PalettedContainer
import java.nio.ByteBuffer

/**
 * 区块数据解码。
 *
 * 协议库只把 `ClientboundLevelChunkWithLightPacket` 的 `chunkData` 原样交出来（`byte[]`），
 * 不解析 section —— 它服务的是转发场景，没有客户端世界模型的需求。这里按协议格式补齐：
 *
 * ```text
 * repeat(sectionCount) {
 *   blockCount: i16, fluidCount: i16
 *   blockStates: paletted container(4096 项)
 *   biomes:      paletted container(64 项)
 * }
 * ```
 *
 * 调色板容器的公共格式：
 *
 * ```text
 * bitsPerEntry: u8
 * bits == 0            -> singleValue: varint
 * bits <= maxIndirect  -> palette: varint 长度 + varint 数组, storage: i64 数组
 * 其余（直接模式）      -> storage: i64 数组
 * ```
 *
 * 存储是“紧凑打包”：一个 long 内放 `64 / bitsPerEntry` 个值，值不跨 long 边界。
 * 长整型数组**不带**长度前缀——长度由元素数与位宽算出，26.2 的协议库
 * （`MinecraftTypes.readFixedSizeLongArray`）与 Minestom 的调色板序列化器都按此读写。
 *
 * 所有长度、位宽与剩余字节都做校验。数据不合规时抛 [IllegalArgumentException]，
 * 由调用方加上区块坐标后转成协议失败——静默跳过会让世界缺一块而无人知晓。
 */
internal object ChunkDecoder {

    /** 方块与生物群系的级数：前者 16³，后者 4³。 */
    private const val BLOCK_DIMENSION: Int = 16
    private const val BIOME_DIMENSION: Int = 4

    /** 协议规定：方块调色板最多 8 位，超过即直接模式；生物群系最多 3 位。 */
    private const val BLOCK_MAX_INDIRECT_BITS: Int = 8
    private const val BIOME_MAX_INDIRECT_BITS: Int = 3

    private const val BLOCK_MIN_INDIRECT_BITS: Int = 4
    private const val BIOME_MIN_INDIRECT_BITS: Int = 1

    /**
     * 直接模式允许的最大位宽。
     *
     * 协议里最高的方块状态 id 远小于 2^32，32 位是“不会静默截断”的上界，
     * 再大就可能是数据错位，应当失败而不是继续读。
     */
    private const val MAX_DIRECT_BITS: Int = 32

    fun decode(
        x: Int,
        z: Int,
        minY: Int,
        sectionCount: Int,
        data: ByteArray,
        heightmaps: Map<HeightmapType, LongArray> = emptyMap(),
        light: ChunkLight = ChunkLight.of(ChunkLight.layersFor(sectionCount)),
        blockEntities: List<BlockEntity> = emptyList(),
    ): ChunkColumn {
        require(sectionCount > 0) { "区块至少包含一个 section，实际为 $sectionCount" }

        val cursor = ChunkCursor(ByteBuffer.wrap(data))
        val sections = ArrayList<ChunkSection>(sectionCount)
        repeat(sectionCount) {
            val blockCount = cursor.readUnsignedShort()
            // 流体计数只用于客户端优化，不进入模型。
            cursor.readUnsignedShort()
            val blocks = cursor.readPaletteContainer(BLOCK_DIMENSION, BLOCK_MIN_INDIRECT_BITS, BLOCK_MAX_INDIRECT_BITS)
            val biomes = cursor.readPaletteContainer(BIOME_DIMENSION, BIOME_MIN_INDIRECT_BITS, BIOME_MAX_INDIRECT_BITS)
            sections += ChunkSection(blockCount, blocks, biomes)
        }

        require(!cursor.hasRemaining()) {
            "解码完 $sectionCount 个 section 后仍剩余 ${cursor.remaining} 字节，区块格式与预期不符"
        }
        return ChunkColumn(x, z, minY, sections, light, heightmaps, blockEntities)
    }

    private fun ChunkCursor.readPaletteContainer(
        dimension: Int,
        minIndirectBits: Int,
        maxIndirectBits: Int,
    ): PalettedContainer {
        val bitsPerEntry = readUnsignedByte()
        val entryCount = dimension * dimension * dimension

        if (bitsPerEntry == 0) {
            return PalettedContainer(bitsPerEntry, null, EMPTY_STORAGE, readVarInt())
        }

        val valuesPerLong = Long.SIZE_BITS / bitsPerEntry
        val expectedLongs = (entryCount + valuesPerLong - 1) / valuesPerLong

        if (bitsPerEntry <= maxIndirectBits) {
            require(bitsPerEntry >= minIndirectBits) {
                "调色板位宽 $bitsPerEntry 不在允许区间 $minIndirectBits..$maxIndirectBits 内"
            }
            val paletteLength = readVarInt()
            require(paletteLength in 1..(1 shl bitsPerEntry)) {
                "调色板长度 $paletteLength 超过 $bitsPerEntry 位能表示的上限"
            }
            val palette = IntArray(paletteLength) { readVarInt() }
            return PalettedContainer(bitsPerEntry, palette, readLongArray(expectedLongs), 0)
        }

        require(bitsPerEntry in 1..MAX_DIRECT_BITS) {
            "直接模式的位宽 $bitsPerEntry 不合法"
        }
        return PalettedContainer(bitsPerEntry, null, readLongArray(expectedLongs), 0)
    }

    /** 只读游标：越界立即失败，错误里带剩余字节数，便于定位是哪个 section 截断。 */
    private class ChunkCursor(private val buffer: ByteBuffer) {

        val remaining: Int get() = buffer.remaining()

        fun hasRemaining(): Boolean = buffer.hasRemaining()

        fun readUnsignedByte(): Int {
            requireRemaining(Byte.SIZE_BYTES)
            return buffer.get().toInt() and 0xFF
        }

        fun readUnsignedShort(): Int {
            requireRemaining(Short.SIZE_BYTES)
            return buffer.short.toInt() and 0xFFFF
        }

        fun readVarInt(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val byte = readUnsignedByte()
                value = value or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
                require(shift < 35) { "VarInt 超过 5 字节" }
            }
        }

        fun readLongArray(length: Int): LongArray = LongArray(length) {
            requireRemaining(Long.SIZE_BYTES)
            buffer.long
        }

        private fun requireRemaining(bytes: Int) {
            require(buffer.remaining() >= bytes) {
                "区块数据提前结束：还需要 $bytes 字节，实际剩余 ${buffer.remaining()} 字节"
            }
        }
    }

    private val EMPTY_STORAGE: LongArray = LongArray(0)
}
