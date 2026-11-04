package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.FillStackAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket
import org.pickaxe.protocol.inventory.Container
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
