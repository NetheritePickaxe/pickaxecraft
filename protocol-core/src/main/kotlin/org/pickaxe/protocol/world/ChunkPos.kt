package org.pickaxe.protocol.world

/**
 * 区块坐标：整数二元组。
 *
 * 区块坐标是方块坐标除以 16 向下取整的结果（`x shr 4`、`z shr 4`）。
 * 与 [BlockPosition] 不同，这里没有 Y 坐标——区块是垂直贯穿的列。
 */
public data class ChunkPos(
    public val x: Int,
    public val z: Int,
) {
    override fun toString(): String = "ChunkPos($x, $z)"
}