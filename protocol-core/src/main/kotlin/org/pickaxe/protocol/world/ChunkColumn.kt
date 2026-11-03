package org.pickaxe.protocol.world

/**
 * 一组按位宽压缩的数值，区块里的方块与生物群系都用它表示。
 *
 * 三种形态对应协议里的同一段编码：
 * - [bitsPerEntry] 为 0：整段只有一个值（[singleValue]），不占数组；
 * - 小于等于 8（方块）/ 3（生物群系）：[palette] 是调色板，存储的是调色板下标；
 * - 再大：直接存储全局 id，[palette] 为 null。
 *
 * 保持压缩形态而不是展开成 4096 个 Int，是区块能留在内存里的前提：
 * 一份 24 个 section 的区块用压缩形态通常只有几十 KB。
 */
internal class PalettedContainer(
    private val bitsPerEntry: Int,
    private val palette: IntArray?,
    private val storage: LongArray,
    private val singleValue: Int,
) {

    /** @param index 取值在 `0 until 16^3`（方块）或 `0 until 4^3`（生物群系）之间。 */
    fun at(index: Int): Int {
        if (bitsPerEntry == 0) return singleValue

        val valuesPerLong = Long.SIZE_BITS / bitsPerEntry
        val longIndex = index / valuesPerLong
        val bitIndex = (index % valuesPerLong) * bitsPerEntry
        val mask = (1L shl bitsPerEntry) - 1L
        val id = ((storage[longIndex] ushr bitIndex) and mask).toInt()
        return palette?.get(id) ?: id
    }
}

/**
 * 区块的一个 16×16×16 层。
 *
 * 坐标参数都是**层内局部坐标**（0–15）；世界坐标查询走 [ChunkColumn]。
 * 方块 id 是协议的全局方块状态 id，解析成 `minecraft:stone` 这类名字需要版本数据表，
 * 不在本库职责内（见 `docs/architecture.md` 的世界模型小节）。
 */
public class ChunkSection internal constructor(
    /** 服务端统计的非空气方块数；全空气时为 0。 */
    public val blockCount: Int,
    private val blocks: PalettedContainer,
    private val biomes: PalettedContainer,
) {

    /** 是否为服务端标记的空层（全部为空气）。 */
    public val isEmpty: Boolean get() = blockCount == 0

    /** 读取层内方块状态 id。 */
    public fun blockStateAt(x: Int, y: Int, z: Int): Int {
        requireLocal(x, y, z)
        return blocks.at((y shl 8) or (z shl 4) or x)
    }

    /**
     * 读取层内生物群系 id（`minecraft:worldgen/biome` 注册表下标）。
     *
     * 生物群系按 4×4×4 采样：同一格内的 64 个方块共享一个值。
     */
    public fun biomeAt(x: Int, y: Int, z: Int): Int {
        requireLocal(x, y, z)
        return biomes.at(((y shr 2) shl 4) or ((z shr 2) shl 2) or (x shr 2))
    }

    private fun requireLocal(x: Int, y: Int, z: Int) {
        require(x in 0..15 && y in 0..15 && z in 0..15) {
            "层内坐标必须在 0–15 之间，实际为 ($x, $y, $z)"
        }
    }
}

/**
 * 一列已解码的区块。
 *
 * 不可变：服务端重发同一坐标的区块时，上层看到的是一个新对象，而不是就地改写。
 * 因此它可以被多个线程读取，也可以直接作为缓存值——这正是 `client-core` 的用法。
 *
 * 未解码的内容：光照、高度图与方块实体（NBT）。它们不影响“读某个坐标是什么方块”，
 * 需要时再按相同方式补上（见 `docs/plans/m4a-chunk-model.md`）。
 */
public class ChunkColumn internal constructor(
    /** 区块坐标，不是方块坐标。 */
    public val x: Int,
    public val z: Int,
    /** 该维度最低方块层的 Y 坐标，来自 `minecraft:dimension_type`。 */
    public val minY: Int,
    private val sections: List<ChunkSection>,
) {

    /** section 数量，等于维度高度 / 16。 */
    public val sectionCount: Int get() = sections.size

    /** 该列覆盖的 Y 上界（不含）。 */
    public val maxY: Int get() = minY + sectionCount * SECTION_HEIGHT

    /** 按层序号取 section；越界抛错，不返回 null。 */
    public fun section(index: Int): ChunkSection {
        require(index in 0 until sectionCount) {
            "section 序号必须在 0 until $sectionCount 之间，实际为 $index"
        }
        return sections[index]
    }

    /** 按世界 Y 取 section；不在该列高度范围内时返回 null。 */
    public fun sectionAt(worldY: Int): ChunkSection? {
        if (worldY < minY || worldY >= maxY) return null
        return sections[(worldY - minY) / SECTION_HEIGHT]
    }

    /**
     * 读取世界坐标处的方块状态 id。
     *
     * @throws IllegalArgumentException 坐标不在本列内，或 Y 超出该维度高度。
     */
    public fun blockStateAt(worldX: Int, worldY: Int, worldZ: Int): Int {
        requireInColumn(worldX, worldY, worldZ)
        val section = sections[(worldY - minY) / SECTION_HEIGHT]
        return section.blockStateAt(worldX and 15, (worldY - minY) and 15, worldZ and 15)
    }

    /**
     * 读取世界坐标处的生物群系 id。
     *
     * @throws IllegalArgumentException 坐标不在本列内，或 Y 超出该维度高度。
     */
    public fun biomeAt(worldX: Int, worldY: Int, worldZ: Int): Int {
        requireInColumn(worldX, worldY, worldZ)
        val section = sections[(worldY - minY) / SECTION_HEIGHT]
        return section.biomeAt(worldX and 15, (worldY - minY) and 15, worldZ and 15)
    }

    override fun toString(): String =
        "ChunkColumn(x=$x, z=$z, minY=$minY, sections=$sectionCount)"

    private fun requireInColumn(worldX: Int, worldY: Int, worldZ: Int) {
        require(worldX shr 4 == x && worldZ shr 4 == z) {
            "坐标 ($worldX, $worldZ) 不属于区块 ($x, $z)"
        }
        require(worldY in minY until maxY) {
            "y=$worldY 超出区块高度 [$minY, $maxY)"
        }
    }
}

private const val SECTION_HEIGHT: Int = 16
