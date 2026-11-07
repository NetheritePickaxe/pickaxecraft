package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.data.game.inventory.CreativeGrabAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.FillStackAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.SpreadItemAction
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.DragButton
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import org.pickaxe.protocol.inventory.SlotClick

/**
 * [SlotClick] → `container_click` 的字段映射。
 *
 * 单独成对象有两个理由：一是这张「动作 + 参数」→ 意图的对照表需要单测钉住（协议里参数是
 * 裸字节，错了不会有任何编译期提示）；二是包里那两处「客户端自称看到的内容」字段
 * （`changedSlots` / `carriedItem`）怎么填，是一个必须解释清楚的取舍，只应该出现在一个地方。
 *
 * **不做本地预测**：`changedSlots` 恒为空——重算点击结果需要物品组件（`max_stack_size`）
 * 与快速转移的合并规则，那是另一套注册表。`carriedItem` 填的是**点击前**光标上的物品，
 * 不是点击后的预测值：本库只知道点击前自己手上是什么，如实说“我手上是这些”才是诚实的
 * 声明。两处都会让服务端的去同步检测发现差异，于是它把权威内容（含光标）重新下发——
 * 每次点击后本地视图都是服务端的内容，绝不漂移。
 *
 * 反过来填 `null`（“光标是空的”）看似更省事，实则是个会留下错误状态的谎：当点击结束时
 * 光标真的变空，服务端认为两端一致、什么也不发，本端就会永远停在点击前的光标上。
 */
internal object ContainerClicks {

    /**
     * 校验点击的槽位。
     *
     * 窗口 0 的槽数固定（[PlayerInventoryLayout.SIZE]），越界直接拒绝。其余窗口**不查上限**：
     * 容器整包可能只带容器自己的槽位（Minestom 就是这样），后面的背包段在快照里不存在，
     * 但仍然是合法点击目标——按快照长度拦截会挡住真实能点的格子。
     */
    fun requireSlot(container: Container, slot: Int) {
        require(slot >= 0) { "槽位不能为负：$slot" }
        require(!container.isPlayerInventory || slot < PlayerInventoryLayout.SIZE) {
            "玩家背包只有 ${PlayerInventoryLayout.SIZE} 个窗口槽位，槽位 $slot 越界"
        }
    }

    /** 意图 → 协议里的动作类型与参数。 */
    fun action(click: SlotClick): Pair<ContainerActionType, ContainerAction> = when (click) {
        is SlotClick.Pickup -> ContainerActionType.CLICK_ITEM to
            if (click.rightButton) ClickItemAction.RIGHT_CLICK else ClickItemAction.LEFT_CLICK

        is SlotClick.QuickMove -> ContainerActionType.SHIFT_CLICK_ITEM to
            if (click.rightButton) ShiftClickItemAction.RIGHT_CLICK else ShiftClickItemAction.LEFT_CLICK

        is SlotClick.SwapWithHotbar -> ContainerActionType.MOVE_TO_HOTBAR_SLOT to requireNotNull(
            MoveToHotbarAction.from(click.hotbar),
        ) { "快捷栏下标没有对应的协议参数：${click.hotbar}" }

        is SlotClick.Throw -> ContainerActionType.DROP_ITEM to
            if (click.wholeStack) DropItemAction.DROP_SELECTED_STACK else DropItemAction.DROP_FROM_SELECTED

        SlotClick.PickupAll -> ContainerActionType.FILL_STACK to FillStackAction.FILL
    }

    /**
     * 构造点击包。
     *
     * `stateId` 与光标物品都来自 [container] 这份快照——它由调用方持有且不可变，因此不需要在
     * 网络线程之外读适配层的可变容器状态。
     */
    fun packet(container: Container, slot: Int, click: SlotClick): ServerboundContainerClickPacket {
        requireSlot(container, slot)
        val (type, param) = action(click)
        return ServerboundContainerClickPacket(
            container.windowId,
            container.stateId,
            slot,
            type,
            param,
            container.cursor?.let(::hashed),
            emptyMap(),
        )
    }

