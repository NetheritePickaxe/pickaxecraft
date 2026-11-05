package org.pickaxe.protocol.world

/**
 * 高度图种类。协议一次下发多张表，同一列的答案可能不同。
 *
 * 六种表只差“哪些方块算遮挡”，名字里的 `_WG` 表示这张表供世界生成使用：
 *
 * | 种类 | 顶部算作“地表”的方块 |
 * | --- | --- |
 * | `WORLD_SURFACE` | 任何非空气方块，含树叶与流体 |
 * | `OCEAN_FLOOR` | 忽略流体，海底就是海底 |
 * | `MOTION_BLOCKING` | 会挡住移动的方块，含流体（水面能站人的判定用它） |
 * | `MOTION_BLOCKING_NO_LEAVES` | 同上但忽略树叶（生成生物用） |
 *
 * 本库不解释语义，只把服务端算好的值交出来；选了哪张表由调用方按用途决定。
 */
public enum class HeightmapType {
    WORLD_SURFACE_WG,
    WORLD_SURFACE,
    OCEAN_FLOOR_WG,
    OCEAN_FLOOR,
    MOTION_BLOCKING,
    MOTION_BLOCKING_NO_LEAVES,
}
