package org.pickaxe.protocol.inventory

/**
 * 一次对窗口槽位的点击意图。
 *
 * 协议里所有点击共用一个包，字段是「动作类型 + 参数」的组合（左键/右键、哪个快捷栏格、
 * 丢一个还是整堆）。把这张表交给调用方去背没有意义，因此这里只表达**想做什么**，
 * 到包字段的映射放在适配层并单测。
 *
 * 创造模式的两个写入动作（整堆拿取 `CREATIVE_GRAB_MAX_STACK` 与 `set_creative_mode_slot`）
 * 也不在这里：前者需要游戏模式门控，后者只有 id 与数量，见 [DragButton] 同目录的
 * [CreativeItem] 与适配层的对应入口。点击的结果**不做本地预测**：本端只如实声明
 * “点之前光标上是这些”，由服务端下发权威内容，[Container.cursor] 随之下发。
 */
public sealed interface SlotClick {

    /**
     * 拿起或放下：光标为空时拿起该格的物品，光标有物品时放下。
     *
     * [rightButton] 为真时是右键语义：拿起半堆、或只放一个；左键是整堆。
     */
    public data class Pickup(public val rightButton: Boolean = false) : SlotClick

    /**
     * 快速转移（原版 Shift 点击）：把该格物品移到对侧容器。
     *
     * 落到背包还是容器由服务端决定（容器 → 背包，背包 → 容器，盔甲与副手另有规则），
     * 本端不推测结果。
     */
    public data class QuickMove(public val rightButton: Boolean = false) : SlotClick

    /**
     * 与快捷栏第 [hotbar] 格交换（原版按数字键）。[hotbar] 取值 0–8。
     *
     * 副手交换（协议里是同一动作的参数 40）需要区分手上是主手还是副手，本轮不做。
     */
    public data class SwapWithHotbar(public val hotbar: Int) : SlotClick {
        init {
            require(hotbar in 0 until PlayerInventoryLayout.HOTBAR_SIZE) {
                "快捷栏下标应在 0..${PlayerInventoryLayout.HOTBAR_SIZE - 1} 之间，实际为 $hotbar"
            }
        }
    }

    /** 丢弃：[wholeStack] 为真丢整堆，否则丢一个。 */
    public data class Throw(public val wholeStack: Boolean = false) : SlotClick

    /**
     * 双击收拢：把背包里与该格同类的物品并到光标上。
     *
     * 协议里它与「填充堆叠」（`FILL_STACK`）是同一个动作。
     */
    public data object PickupAll : SlotClick
}
