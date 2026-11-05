package org.pickaxe.protocol.world

/**
 * 一段按位宽压缩的数值，区块里的方块与生物群系都用它表示。
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
) : BlockStorage {

    /** @param index 取值在 `0 until 16^3`（方块）或 `0 until 4^3`（生物群系）之间。 */
    override fun at(index: Int): Int {
        if (bitsPerEntry == 0) return singleValue

        val valuesPerLong = Long.SIZE_BITS / bitsPerEntry
        val longIndex = index / valuesPerLong
        val bitIndex = (index % valuesPerLong) * bitsPerEntry
        val mask = (1L shl bitsPerEntry) - 1L
        val id = ((storage[longIndex] ushr bitIndex) and mask).toInt()
        return palette?.get(id) ?: id
    }

    /** 展开成每格一个 int；只在写入时调用。 */
    override fun toArray(size: Int): IntArray = IntArray(size) { at(it) }
}

/**
 * 一段方块内容的存储形态。
 *
 * 解码出来的区块用 [PalettedContainer]（压缩），一旦被方块变更改过就换成每格一个 int 的
 * 展开形态：局部改动压缩数据需要重建调色板，成本与展开整段相当，而展开后写一格是常数时间。
 */
internal interface BlockStorage {
    fun at(index: Int): Int

    /** 展开成 [size] 个值的新数组。 */
    fun toArray(size: Int): IntArray
}

/** 展开形态的方块存储：一次修改只复制数组，不再碰调色板。 */
internal class FlatBlockStorage(private val values: IntArray) : BlockStorage {

    override fun at(index: Int): Int = values[index]

    override fun toArray(size: Int): IntArray = values.copyOf()
}

/**
 * 区块的一个 16×16×16 层。
 *
 * 坐标参数都是**层内局部坐标**（0–15）；世界坐标查询走 [ChunkColumn]。
 * 方块 id 是协议的全局方块状态 id，解析成 `minecraft:stone` 这类名字需要版本数据表，
 * 不在本库职责内（见 `docs/architecture.md` 的世界模型小节）。
 */
