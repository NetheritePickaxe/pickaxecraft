package org.pickaxe.samples.viewer

/**
 * 示例地形：一整层地面（y=0）+ 中间一个 2×2×2 的方块柱。
 *
 * 越界约定与 [blockSolidity] 一致：**侧面与下方**越界视为实心（不画朝向网格外的面），
 * 上方越界视为空气（顶面照画）。这个约定决定了网格的面数，所以示例与测试共用同一份实现，
 * 数字才对得上。
 */
internal fun demoTerrain(size: Int): SolidQuery = SolidQuery { x, y, z ->
    when {
        x !in 0 until size || z !in 0 until size || y < 0 -> true
        y == 0 -> true
        x in 2..3 && y in 1..2 && z in 2..3 -> true
        else -> false
    }
}
