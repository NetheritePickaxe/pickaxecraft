package org.pickaxe.protocol.internal

import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.inventory.PlayerInventoryLayout

/**
 * 另一个背包槽位编号体系到窗口槽位的换算。
 *
 * 协议里存在两套编号：
 *
 * - **窗口槽位**：`container_set_content` / `container_set_slot` 用的就是它，窗口 0 的含义见
 *   [PlayerInventoryLayout]；打开箱子时前 N 个是容器内容，其后是主背包与快捷栏。
 * - **背包槽位**：`set_player_inventory`（26.2 新增）用的编号，0–8 快捷栏、9–35 主背包、
 *   36–39 依次是靴/腿/胸/头、40 副手。这个包不带窗口 id，只能用来改玩家自己的背包。
 *
 * 两套编号在护甲段是**反序**的（背包 36 是靴，窗口 5 是头盔），照抄下标会把护甲放反。
 * 换算独立成函数并单测，避免在实际链路里才暴露。
 */
internal object PlayerSlotMapping {

    /**
     * 背包槽位 → 窗口 0 槽位；无法对应（越界）时返回 null。
     *
     * 合成格与合成结果没有背包槽位，因此换算只覆盖 0–40。
     */
    fun toWindowSlot(playerSlot: Int): Int? = when (playerSlot) {
        in 0..8 -> PlayerInventoryLayout.HOTBAR_FIRST + playerSlot
        in 9..35 -> playerSlot
        36 -> 8 // 靴
        37 -> 7 // 腿
        38 -> 6 // 胸甲
        39 -> 5 // 头盔
        40 -> PlayerInventoryLayout.OFFHAND
        else -> null
    }
}

/**
 * 适配层内部的可变容器状态。
 *
 * 只在连接的网络线程上读写；向上只交出不可变的 [Container] 快照。槽位按需增长：
 * 服务端可能先发单槽更新、后发整包内容，短列表比“猜一个长度”更诚实——没收到过的槽
 * 读出来就是 null，与空槽的表现一致。
 */
internal class MutableContainer(val windowId: Int) {

    var type: String? = null
    var title: String? = null
    var stateId: Int = 0

    /** 光标物品；记在**活动窗口**上，与点这个窗口的格子时声明给服务端的那份保持一致。 */
    var cursor: InventoryItem? = null

    private val items = ArrayList<InventoryItem?>()

    /** 用整包内容替换全部槽位与光标。 */
    fun setContent(stateId: Int, items: List<InventoryItem?>, cursor: InventoryItem?) {
        this.stateId = stateId
        this.items.clear()
        this.items.addAll(items)
        this.cursor = cursor
    }

    /** 单槽更新；槽位超出当前长度时用空槽补齐。越界（负数）直接忽略。 */
    fun setSlot(slot: Int, item: InventoryItem?) {
        if (slot < 0) return
        while (items.size <= slot) items.add(null)
        items[slot] = item
    }

    fun snapshot(): Container = Container(
        windowId = windowId,
        type = type,
        title = title,
        stateId = stateId,
        slots = items.toList(),
        cursor = cursor,
    )
}