    /**
     * 构造一次拖拽的全部包：begin → 每个槽一条 add → end。
     *
     * 拖拽不是一次点击：服务端只在 end 收到后一次性结算（Minestom 把 add 槽攒进列表，
     * end 才应用「均分/每槽一个」），所以 begin 与 end 的槽位是固定的 -999（不指任何槽），
     * 只有 add 带目标槽位。三段共用拖拽开始前那份快照的 `stateId` 与光标——拖拽期间
     * 服务端不结算，光标不会变；照常不预测 `changedSlots`。
     *
     * 按键参数与协议字节一一对应（0/4/8 起，+1 加，+2 结）：左键均分、右键每槽一个、
     * 中键创造模式放满。空槽列表直接拒绝——begin+end 什么也不做的拖拽是调用方的错误，
     * 发出去只会让服务端的拖拽列表凭空清空。
     */
    fun dragPackets(
        container: Container,
        slots: List<Int>,
        button: DragButton,
    ): List<ServerboundContainerClickPacket> {
        require(slots.isNotEmpty()) { "拖拽至少要有一个目标槽位" }
        slots.forEach { requireSlot(container, it) }
        val carried = container.cursor?.let(::hashed)
        val begin = spreadAction(button, 0)
        val add = spreadAction(button, 1)
        val end = spreadAction(button, 2)
        return buildList(slots.size + 2) {
            add(click(container, CLICK_OUTSIDE_SLOT, begin, carried))
            slots.forEach { add(click(container, it, add, carried)) }
            add(click(container, CLICK_OUTSIDE_SLOT, end, carried))
        }
    }

    /**
     * 构造创造模式「取整堆」的包（原版中键点击）。
     *
     * 它是 `container_click` 的一个动作而不是独立包：`CREATIVE_GRAB_MAX_STACK` 把该格物品
     * 按堆叠上限复制到光标。本库不判断游戏模式——调用方（Session）知道自己的模式并负责门控，
     * 这里只负责把意图变成正确的字段。
     */
    fun grabMaxStackPacket(container: Container, slot: Int): ServerboundContainerClickPacket {
        requireSlot(container, slot)
        return click(container, slot, CREATIVE_GRAB_MAX_STACK, container.cursor?.let(::hashed))
    }

    private fun click(
        container: Container,
        slot: Int,
        action: Pair<ContainerActionType, ContainerAction>,
        carried: HashedStack?,
    ): ServerboundContainerClickPacket = ServerboundContainerClickPacket(
        container.windowId,
        container.stateId,
        slot,
        action.first,
        action.second,
        carried,
        emptyMap(),
    )

    /** [DragButton] + 拖拽阶段（0 起、1 加、2 结）→ 协议动作参数。 */
    private fun spreadAction(button: DragButton, phase: Int): Pair<ContainerActionType, ContainerAction> {
        val action = when (button) {
            DragButton.LEFT -> when (phase) {
                0 -> SpreadItemAction.LEFT_MOUSE_BEGIN_DRAG
                1 -> SpreadItemAction.LEFT_MOUSE_ADD_SLOT
                else -> SpreadItemAction.LEFT_MOUSE_END_DRAG
            }
            DragButton.RIGHT -> when (phase) {
                0 -> SpreadItemAction.RIGHT_MOUSE_BEGIN_DRAG
                1 -> SpreadItemAction.RIGHT_MOUSE_ADD_SLOT
                else -> SpreadItemAction.RIGHT_MOUSE_END_DRAG
            }
            DragButton.MIDDLE -> when (phase) {
                0 -> SpreadItemAction.MIDDLE_MOUSE_BEGIN_DRAG
                1 -> SpreadItemAction.MIDDLE_MOUSE_ADD_SLOT
                else -> SpreadItemAction.MIDDLE_MOUSE_END_DRAG
            }
        }
        return ContainerActionType.SPREAD_ITEM to action
    }

    private val CREATIVE_GRAB_MAX_STACK: Pair<ContainerActionType, ContainerAction> =
        ContainerActionType.CREATIVE_GRAB_MAX_STACK to CreativeGrabAction.GRAB

    /** begin/end 不指向任何槽位，协议里写死的值。 */
    private const val CLICK_OUTSIDE_SLOT: Int = -999

    /**
     * 物品 → 服务端用来做去同步检测的哈希。
     *
     * 只带 id 与数量：组件哈希需要物品组件表，本库没有。缺了组件必然与“带组件的真物品”不匹配，
     * 服务端于是重发内容——这正是我们想要的权威结果。
     */
    private fun hashed(item: InventoryItem): HashedStack = HashedStack(
        item.itemId,
        item.count,
        emptyMap(),
        emptySet(),
    )
}
