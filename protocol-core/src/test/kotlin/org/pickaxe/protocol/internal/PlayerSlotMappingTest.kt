package org.pickaxe.protocol.internal

import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 背包槽位换算与容器状态累积。
 *
 * 换算单独测，是因为两套编号在护甲段**反序**：背包 36 是靴子，窗口 8 也是靴子，而窗口 5
 * 是头盔。照抄下标不会报错，只会在联调时表现为“头盔戴到了脚上”——那种错误在链路上很难定位。
 */
class PlayerSlotMappingTest {

    @Test
    fun `快捷栏与主背包的换算保持同序`() {
        assertEquals(PlayerInventoryLayout.HOTBAR_FIRST, PlayerSlotMapping.toWindowSlot(0))
        assertEquals(PlayerInventoryLayout.HOTBAR_FIRST + 8, PlayerSlotMapping.toWindowSlot(8))
        assertEquals(9, PlayerSlotMapping.toWindowSlot(9))
        assertEquals(35, PlayerSlotMapping.toWindowSlot(35))
    }

    @Test
    fun `护甲段的换算方向相反`() {
        // 背包槽位 36–39 依次是靴、腿、胸、头；窗口槽位 5–8 依次是头、胸、腿、靴。
        assertEquals(8, PlayerSlotMapping.toWindowSlot(36), "背包 36 是靴子，对应窗口 8")
        assertEquals(7, PlayerSlotMapping.toWindowSlot(37))
        assertEquals(6, PlayerSlotMapping.toWindowSlot(38))
        assertEquals(5, PlayerSlotMapping.toWindowSlot(39), "背包 39 是头盔，对应窗口 5")
    }

    @Test
    fun `副手与越界`() {
        assertEquals(PlayerInventoryLayout.OFFHAND, PlayerSlotMapping.toWindowSlot(40))
        assertNull(PlayerSlotMapping.toWindowSlot(41), "背包编号只到 40")
        assertNull(PlayerSlotMapping.toWindowSlot(-1))
    }

    @Test
    fun `单槽更新补齐到该槽位`() {
        val container = MutableContainer(windowId = 2)
        container.setSlot(4, InventoryItem(itemId = 10, count = 2))

        val snapshot = container.snapshot()
        assertEquals(5, snapshot.slots.size, "未收到的槽位用空槽补齐")
        assertNull(snapshot.slot(3))
        assertEquals(InventoryItem(10, 2), snapshot.slot(4))
        assertEquals(1, snapshot.usedSlots)
    }

    @Test
    fun `整包内容替换之前的全部槽位`() {
        val container = MutableContainer(windowId = 0)
        container.setSlot(40, InventoryItem(1, 1))

        container.setContent(
            stateId = 7,
            items = listOf(InventoryItem(2, 3), null),
            cursor = InventoryItem(4, 5),
        )

        val snapshot = container.snapshot()
        assertEquals(7, snapshot.stateId)
        assertEquals(2, snapshot.slots.size, "整包内容就是全部槽位，旧的长列表不能残留")
        assertNull(snapshot.slot(40))
        assertEquals(InventoryItem(4, 5), snapshot.cursor)
    }

    @Test
    fun `快捷栏下标越界被拒`() {
        assertEquals(PlayerInventoryLayout.HOTBAR_FIRST, PlayerInventoryLayout.hotbar(0))
        assertEquals(PlayerInventoryLayout.HOTBAR_FIRST + 8, PlayerInventoryLayout.hotbar(8))
        assertFailsWith<IllegalArgumentException> { PlayerInventoryLayout.hotbar(9) }
    }
}
