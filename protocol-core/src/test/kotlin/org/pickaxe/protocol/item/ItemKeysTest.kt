package org.pickaxe.protocol.item

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 物品键名表的锚点用例。
 *
 * 表本身由 Minestom 注册表导出（1537 项、id 连续 0..1536），这里只钉住少量锚点与边界：
 * 表错了会是「所有物品都叫错名字」这种大面积错误，锚点足够发现版本错位。
 */
class ItemKeysTest {

    @Test
    fun `锚点物品对得上当前协议号`() {
        assertEquals("minecraft:air", ItemKeys.key(0))
        assertEquals("minecraft:stone", ItemKeys.key(1))
        assertEquals("minecraft:diamond", ItemKeys.key(926))
        assertEquals("minecraft:diamond_sword", ItemKeys.key(964))
    }

    @Test
    fun `越界与空堆 id 返回 null`() {
        assertNull(ItemKeys.key(-1), "协议库用 -1 表示空堆")
        assertNull(ItemKeys.key(1537), "表尾之后没有物品")
    }
}
