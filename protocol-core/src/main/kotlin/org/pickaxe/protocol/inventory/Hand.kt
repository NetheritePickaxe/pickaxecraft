package org.pickaxe.protocol.inventory

/**
 * 交互用的是哪只手。
 *
 * 协议里几乎所有交互包都带这个字段：挖掘、放置、使用物品、挥手。缺省是主手；副手只有
 * 在“主手正在做别的事”时才有意义（例如主手举盾、副手放方块），因此本库不把它藏在参数列表
 * 中间，而是作为可命名参数放在最后。
 */
public enum class Hand {
    MAIN_HAND,
    OFF_HAND,
}
