package org.pickaxe.protocol.item

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 物品原型表的锚点用例。
 *
 * 表本身由 Minestom 注册表导出（1537 项，与 [ItemKeys] 同源，见 `exportItemPrototypes` 任务），
 * 这里只钉住少量锚点与边界：原型表错了会表现为「所有工具的耐久都读不到」，锚点足够发现版本错位。
 */
class ItemPrototypesTest {

    @Test
    fun `耐久只覆盖可损坏的物品`() {
        assertEquals(1561, ItemPrototypes.maxDamage(964), "diamond_sword")
        assertEquals(250, ItemPrototypes.maxDamage(961), "iron_pickaxe")
        assertNull(ItemPrototypes.maxDamage(1), "stone 不可损坏")
        assertNull(ItemPrototypes.maxDamage(974), "stick 不可损坏")
        assertNull(ItemPrototypes.maxDamage(1144), "ender_pearl 不可损坏")
    }

    @Test
    fun `堆叠上限覆盖 1 与 16 两类非默认值`() {
        assertEquals(64, ItemPrototypes.maxStackSize(1), "stone")
        assertEquals(64, ItemPrototypes.maxStackSize(974), "stick")
        assertEquals(1, ItemPrototypes.maxStackSize(964), "diamond_sword")
        assertEquals(16, ItemPrototypes.maxStackSize(1144), "ender_pearl")
    }

    @Test
    fun `越界 id 两项都返回 null`() {
        assertNull(ItemPrototypes.maxDamage(-1), "协议库用 -1 表示空堆")
        assertNull(ItemPrototypes.maxStackSize(-1), "协议库用 -1 表示空堆")
        assertNull(ItemPrototypes.maxDamage(ItemPrototypes.SIZE), "表尾之后没有物品")
        assertNull(ItemPrototypes.maxStackSize(ItemPrototypes.SIZE), "表尾之后没有物品")
    }
}
