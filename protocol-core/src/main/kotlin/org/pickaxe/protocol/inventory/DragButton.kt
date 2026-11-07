package org.pickaxe.protocol.inventory

/**
 * 创造模式整堆写入用的最小物品描述。
 *
 * `set_creative_mode_slot` 的包里只有 id 与数量，组件段由调用方自行构造——本库不提供
 * 组件写入（那需要把组件补丁反向序列化成字节），传入这里就等于声明「我要一个无组件物品」。
 * 服务端会以收到的内容为准下发权威快照，本端不做预测。
 */
public data class CreativeItem(
    /** 协议里的全局物品 id（`minecraft:item` 注册表下标，如 1 = `minecraft:stone`）。 */
    public val itemId: Int,
    /** 堆叠数量；超过该物品上限时服务端可能截断或拒绝，本层不校验。 */
    public val count: Int,
)

/**
 * 拖拽（原版按住左/右键划过若干格）的按键。
 *
 * 拖拽**不是一次点击**：协议里它是 begin → 每个槽一条 add → end 三段，服务端只在
 * end 收到后一次性结算。三段共用拖拽开始前那份快照的 `stateId` 与光标——拖拽期间
 * 服务端不结算，光标不会变。
 *
 * 语义由服务端按模式解释：
 * - [LEFT]：把光标上的整堆均分到各槽；
 * - [RIGHT]：每槽放 1 个；
 * - [MIDDLE]：创造模式每槽放满（原版在生存模式忽略）。
 */
public enum class DragButton {
    LEFT,
    RIGHT,
    MIDDLE,
}
