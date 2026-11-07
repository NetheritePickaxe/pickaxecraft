package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.data.game.inventory.CreativeGrabAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.FillStackAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.SpreadItemAction
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.DragButton
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import org.pickaxe.protocol.inventory.SlotClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 点击意图到 `container_click` 字段的映射。
 *
 * 单独测的理由：协议里点击是「动作类型 + 一个裸字节参数」，行动作与参数是枚举序号，写错了
 * 既不会编译失败也不会被服务端当场拒绝——只会表现为“点了没反应”或者“按数字键变成了丢东西”，
 * 在联调日志里几乎无法倒推。
 */
class ContainerClicksTest {

    private fun playerInventory(stateId: Int = 0) = Container(
        windowId = Container.PLAYER_WINDOW_ID,
        type = null,
        title = null,
        stateId = stateId,
        slots = List(PlayerInventoryLayout.SIZE) { null },
    )

    private fun chest(stateId: Int = 0) = Container(
        windowId = 7,
        type = "GENERIC_9X3",
        title = "箱子",
        stateId = stateId,
        // 只带容器自己的 27 格：箱子后面的背包段在 Minestom 的快照里并不存在。
        slots = List(27) { null },
    )

    @Test
    fun `左右键映射到同一动作的不同参数`() {
        val (type, param) = ContainerClicks.action(SlotClick.Pickup())
        assertEquals(ContainerActionType.CLICK_ITEM, type)
        assertEquals(ClickItemAction.LEFT_CLICK, param)

        val (rightType, rightParam) = ContainerClicks.action(SlotClick.Pickup(rightButton = true))
        assertEquals(ContainerActionType.CLICK_ITEM, rightType)
        assertEquals(ClickItemAction.RIGHT_CLICK, rightParam)
    }

    @Test
    fun `快速转移与双击收拢各有自己的动作`() {
        assertEquals(ContainerActionType.SHIFT_CLICK_ITEM, ContainerClicks.action(SlotClick.QuickMove()).first)
        assertEquals(
            ShiftClickItemAction.RIGHT_CLICK,
            ContainerClicks.action(SlotClick.QuickMove(rightButton = true)).second,
        )
        val (type, param) = ContainerClicks.action(SlotClick.PickupAll)
        assertEquals(ContainerActionType.FILL_STACK, type)
        assertEquals(FillStackAction.FILL, param)
    }

    @Test
    fun `丢弃整堆与丢一个的差别在参数上`() {
        // 协议里 DROP_ITEM 的参数只有两位：0 丢一个、1 丢整堆；槽位 -999 才表示丢光标上的物品。
        val (type, one) = ContainerClicks.action(SlotClick.Throw())
        assertEquals(ContainerActionType.DROP_ITEM, type)
        assertEquals(DropItemAction.DROP_FROM_SELECTED, one)
        assertEquals(0, one.id, "丢一个写在包里的参数是 0")
        val stack = ContainerClicks.action(SlotClick.Throw(wholeStack = true)).second
        assertEquals(DropItemAction.DROP_SELECTED_STACK, stack)
        assertEquals(1, stack.id, "丢整堆写在包里的参数是 1")
    }

    @Test
    fun `快捷栏交换传的是快捷栏下标而不是格子号`() {
        val (type, param) = ContainerClicks.action(SlotClick.SwapWithHotbar(3))
        assertEquals(ContainerActionType.MOVE_TO_HOTBAR_SLOT, type)
        // 参数就是快捷栏下标 0–8；写成窗口槽位（39）会被服务端当成副手交换。
        assertEquals(3, param.id)
    }

    @Test
    fun `快捷栏下标越界在构造时就被拒`() {
        assertFailsWith<IllegalArgumentException> { SlotClick.SwapWithHotbar(PlayerInventoryLayout.HOTBAR_SIZE) }
        assertFailsWith<IllegalArgumentException> { SlotClick.SwapWithHotbar(-1) }
    }

    @Test
    fun `窗口号与状态号来自快照，槽位变化不做预测`() {
        val packet = ContainerClicks.packet(chest(stateId = 12), slot = 3, click = SlotClick.Pickup())

        assertEquals(7, packet.containerId)
        assertEquals(12, packet.stateId, "服务端用状态号判断本端看到的是不是最新内容")
        assertEquals(3, packet.slot)
        assertTrue(packet.changedSlots.isEmpty(), "不重算点击结果，声明“没有槽位变化”")
        assertNull(packet.carriedItem, "光标本来就是空的")
    }

    @Test
    fun `光标物品按点击前的状态声明`() {
        // 填点击前的光标而不是 null：点击把光标清空时服务端才会发现差异并回一条空光标，
        // 否则本端会永远停在点击前的那堆物品上。
        val container = chest(stateId = 1).copy(cursor = InventoryItem(itemId = 7, count = 2))
        val packet = ContainerClicks.packet(container, slot = 0, click = SlotClick.Pickup())

        val carried = assertNotNull(packet.carriedItem)
        assertEquals(7, carried.id)
        assertEquals(2, carried.count)
        assertTrue(carried.addedComponents.isEmpty(), "没有物品组件表，组件哈希恒为空")
        assertTrue(carried.removedComponents.isEmpty())
    }

