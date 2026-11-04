package org.pickaxe.protocol.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EffectKeysTest {

    @Test
    fun `协议 776 的效果表连续且与枚举顺序一致`() {
        // 与 MCProtocolLib 26.2 的 Effect 枚举逐项对应；升级协议库时必须重新核对。
        assertEquals(40, EffectKeys.size)
        assertEquals("minecraft:speed", EffectKeys.key(0))
        assertEquals("minecraft:mining_fatigue", EffectKeys.key(3))
        assertEquals("minecraft:hero_of_the_village", EffectKeys.key(31))
        assertEquals("minecraft:breath_of_the_nautilus", EffectKeys.key(39))
    }

    @Test
    fun `未知 id 返回 null 而不是乱猜键名`() {
        assertNull(EffectKeys.key(-1))
        assertNull(EffectKeys.key(40))
        assertNull(EffectKeys.key(Int.MAX_VALUE))
    }
}