public class ChunkSection internal constructor(
    /** 服务端统计的非空气方块数；被方块变更改过之后由本层自己维护。 */
    blockCount: Int,
    private val blocks: BlockStorage,
    private val biomes: PalettedContainer,
) {

    /** 非空气方块数。方块变更会按增量维护它，区块解码后由服务端下发的值初始化。 */
    public var blockCount: Int = blockCount
        private set

    /** 是否为全空气层。渲染层靠它跳过整层，因此方块变更后必须跟着更新。 */
    public val isEmpty: Boolean get() = blockCount == 0

    /** 读取层内方块状态 id。 */
    public fun blockStateAt(x: Int, y: Int, z: Int): Int {
        requireLocal(x, y, z)
        return blocks.at(indexOf(x, y, z))
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

    /**
     * 写入一格方块，返回新的层；值没变时返回自己。
     *
     * 写时复制：调用方拿到的是新对象，旧层仍可被其它线程安全读取。展开只发生一次——
     * 同一层后续的写入直接改副本。
     */
    internal fun withBlock(x: Int, y: Int, z: Int, stateId: Int): ChunkSection {
        val index = indexOf(x, y, z)
        val previous = blocks.at(index)
        if (previous == stateId) return this

        val values = blocks.toArray(SECTION_VOLUME)
        values[index] = stateId

        // 空层计数靠“状态 id 0 是空气”这条不变量维护：原版的全局方块状态注册表从
        // `minecraft:air` 开始，所有版本 0 都是空气。不维护它的话，把方块放进空气层之后
        // 仍然报告“整层是空的”，渲染层会整层跳过——数据是对的，方块却看不见。
        val delta = (if (stateId == AIR_STATE) 0 else 1) - (if (previous == AIR_STATE) 0 else 1)
        return ChunkSection(blockCount + delta, FlatBlockStorage(values), biomes)
    }

    private fun requireLocal(x: Int, y: Int, z: Int) {
        require(x in 0..15 && y in 0..15 && z in 0..15) {
            "层内坐标必须在 0–15 之间，实际为 ($x, $y, $z)"
        }
    }

    private fun indexOf(x: Int, y: Int, z: Int): Int = (y shl 8) or (z shl 4) or x
}

/**
 * 一列已解码的区块。
 *
 * 对上层**不可变**：服务端重发同一坐标的区块时，上层看到的是一个新对象，而不是就地改写。
 * 因此它可以被多个线程读取，也可以直接作为缓存值——这正是 `client-core` 的用法。
 *
 * 方块变更走 [withBlock]：复制受影响的那一层再生成新列，读取路径完全不变。未被改动的
 * section 继续保留压缩形态，因此“出生点挖掉一块方块”不会让整个视野的内存翻倍。
 *
 * 光照、高度图与方块实体都在这里：它们与方块来自同一个 `level_chunk_with_light` 包，
 * 分开保存只会让“一列区块”这个概念在三个地方各有一份缓存。光照与方块实体都能被后续的
 * 增量包单独改写，走 [withLight] / [withBlockEntity]，同样返回新列。
 */
public class ChunkColumn internal constructor(
    /** 区块坐标，不是方块坐标。 */
    public val x: Int,
    public val z: Int,
    /** 该维度最低方块层的 Y 坐标，来自 `minecraft:dimension_type`。 */
    public val minY: Int,
    private val sections: List<ChunkSection>,
    private val light: ChunkLight = ChunkLight.of(ChunkLight.layersFor(sections.size)),
    heightmaps: Map<HeightmapType, LongArray> = emptyMap(),
    /** 服务端下发或后来更新的方块实体，按位置排列。 */
    public val blockEntities: List<BlockEntity> = emptyList(),
) {

    private val heightmaps: Map<HeightmapType, LongArray> = heightmaps.toMap()

    init {
        // 高度图固定 9 位一格、256 格，长度由格式决定而不是由数据决定。长度不符说明
        // 解码已经错位，继续读只会给出看似合理的错值。
        for ((type, packed) in heightmaps) {
            require(packed.size == HEIGHTMAP_LONGS) {
                "$type 高度图应为 $HEIGHTMAP_LONGS 个 long，实际为 ${packed.size}"
            }
        }
    }

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

    /**
     * 读取世界坐标处的天空光等级（0–15）。
     *
     * 光照层比 section 多上下各一层：层 0 覆盖 `[minY - 16, minY)`，层 `sectionCount + 1`
     * 覆盖 `[maxY, maxY + 16)`。再外层、或该层服务端没有下发数据时返回 null；**全黑返回 0**，
     * 两者不是一回事。
     */
    public fun skyLightAt(worldX: Int, worldY: Int, worldZ: Int): Int? =
        lightAt(worldX, worldY, worldZ, sky = true)

    /**
     * 读取世界坐标处的方块光等级（0–15），语义与 [skyLightAt] 相同。
     *
     * 火把、萤石这类光源体现在这张表里；服务端改光后会用 `update_light` 增量下发，
     * 由 [withLight] 合并进本列。
     */
    public fun blockLightAt(worldX: Int, worldY: Int, worldZ: Int): Int? =
        lightAt(worldX, worldY, worldZ, sky = false)

    /**
     * 读取某列高度图，单位是世界 Y。
     *
     * 值是**该列第一格空气的 Y**，也就是最高“算作地表”的方块的 Y 加一；没有这张表时
     * 返回 null。哪些方块算地表取决于 [type]（见 [HeightmapType]）。
     *
     * 高度图是服务端整列下发时算好的**快照**：本端改方块不会重算它。[withBlock] 只改方块，
     * 因为判断“哪些方块算地表”需要方块分类表（空气/流体/树叶），那不是协议能提供的。
     * 需要精确值时应以服务端重发的整列为准，而不是把旧快照当成最新地形。
     *
     * @throws IllegalArgumentException 坐标不在本列内
     */
    public fun heightAt(type: HeightmapType, worldX: Int, worldZ: Int): Int? {
        require(worldX shr 4 == x && worldZ shr 4 == z) {
            "坐标 ($worldX, $worldZ) 不属于区块 ($x, $z)"
        }
        val packed = heightmaps[type] ?: return null
        val index = ((worldZ and 15) shl 4) or (worldX and 15)
        val raw = (packed[index / HEIGHTMAP_VALUES_PER_LONG] ushr
            ((index % HEIGHTMAP_VALUES_PER_LONG) * HEIGHTMAP_BITS)) and HEIGHTMAP_MASK
        return minY + raw.toInt()
    }

    /** 按坐标取方块实体；这格没有实体（或没收到数据）时返回 null。 */
    public fun blockEntity(worldX: Int, worldY: Int, worldZ: Int): BlockEntity? =
        blockEntities.firstOrNull {
            it.position.x == worldX && it.position.y == worldY && it.position.z == worldZ
        }

    /** [blockEntity] 的方块坐标版本。 */
    public fun blockEntity(position: BlockPosition): BlockEntity? =
        blockEntity(position.x, position.y, position.z)

    /**
     * 合并一次光照更新，返回新的列；没有任何层变化时返回自己。
     *
     * 光照与方块是两条独立的下行链路（`update_light` 与 `block_update`），所以它不能挂在
     * [withBlock] 上：一次放火把会先来方块变更、后来光照更新，也可能只来其中一个。
     */
    public fun withLight(patch: LightPatch): ChunkColumn {
        val updated = light.withPatch(patch)
        if (updated === light) return this
        return ChunkColumn(x, z, minY, sections, updated, heightmaps, blockEntities)
    }

    /**
     * 写入或替换一个方块实体，返回新的列。
     *
     * 同一位置已存在实体时整条替换：服务端下发的 NBT 是**这份实体的完整状态**，
     * 不是补丁，按键合并只会留下已经不存在的字段。
     *
     * @throws IllegalArgumentException 位置不在本列内
     */
    public fun withBlockEntity(entity: BlockEntity): ChunkColumn {
        val position = entity.position
        require(position.x shr 4 == x && position.z shr 4 == z) {
            "方块实体位于 ${position.x}, ${position.z}，不属于区块 ($x, $z)"
        }
        val next = ArrayList<BlockEntity>(blockEntities.size + 1)
        for (existing in blockEntities) {
            if (existing.position != position) next += existing
        }
        next += entity
        return ChunkColumn(x, z, minY, sections, light, heightmaps, next)
    }

    /**
     * 写入一格方块，返回新的列；没有变化时返回自己。
     *
     * 坐标不属于本列、或 Y 超出该维度高度时返回 null：批量方块变更可能跨越刚刚被卸载的
     * 区块，那不是错误，而是“这条变更在本端已经没有落点”。
     *
     * 同一格的方块实体一并清除：服务端换掉方块时不发“删除方块实体”的包，旧实体的 NBT
     * （箱子内容、告示牌文字）留在缓存里就是过期事实。如果新方块本身带实体，服务端紧跟
     * 一个 `block_entity_data`，[withBlockEntity] 会把它放回来。
     *
     * @return 新的列；坐标不可写时返回 null
     */
    public fun withBlock(worldX: Int, worldY: Int, worldZ: Int, stateId: Int): ChunkColumn? {
        if (worldX shr 4 != x || worldZ shr 4 != z) return null
        if (worldY < minY || worldY >= maxY) return null

        val index = (worldY - minY) / SECTION_HEIGHT
        val updated = sections[index].withBlock(worldX and 15, (worldY - minY) and 15, worldZ and 15, stateId)
        if (updated === sections[index]) return this

        val nextSections = sections.toMutableList()
        nextSections[index] = updated

        val nextEntities = blockEntities.filterNot {
            it.position.x == worldX && it.position.y == worldY && it.position.z == worldZ
        }
        return ChunkColumn(x, z, minY, nextSections, light, heightmaps, nextEntities)
    }

    private fun lightAt(worldX: Int, worldY: Int, worldZ: Int, sky: Boolean): Int? {
        // 光照层按 floorDiv 分桶：minY 之下的那一层是负数区间，`and 15` 在 16 的补码上
        // 正好等于 floorMod，因此层内坐标不需要额外的负值处理。
        val layer = Math.floorDiv(worldY - minY, SECTION_HEIGHT) + 1
        if (layer !in 0 until light.layers) return null
        val localY = (worldY - minY) and 15
        return if (sky) {
            light.skyAt(layer, worldX and 15, localY, worldZ and 15)
        } else {
            light.blockAt(layer, worldX and 15, localY, worldZ and 15)
        }
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

/** 高度图一格 9 位：维度高度上限 512 足够。 */
private const val HEIGHTMAP_BITS: Int = 9
private const val HEIGHTMAP_VALUES_PER_LONG: Int = Long.SIZE_BITS / HEIGHTMAP_BITS

/** 256 格按每 long 7 个打包，共 37 个 long；协议不带长度，长度由格式算出。 */
private const val HEIGHTMAP_LONGS: Int = 37
private const val HEIGHTMAP_MASK: Long = (1L shl HEIGHTMAP_BITS) - 1L

/** 一层 16×16×16 的格数。 */
private const val SECTION_VOLUME: Int = 16 * 16 * 16

/** 空气的全局方块状态 id。 */
private const val AIR_STATE: Int = 0