    @Test
    fun `玩家背包越界与负槽位被拒，容器窗口不查上限`() {
        assertFailsWith<IllegalArgumentException> { ContainerClicks.packet(playerInventory(), -1, SlotClick.Pickup()) }
        assertFailsWith<IllegalArgumentException> {
            ContainerClicks.packet(playerInventory(), PlayerInventoryLayout.SIZE, SlotClick.Pickup())
        }

        // 箱子的快照只有 27 格，但 27–62 是合法的背包槽位，不能按快照长度拦。
        assertEquals(54, ContainerClicks.packet(chest(), 54, SlotClick.Pickup()).slot)
    }

    @Test
    fun `动作类型在包里的编号就是枚举序号`() {
        // 包体写的是 ordinal，服务端按同一顺序解读；协议库一旦调整枚举顺序，这些包会静默变义。
        assertEquals(0, ContainerActionType.CLICK_ITEM.ordinal)
        assertEquals(1, ContainerActionType.SHIFT_CLICK_ITEM.ordinal)
        assertEquals(2, ContainerActionType.MOVE_TO_HOTBAR_SLOT.ordinal)
        assertEquals(4, ContainerActionType.DROP_ITEM.ordinal)
        assertEquals(6, ContainerActionType.FILL_STACK.ordinal)
        assertEquals(5, ContainerActionType.SPREAD_ITEM.ordinal)
        assertEquals(3, ContainerActionType.CREATIVE_GRAB_MAX_STACK.ordinal)
    }

    @Test
    fun `拖拽拆成开始加槽与结束三段`() {
        val container = playerInventory(stateId = 3).copy(cursor = InventoryItem(itemId = 1, count = 12))
        val packets = ContainerClicks.dragPackets(container, listOf(9, 10, 11), DragButton.LEFT)

        assertEquals(5, packets.size, "begin + 3 个 add + end")
        assertEquals(3, packets.first().stateId)
        packets.forEach { assertEquals(3, it.stateId, "三段共用拖拽开始前的状态号") }

        val begin = packets.first()
        assertEquals(ContainerActionType.SPREAD_ITEM, begin.action)
        assertEquals(SpreadItemAction.LEFT_MOUSE_BEGIN_DRAG, begin.param)
        assertEquals(-999, begin.slot, "begin 不指向任何槽位")

        assertEquals(SpreadItemAction.LEFT_MOUSE_ADD_SLOT, packets[1].param)
        assertEquals(9, packets[1].slot)
        assertEquals(SpreadItemAction.LEFT_MOUSE_ADD_SLOT, packets[3].param)
        assertEquals(11, packets[3].slot)

        val end = packets.last()
        assertEquals(SpreadItemAction.LEFT_MOUSE_END_DRAG, end.param)
        assertEquals(-999, end.slot, "end 同样不指向任何槽位")

        packets.forEach {
            val carried = assertNotNull(it.carriedItem, "三段共用点击前的光标")
            assertEquals(12, carried.count)
        }
    }

    @Test
    fun `右键与中键拖拽用各自的按钮参数`() {
        val container = playerInventory()
        val right = ContainerClicks.dragPackets(container, listOf(36), DragButton.RIGHT)
        assertEquals(SpreadItemAction.RIGHT_MOUSE_BEGIN_DRAG, right.first().param)
        assertEquals(SpreadItemAction.RIGHT_MOUSE_END_DRAG, right.last().param)

        val middle = ContainerClicks.dragPackets(container, listOf(36), DragButton.MIDDLE)
        assertEquals(SpreadItemAction.MIDDLE_MOUSE_BEGIN_DRAG, middle.first().param)
        assertEquals(SpreadItemAction.MIDDLE_MOUSE_END_DRAG, middle.last().param)
    }

    @Test
    fun `空拖拽与越界槽位被拒`() {
        assertFailsWith<IllegalArgumentException> {
            ContainerClicks.dragPackets(playerInventory(), emptyList(), DragButton.LEFT)
        }
        assertFailsWith<IllegalArgumentException> {
            ContainerClicks.dragPackets(playerInventory(), listOf(PlayerInventoryLayout.SIZE), DragButton.LEFT)
        }
    }

    @Test
    fun `创造模式取整堆是点击包的一个动作`() {
        val container = playerInventory(stateId = 5)
        val packet = ContainerClicks.grabMaxStackPacket(container, slot = 9)

        assertEquals(5, packet.stateId)
        assertEquals(9, packet.slot)
        assertEquals(ContainerActionType.CREATIVE_GRAB_MAX_STACK, packet.action)
        assertEquals(CreativeGrabAction.GRAB, packet.param)
        assertNull(packet.carriedItem, "光标本来就是空的")
        // 参数编码是 ordinal + 2：写错会被服务端解读成越界下标，协议库在反序列化时崩溃。
        assertEquals(2, CreativeGrabAction.GRAB.getId())
    }
}
