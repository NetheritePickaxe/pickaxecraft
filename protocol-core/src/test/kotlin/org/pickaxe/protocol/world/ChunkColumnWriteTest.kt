package org.pickaxe.protocol.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 方块变更落到区块缓存的行为。
 *
 * 关键不在“写一格”，而在三条约束同时成立：写入产生**新**列（旧列仍可被渲染线程读取）、
 * 未被改动的 section 保持压缩形态（内存不因挖一块方块而翻倍）、空层计数跟着变
 * （否则“把方块放进空气层”会整层不被渲染）。
 */
class ChunkColumnWriteTest {

    private val stoneState = 1

    /** 全是石头的 24 层列，minY = -64：与主世界维度形状一致。 */
    private fun column(): ChunkColumn {
        val sections = List(24) {
            ChunkSection(
                blockCount = 4096,
                blocks = FlatBlockStorage(IntArray(4096) { stoneState }),
                biomes = PalettedContainer(0, null, LongArray(0), 7),
            )
        }
        return ChunkColumn(3, -2, -64, sections)
    }

    @Test
    fun `写入产生新列且旧列不变`() {
        val original = column()
        val updated = original.withBlock(48, -63, -30, 0)

        assertNotNull(updated)
        assertNotEquals(original, updated)
        assertEquals(0, updated.blockStateAt(48, -63, -30), "写入的那格应变成新值")
        assertEquals(stoneState, original.blockStateAt(48, -63, -30), "旧列不能被改写")
        assertEquals(stoneState, updated.blockStateAt(49, -63, -30), "相邻格不受影响")
    }

    @Test
    fun `写入相同的值返回原对象`() {
        val original = column()
        assertSame(original, original.withBlock(48, -63, -30, stoneState))
    }

    @Test
    fun `坐标不属于本列或超出高度时返回 null`() {
        val original = column()

        assertNull(original.withBlock(47, -63, -30, 0), "x=47 属于区块 2，不属于区块 3")
        assertNull(original.withBlock(48, -65, -30, 0), "y=-65 低于 minY=-64")
        assertNull(original.withBlock(48, 320, -30, 0), "y=320 正好是 24 层列的上界（不含）")
    }

    @Test
    fun `空层计数随写入维护`() {
        // 挖掉全石头层的一格：从 4096 变 4095，层不再“全空”。
        val afterDig = assertNotNull(column().withBlock(48, -64, -20, 0))
        assertEquals(4095, afterDig.section(0).blockCount)
        assertFalse(afterDig.section(0).isEmpty)

        // 把方块放进全空气层：计数从 0 变 1。若不维护，isEmpty 仍为 true，
        // 渲染层会整层跳过——数据是对的，方块却看不见。
        val airSections = List(24) {
            ChunkSection(0, FlatBlockStorage(IntArray(4096)), PalettedContainer(0, null, LongArray(0), 7))
        }
        val air = ChunkColumn(0, 0, -64, airSections)
        val afterPlace = assertNotNull(air.withBlock(5, 10, 5, stoneState))
        val placedSection = assertNotNull(afterPlace.sectionAt(10), "y=10 在该列高度范围内")
        assertEquals(1, placedSection.blockCount)
        assertFalse(placedSection.isEmpty)
        assertTrue(assertNotNull(air.sectionAt(10)).isEmpty, "原列的空层标记不能被改写")
    }

    @Test
    fun `连续写入走的是展开后的副本`() {
        var current = assertNotNull(column().withBlock(48, -64, -20, 0))
        current = assertNotNull(current.withBlock(49, -64, -20, 0))
        current = assertNotNull(current.withBlock(49, -64, -20, 9), "覆盖同一格也应产生新列")

        assertEquals(0, current.blockStateAt(48, -64, -20))
        assertEquals(9, current.blockStateAt(49, -64, -20))
        assertEquals(4095, current.section(0).blockCount, "两次写入一挖一放，净变化为 0")
    }
}
