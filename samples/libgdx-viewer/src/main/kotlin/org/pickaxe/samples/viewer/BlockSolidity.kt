package org.pickaxe.samples.viewer

import org.pickaxe.protocol.world.ChunkColumn

/**
 * 把 `ChunkColumn` 适配成 [SolidQuery]。
 *
 * 查询用的是**网格局部坐标**，由 [originX] / [originY] / [originZ] 换算成世界坐标；
 * 默认原点取这一列自己的左下角，因此局部坐标 0–15 与区块内坐标一致。
 *
 * **air 是状态 id 0** 是 26.2 的版本约定，不是协议保证：`blockStateAt` 返回的是全局
 * 方块状态 id，严格判断“是不是空气”需要版本数据表（本库不提供，见
 * `docs/architecture.md` 的世界模型小节）。示例取这个约定，升级协议库时须重新核对。
 *
 * 越界返回 true：单列网格化时看不到相邻列，把边界当成实的才不会在列与列之间画出
 * 重叠的内壁。真正的渲染器应当跨列查询（相邻列已经在本端缓存里）。
 */
public fun blockSolidity(
    chunk: ChunkColumn,
    originX: Int = chunk.x shl 4,
    originY: Int = chunk.minY,
    originZ: Int = chunk.z shl 4,
    sizeX: Int = 16,
    sizeY: Int = chunk.maxY - chunk.minY,
    sizeZ: Int = 16,
): SolidQuery = SolidQuery { x, y, z ->
    if (x !in 0 until sizeX || y !in 0 until sizeY || z !in 0 until sizeZ) {
        true
    } else {
        chunk.blockStateAt(originX + x, originY + y, originZ + z) != AIR
    }
}

/** 26.2 的全局空气状态 id。 */
private const val AIR: Int = 0
