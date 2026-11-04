package org.pickaxe.protocol.entity

/**
 * 实体的一条属性：键名、当前值与本包里带的修改器。
 *
 * [key] 形如 `minecraft:max_health`；[value] 是服务端**代算好**的结果（基值与全部修改器
 * 叠加之后），本库不再二次计算。修改器列表只反映**最近一次下发的这一项**——服务端
 * 每次只发改动的属性，没出现的键保持上一次的值。
 */
public data class EntityAttribute(
    /** 属性键名，例如 `minecraft:max_health`。 */
    public val key: String,
    /** 服务端计算后的当前值。 */
    public val value: Double,
    /** 本包里带的修改器；出生全量之外可能为空。 */
    public val modifiers: List<EntityAttributeModifier> = emptyList(),
)

/**
 * 属性修改器。协议只透传，不代算：服务端下发的 [EntityAttribute.value] 已含其效果。
 *
 * [operation] 是协议的操作名：`ADD`（加到基值）、`ADD_MULTIPLIED_BASE`（按基值乘算）、
 * `ADD_MULTIPLIED_TOTAL`（按总和乘算）。
 */
public data class EntityAttributeModifier(
    /** 修改器的资源键，例如 `minecraft:effect.speed`。 */
    public val id: String,
    public val amount: Double,
    public val operation: String,
)
