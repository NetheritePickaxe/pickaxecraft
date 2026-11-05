package org.pickaxe.protocol.world

/**
 * 一次光照更新。
 *
 * 协议按“层”下发：一层的数组是 2048 字节，即 16³ 个格子的半字节（低半字节在前）。
 * 层号是**光照层**，不是 section 序号：光照层 0 是维度最低 section 之下一层，
 * 因此一列有 `sectionCount + 2` 层。这个偏移是最容易记错的一处，`ChunkColumn`
 * 负责把世界 Y 换算成层号，调用方不需要自己做。
 *
 * [sky] / [block] 只包含**本包改动的层**：没有出现的层保持原值。服务端用两张掩码区分
 * “带了一段新数据”和“这一层是全黑的”，两者在这里都表现为一条替换项——空层映射到
 * 一段共享的全零数组，合并逻辑只有一条路径。
 */
public class LightPatch internal constructor(
    public val sky: Map<Int, ByteArray>,
    public val block: Map<Int, ByteArray>,
) {

    /** 本包没有改动任何一层（例如只更新了别的维度）。 */
    public val isEmpty: Boolean get() = sky.isEmpty() && block.isEmpty()

    override fun toString(): String =
        "LightPatch(sky=${sky.keys}, block=${block.keys})"
}

/**
 * 一列区块的光照数据，按光照层下标保存。
 *
 * `null` 表示“这一层没有数据”：服务端没下发、或本端从未收到过。它与“全黑”不同——
 * 全黑是一段全零数组，查询返回 0；没有数据时查询返回 `null`，让调用方自己决定用什么兜底。
 *
 * 数组直接引用服务端下发的那一段，不复制：一列 26 层、两种光加起来约 100 KB，
 * 复制一次就让出生视野的内存翻倍。这些数组在解码之后不再被写入，因此可以安全共享。
 */
internal class ChunkLight private constructor(
    private val sky: List<ByteArray?>,
    private val block: List<ByteArray?>,
) {

    /** 光照层数，等于 `sectionCount + 2`。 */
    val layers: Int get() = sky.size

    /** 读取一层内某格的天空光等级（0–15）；该层没有数据时返回 null。 */
    fun skyAt(layer: Int, x: Int, y: Int, z: Int): Int? = levelAt(sky[layer], x, y, z)

    /** 读取一层内某格的方块光等级（0–15）；该层没有数据时返回 null。 */
    fun blockAt(layer: Int, x: Int, y: Int, z: Int): Int? = levelAt(block[layer], x, y, z)

    /** 合并一次更新，返回新的光照；没有任何层变化时返回自己。 */
    fun withPatch(patch: LightPatch): ChunkLight {
        if (patch.isEmpty) return this
        val nextSky = replace(sky, patch.sky, "天空光")
        val nextBlock = replace(block, patch.block, "方块光")
        if (nextSky === sky && nextBlock === block) return this
        return ChunkLight(nextSky, nextBlock)
    }

    private fun replace(
        current: List<ByteArray?>,
        patch: Map<Int, ByteArray>,
        what: String,
    ): List<ByteArray?> {
        var next: MutableList<ByteArray?>? = null
        for ((layer, body) in patch) {
            require(layer in current.indices) {
                "$what 的层号 $layer 超出本列 ${current.size} 层"
            }
            require(body.size == LAYER_BYTES) {
                "$what 第 $layer 层应为 $LAYER_BYTES 字节，实际为 ${body.size}"
            }
            if (next == null) next = current.toMutableList()
            next[layer] = body
        }
        return next ?: current
    }

    companion object {

        /** 一层光照的字节数：4096 个半字节。 */
        const val LAYER_BYTES: Int = 2048

        /** 全零的一层，多处共享；只读，禁止写入。 */
        private val DARK: ByteArray = ByteArray(LAYER_BYTES)

        /** 只有层数、没有任何数据的一列。 */
        fun of(layers: Int): ChunkLight {
            require(layers >= 2) { "光照至少包含世界上下各一层，实际为 $layers" }
            val empty = ArrayList<ByteArray?>(layers)
            repeat(layers) { empty += null }
            return ChunkLight(empty, ArrayList(empty))
        }

        /** 一列 [sectionCount] 段区块对应的光照层数：上下各多一层。 */
        fun layersFor(sectionCount: Int): Int = sectionCount + 2

        /** 全黑的共享数组；[LightPatch] 用它在“空层”与“有数据”之间统一表示。 */
        fun dark(): ByteArray = DARK

        private fun levelAt(body: ByteArray?, x: Int, y: Int, z: Int): Int? {
            if (body == null) return null
            require(x in 0..15 && y in 0..15 && z in 0..15) {
                "层内坐标必须在 0–15 之间，实际为 ($x, $y, $z)"
            }
            val index = (y shl 8) or (z shl 4) or x
            val packed = body[index shr 1].toInt() and 0xFF
            return if (index and 1 == 0) packed and 0x0F else packed ushr 4
        }
    }
}
