package org.pickaxe.protocol.world

/**
 * 方块实体附带的一段 NBT，已转换成不依赖协议库的树。
 *
 * `block_entity_data` 的值是任意 NBT，协议库把它交成自己的 `NbtMap`。那是第三方类型，
 * 不能出现在本库的公共 API 上（见 `docs/architecture.md` 的依赖边界），所以这里重建一棵
 * 只有四种形态的小树：数值、文本、列表、复合。
 *
 * 数组（`byte[]` / `int[]` / `long[]`）展开成 [Items] 的数值项：方块实体的 NBT 都很小，
 * 换来的是调用方少一条分支。代价是“数组”与“列表”在模型上不再区分；需要区分时看具体字段的
 * 文档，而不是靠类型猜。
 *
 * 数值保留原宽度（`Byte` / `Int` / `Long` / `Float` / `Double`）：NBT 里 `int` 与 `long`
 * 的区分是有语义的（种子、时间戳），归一成 `Double` 会悄悄丢精度。取标量时用 [asLong] /
 * [asDouble]，它们会做合理的数值转换。
 */
public sealed interface NbtValue {

    /** 任意 NBT 数值标签。 */
    public data class Num(public val value: Number) : NbtValue

    /** `TAG_String`。 */
    public data class Text(public val value: String) : NbtValue

    /** `TAG_List` 与三种数组标签。 */
    public data class Items(public val values: List<NbtValue>) : NbtValue

    /** `TAG_Compound`。 */
    public data class Compound(public val entries: Map<String, NbtValue>) : NbtValue

    /** 按键取子项；不是复合标签、或键不存在时返回 null。 */
    public fun child(key: String): NbtValue? = (this as? Compound)?.entries?.get(key)

    /** 取文本；不是文本标签时返回 null。 */
    public fun asText(): String? = (this as? Text)?.value

    /**
     * 取整数；数值标签按 `toLong()` 转换，其余返回 null。
     *
     * 浮点标签也会被截断取整——NBT 里没有隐式类型提升，调用方要自己确认字段宽度。
     */
    public fun asLong(): Long? = (this as? Num)?.value?.toLong()

    /** 取浮点；数值标签按 `toDouble()` 转换，其余返回 null。 */
    public fun asDouble(): Double? = (this as? Num)?.value?.toDouble()
}
