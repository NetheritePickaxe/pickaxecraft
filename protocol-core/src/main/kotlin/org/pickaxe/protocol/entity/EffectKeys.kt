// 由 Minestom 2026.10.07-26.2 的效果注册表自动导出（协议 776，共 40 项）。
// 不要手改：升级协议库/Minestom 后重跑 “./gradlew :testing:minestom:exportRegistries” 整表替换。
package org.pickaxe.protocol.entity

/**
 * 状态效果的数字 id → `minecraft:*` 键名。
 *
 * 协议里的 `update_mob_effect` / `remove_mob_effect` 只带一个 varint id，效果注册表
 * **不随登录下发**（配置阶段的注册表里没有 `minecraft:mob_effect`）。MCProtocolLib 26.2
 * 的 `Effect.from(id)` 直接返回 `VALUES[id]`，即 id == 枚举 ordinal；枚举名小写后恰好是
 * 26.2 注册表的键名，因此这里按同一顺序固化成版本资产，避免上层依赖协议库枚举。
 *
 * 与 [AttributeTable] / `ItemKeys` 一样：升级协议库时必须重新核对这张表。
 */
public object EffectKeys {

    private val KEYS: List<String> = listOf(
        "minecraft:speed",
        "minecraft:slowness",
        "minecraft:haste",
        "minecraft:mining_fatigue",
        "minecraft:strength",
        "minecraft:instant_health",
        "minecraft:instant_damage",
        "minecraft:jump_boost",
        "minecraft:nausea",
        "minecraft:regeneration",
        "minecraft:resistance",
        "minecraft:fire_resistance",
        "minecraft:water_breathing",
        "minecraft:invisibility",
        "minecraft:blindness",
        "minecraft:night_vision",
        "minecraft:hunger",
        "minecraft:weakness",
        "minecraft:poison",
        "minecraft:wither",
        "minecraft:health_boost",
        "minecraft:absorption",
        "minecraft:saturation",
        "minecraft:glowing",
        "minecraft:levitation",
        "minecraft:luck",
        "minecraft:unluck",
        "minecraft:slow_falling",
        "minecraft:conduit_power",
        "minecraft:dolphins_grace",
        "minecraft:bad_omen",
        "minecraft:hero_of_the_village",
        "minecraft:darkness",
        "minecraft:trial_omen",
        "minecraft:raid_omen",
        "minecraft:wind_charged",
        "minecraft:weaving",
        "minecraft:oozing",
        "minecraft:infested",
        "minecraft:breath_of_the_nautilus",
    )

    /** 协议 776 的效果总数。 */
    public val size: Int get() = KEYS.size

    /** 查键名；未知 id 返回 null（保留数字 id 让调用方仍能显示）。 */
    public fun key(id: Int): String? = KEYS.getOrNull(id)
}
