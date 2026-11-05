// 由 Minestom 2026.10.07-26.2 的 entity_type 注册表自动导出（协议 776，158 项中 93 项为生物）。
// 判据：RegistryData.EntityEntry#shouldSendAttributes()，即 packetType 为 LIVING 或 PLAYER。
// 不要手改：升级协议库/Minestom 后重新导出整表替换，并与 MCPL 的 EntityType 枚举核对名字。
package org.pickaxe.protocol.entity

/**
 * 哪些实体类型走「生物」元数据层级。
 *
 * 实体元数据是一张「下标 → 值」的表，下标含义取决于实体类层级：所有实体共有 0–7，
 * 生物层从 8 开始，血量在 9。协议包只带下标和值，**不带层级**，因此读血量之前必须先知道
 * 这个实体是不是生物，否则会把掉落物、船、展示实体同一下标的其它字段误当成血量。
 *
 * 本表是当前协议号的权威分层。未知类型一律返回 false——不猜层级，血量留空。
 */
internal object LivingEntityTypes {

    fun isLiving(type: String): Boolean = type in LIVING

    /** 与 MCPL 的 EntityType 枚举名一致：注册表键去掉命名空间后转大写。 */
    private val LIVING: Set<String> = setOf(
        "ALLAY", "ARMADILLO", "ARMOR_STAND", "AXOLOTL",
        "BAT", "BEE", "BLAZE", "BOGGED",
        "BREEZE", "CAMEL", "CAMEL_HUSK", "CAT",
        "CAVE_SPIDER", "CHICKEN", "COD", "COPPER_GOLEM",
        "COW", "CREAKING", "CREEPER", "DOLPHIN",
        "DONKEY", "DROWNED", "ELDER_GUARDIAN", "ENDERMAN",
        "ENDERMITE", "ENDER_DRAGON", "EVOKER", "FOX",
        "FROG", "GHAST", "GIANT", "GLOW_SQUID",
        "GOAT", "GUARDIAN", "HAPPY_GHAST", "HOGLIN",
        "HORSE", "HUSK", "ILLUSIONER", "IRON_GOLEM",
        "LLAMA", "MAGMA_CUBE", "MANNEQUIN", "MOOSHROOM",
        "MULE", "NAUTILUS", "OCELOT", "PANDA",
        "PARCHED", "PARROT", "PHANTOM", "PIG",
        "PIGLIN", "PIGLIN_BRUTE", "PILLAGER", "PLAYER",
        "POLAR_BEAR", "PUFFERFISH", "RABBIT", "RAVAGER",
        "SALMON", "SHEEP", "SHULKER", "SILVERFISH",
        "SKELETON", "SKELETON_HORSE", "SLIME", "SNIFFER",
        "SNOW_GOLEM", "SPIDER", "SQUID", "STRAY",
        "STRIDER", "SULFUR_CUBE", "TADPOLE", "TRADER_LLAMA",
        "TROPICAL_FISH", "TURTLE", "VEX", "VILLAGER",
        "VINDICATOR", "WANDERING_TRADER", "WARDEN", "WITCH",
        "WITHER", "WITHER_SKELETON", "WOLF", "ZOGLIN",
        "ZOMBIE", "ZOMBIE_HORSE", "ZOMBIE_NAUTILUS", "ZOMBIE_VILLAGER",
        "ZOMBIFIED_PIGLIN",
    )
}
