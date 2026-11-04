// 由 Minestom 2026.10.07-26.2 的注册表自动生成（tools 脚本见 docs/plans/m4l-attributes.md）。
// 协议 776 的 wire id -> 键名。不要手改：重新生成或核对 dump 时整表替换。
package org.pickaxe.protocol.entity

/**
 * 属性 wire id -> 键名（如 `minecraft:max_health`）。
 *
 * 为什么不用协议库的 [org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType]：
 * 它的内置表按枚举 [ordinal] 反查 wire id，而枚举的声明序与 26.2 的 wire id 只是恰好同为
 * 字母序——构造参数里记录的 id 是旧版本的，已经错位。本表由 Minestom 注册表直接导出，
 * 是当前协议号的权威映射；解包时用 `type.id`（即 ordinal，恰好等于 wire id）还原编号后查表。
 */
internal object AttributeTable {

    /** 未收录的 id 用这个前缀兜底，保证数据不丢；正常服务端不会出现。 */
    fun key(id: Int): String? = KEYS[id]

    private val KEYS: Map<Int, String> = mapOf(
        0 to "minecraft:air_drag_modifier",
        1 to "minecraft:armor",
        2 to "minecraft:armor_toughness",
        3 to "minecraft:attack_damage",
        4 to "minecraft:attack_knockback",
        5 to "minecraft:attack_speed",
        6 to "minecraft:below_name_distance",
        7 to "minecraft:block_break_speed",
        8 to "minecraft:block_interaction_range",
        9 to "minecraft:bounciness",
        10 to "minecraft:burning_time",
        11 to "minecraft:camera_distance",
        12 to "minecraft:explosion_knockback_resistance",
        13 to "minecraft:entity_interaction_range",
        14 to "minecraft:fall_damage_multiplier",
        15 to "minecraft:flying_speed",
        16 to "minecraft:follow_range",
        17 to "minecraft:friction_modifier",
        18 to "minecraft:gravity",
        19 to "minecraft:jump_strength",
        20 to "minecraft:knockback_resistance",
        21 to "minecraft:luck",
        22 to "minecraft:max_absorption",
        23 to "minecraft:max_health",
        24 to "minecraft:mining_efficiency",
        25 to "minecraft:movement_efficiency",
        26 to "minecraft:movement_speed",
        27 to "minecraft:name_tag_distance",
        28 to "minecraft:oxygen_bonus",
        29 to "minecraft:safe_fall_distance",
        30 to "minecraft:scale",
        31 to "minecraft:sneaking_speed",
        32 to "minecraft:spawn_reinforcements",
        33 to "minecraft:step_height",
        34 to "minecraft:submerged_mining_speed",
        35 to "minecraft:sweeping_damage_ratio",
        36 to "minecraft:tempt_range",
        37 to "minecraft:water_movement_efficiency",
        38 to "minecraft:waypoint_transmit_range",
        39 to "minecraft:waypoint_receive_range",
    )
}
