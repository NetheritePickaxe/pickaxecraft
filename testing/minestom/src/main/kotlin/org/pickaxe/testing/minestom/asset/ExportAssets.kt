package org.pickaxe.testing.minestom.asset

import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.item.Material
import net.minestom.server.potion.PotionEffect
import net.minestom.server.registry.Registries
import org.pickaxe.protocol.ProtocolVersion
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/** 生成数组时每行放多少项：一项一行会让文件膨胀到三千行，审查 diff 反而更难读。 */
private const val ITEMS_PER_LINE: Int = 16

/** 实体类型按名字分组，每行放多少项。 */
private const val NAMES_PER_LINE: Int = 4

/** 表里 -1 表示“原型没有这个组件”；0 是合法取值，不能当哨兵。 */
private const val ABSENT: Int = -1

private const val USAGE: String =
    "用法: export-assets <item-keys|item-prototypes|attribute-table|effect-keys|living-entity-types> " +
        "<输出路径> <来源版本>"

/**
 * 版本资产导出器。
 *
 * 物品键名、属性、效果、生物分层这些表的权威来源是 Minestom 的注册表，但它们必须固化进
 * `protocol-core` 才能被客户端使用。固化必须**可重跑**：升级协议库后重新执行同一个任务，
 * 用生成的 diff 判断上游改了什么，而不是依赖某次手工脚本的记忆。
 *
 * 五张表共用一个入口，按子命令分支：参数校验、稠密 id 检查与文件写出只有一份。
 * `exportRegistries` 任务会把五个子命令一次跑完。
 *
 * 导出器不启动服务端：`Registries.vanilla()` 就够了——原版注册表在 `VanillaRegistries`
 * 构造末尾绑定物品原型，绑定前读 `Material#prototype()` 会抛 `IllegalStateException`。
 *
 * 输出统一使用 `\n` 行尾：生成的 diff 要在 Ubuntu CI 与 Windows 本地一致，
 * 不能让平台的换行策略决定“有没有变更”。
 */
public fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: abort(USAGE)
    val target = Path(args.getOrNull(1) ?: abort("$command 缺少输出路径"))
    val source = args.getOrNull(2)
        ?: abort("$command 缺少来源版本，例如 “Minestom 2026.10.07-26.2”")

    // 第一次读注册表会加载全部原版数据并绑定物品原型，耗时数秒，属于预期成本。
    Registries.vanilla()

    when (command) {
        "item-keys" -> exportItemKeys(target, source)
        "item-prototypes" -> exportItemPrototypes(target, source)
        "attribute-table" -> exportAttributeTable(target, source)
        "effect-keys" -> exportEffectKeys(target, source)
        "living-entity-types" -> exportLivingEntityTypes(target, source)
        else -> abort("未知子命令 “$command”；$USAGE")
    }
}

/** 物品 id -> 键名，按 id 稠密排列。 */
private fun exportItemKeys(target: Path, source: String) {
    val materials = Material.values().sortedBy { it.id() }
    requireDenseIds(materials.map { it.id() }, "物品")
    val names = materials.map { it.key().asString() }

    write(target, renderItemKeys(source, names))
    println("event=asset.exported asset=item-keys items=${names.size} target=$target")
}

/**
 * 导出 `ItemPrototypes.kt`。
 *
 * 只写**原型真实带了的组件**：用 `has` 判断存在性，不拿 `get` 的默认值代替“没有这个组件”。
 * 表按物品 id 稠密排列，缺省写 -1（0 是合法取值，不能当哨兵）。
 */
private fun exportItemPrototypes(target: Path, source: String) {
    val materials = Material.values().sortedBy { it.id() }
    requireDenseIds(materials.map { it.id() }, "物品")

    val maxDamage = IntArray(materials.size) { ABSENT }
    val maxStackSize = IntArray(materials.size) { ABSENT }
    for (material in materials) {
        val prototype = material.prototype()
        val id = material.id()
        if (prototype.has(DataComponents.MAX_DAMAGE)) {
            maxDamage[id] = prototype.get(DataComponents.MAX_DAMAGE)
                ?: abort("${material.key()} 声明了 max_damage 却取不到值")
        }
        if (prototype.has(DataComponents.MAX_STACK_SIZE)) {
            maxStackSize[id] = prototype.get(DataComponents.MAX_STACK_SIZE)
                ?: abort("${material.key()} 声明了 max_stack_size 却取不到值")
        }
    }

    write(target, renderItemPrototypes(source, maxDamage, maxStackSize))
    println(
        "event=asset.exported asset=item-prototypes items=${materials.size} " +
            "durable=${maxDamage.count { it != ABSENT }} customStack=${maxStackSize.count { it != ABSENT }} " +
            "target=$target",
    )
}

/** 属性 wire id -> 键名；协议库内置的枚举表会错位，所以这张表必须由注册表导出。 */
private fun exportAttributeTable(target: Path, source: String) {
    val attributes = Attribute.values().sortedBy { it.id() }
    requireDenseIds(attributes.map { it.id() }, "属性")
    val keys = attributes.map { it.key().asString() }

    write(target, renderAttributeTable(source, keys))
    println("event=asset.exported asset=attribute-table entries=${keys.size} target=$target")
}

/** 效果 wire id -> 键名；效果注册表不随登录下发，只能固化成版本资产。 */
private fun exportEffectKeys(target: Path, source: String) {
    val effects = PotionEffect.values().sortedBy { it.id() }
    requireDenseIds(effects.map { it.id() }, "效果")
    val keys = effects.map { it.key().asString() }

    write(target, renderEffectKeys(source, keys))
    println("event=asset.exported asset=effect-keys entries=${keys.size} target=$target")
}

/** 走“生物”元数据层级的实体类型；判据是服务端是否给该类型发属性。 */
private fun exportLivingEntityTypes(target: Path, source: String) {
    val entities = EntityType.values().sortedBy { it.id() }
    requireDenseIds(entities.map { it.id() }, "实体类型")
    val living = entities
        .filter { it.shouldSendAttributes() }
        .map { it.key().value().uppercase() }

    write(target, renderLivingEntityTypes(source, entities.size, living))
    println(
        "event=asset.exported asset=living-entity-types entities=${entities.size} " +
            "living=${living.size} target=$target",
    )
}

private fun requireDenseIds(ids: List<Int>, what: String) {
    ids.forEachIndexed { index, id ->
        check(id == index) { "$what id 不连续：下标 $index 上是 id $id，写出来的表会和编号错位" }
    }
}

private fun write(target: Path, text: String) {
    target.parent?.let { it.toFile().mkdirs() }
    target.writeText(text)
}

private fun renderItemKeys(source: String, names: List<String>): String = buildString {
    header(source, "物品注册表", names.size)
    line("package org.pickaxe.protocol.item")
    line()
    line("/**")
    line(" * 物品 wire id -> 键名（如 `minecraft:stone`）。")
    line(" *")
    line(" * 协议库的 `ItemStack` 只有数字 id，不带任何注册表；本表是当前协议号的权威映射。")
    line(" * 键名能查到，附魔、耐久之类的组件仍然不在本层建模。")
    line(" */")
    line("internal object ItemKeys {")
    line()
    line("    /** 下标就是 wire id；越界返回 null。 */")
    line("    fun key(id: Int): String? = NAMES.getOrNull(id)")
    line()
    line("    private val NAMES: Array<String> = arrayOf(")
    names.forEach { line("        \"$it\",") }
    line("    )")
    line("}")
}

private fun renderItemPrototypes(source: String, maxDamage: IntArray, maxStackSize: IntArray): String =
    buildString {
        header(source, "物品注册表", maxDamage.size)
        line("package org.pickaxe.protocol.item")
        line()
        line("/**")
        line(" * 物品原型的固有组件：最大耐久与堆叠上限。")
        line(" *")
        line(" * 物品包里的 `data_components` 是**补丁**，只带与原型不同的项：钻石剑的 `max_damage`")
        line(" * 恰好是默认值 1561 时，补丁里没有这个键。要还原“这把剑能修多少次”，只能查这张表。")
        line(" * 与 [ItemKeys] 一样是版本资产，升级协议库时必须重跑导出。")
        line(" */")
        line("internal object ItemPrototypes {")
        line()
        line("    /** 物品总数（与 [ItemKeys] 的条目数一致）；越界查询返回 null。 */")
        line("    const val SIZE: Int = ${maxDamage.size}")
        line()
        line("    /** 能承受的耐久；原型没有 `max_damage`（不可损坏）或 id 越界时为 null。0 是合法值。 */")
        line("    fun maxDamage(itemId: Int): Int? = if (itemId in 0 until SIZE) DAMAGE[itemId].orAbsent() else null")
        line()
        line("    /** 堆叠上限；原型没有 `max_stack_size` 时为 64，id 越界时为 null。 */")
        line("    fun maxStackSize(itemId: Int): Int? = when {")
        line("        itemId !in 0 until SIZE -> null")
        line("        else -> STACK_SIZE[itemId].orAbsent() ?: DEFAULT_STACK_SIZE")
        line("    }")
        line()
        line("    /** 原版在没有 `max_stack_size` 组件时的默认堆叠上限。 */")
        line("    private const val DEFAULT_STACK_SIZE: Int = 64")
        line()
        line("    private fun Int.orAbsent(): Int? = takeIf { it != ABSENT }")
        line()
        line("    /** -1 表示原型没有这个组件；0 是真实取值，不参与合并。 */")
        line("    private const val ABSENT: Int = -1")
        line()
        line("    private val DAMAGE: IntArray = intArrayOf(")
        appendIntArray(maxDamage)
        line("    )")
        line()
        line("    private val STACK_SIZE: IntArray = intArrayOf(")
        appendIntArray(maxStackSize)
        line("    )")
        line("}")
    }

private fun renderAttributeTable(source: String, keys: List<String>): String = buildString {
    header(source, "属性注册表", keys.size)
    line("package org.pickaxe.protocol.entity")
    line()
    line("/**")
    line(" * 属性 wire id -> 键名（如 `minecraft:max_health`）。")
    line(" *")
    line(" * 为什么不用协议库的 [org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType]：")
    line(" * 它的内置表按枚举 [ordinal] 反查 wire id，而枚举的声明序与 26.2 的 wire id 只是恰好同为")
    line(" * 字母序——构造参数里记录的 id 是旧版本的，已经错位。本表由 Minestom 注册表直接导出，")
    line(" * 是当前协议号的权威映射；解包时用 `type.id`（即 ordinal，恰好等于 wire id）还原编号后查表。")
    line(" */")
    line("internal object AttributeTable {")
    line()
    line("    /** 未收录的 id 用 null 兜底；正常服务端不会出现。 */")
    line("    fun key(id: Int): String? = KEYS[id]")
    line()
    line("    private val KEYS: Map<Int, String> = mapOf(")
    keys.forEachIndexed { id, key -> line("        $id to \"$key\",") }
    line("    )")
    line("}")
}

private fun renderEffectKeys(source: String, keys: List<String>): String = buildString {
    header(source, "效果注册表", keys.size)
    line("package org.pickaxe.protocol.entity")
    line()
    line("/**")
    line(" * 状态效果的数字 id → `minecraft:*` 键名。")
    line(" *")
    line(" * 协议里的 `update_mob_effect` / `remove_mob_effect` 只带一个 varint id，效果注册表")
    line(" * **不随登录下发**（配置阶段的注册表里没有 `minecraft:mob_effect`）。MCProtocolLib 26.2")
    line(" * 的 `Effect.from(id)` 直接返回 `VALUES[id]`，即 id == 枚举 ordinal；枚举名小写后恰好是")
    line(" * 26.2 注册表的键名，因此这里按同一顺序固化成版本资产，避免上层依赖协议库枚举。")
    line(" *")
    line(" * 与 [AttributeTable] / `ItemKeys` 一样：升级协议库时必须重新核对这张表。")
    line(" */")
    line("public object EffectKeys {")
    line()
    line("    private val KEYS: List<String> = listOf(")
    keys.forEach { line("        \"$it\",") }
    line("    )")
    line()
    line("    /** 协议 ${ProtocolVersion.PROTOCOL_VERSION} 的效果总数。 */")
    line("    public val size: Int get() = KEYS.size")
    line()
    line("    /** 查键名；未知 id 返回 null（保留数字 id 让调用方仍能显示）。 */")
    line("    public fun key(id: Int): String? = KEYS.getOrNull(id)")
    line("}")
}

private fun renderLivingEntityTypes(source: String, totalEntities: Int, living: List<String>): String =
    buildString {
        header(source, "实体类型（entity_type）注册表", totalEntities, extra = "，其中 ${living.size} 项为生物")
        line("// 判据：RegistryData.EntityEntry#shouldSendAttributes()，即 packetType 为 LIVING 或 PLAYER。")
        line("package org.pickaxe.protocol.entity")
        line()
        line("/**")
        line(" * 哪些实体类型走「生物」元数据层级。")
        line(" *")
        line(" * 实体元数据是一张「下标 → 值」的表，下标含义取决于实体类层级：所有实体共有 0–7，")
        line(" * 生物层从 8 开始，血量在 9。协议包只带下标和值，**不带层级**，因此读血量之前必须先知道")
        line(" * 这个实体是不是生物，否则会把掉落物、船、展示实体同一下标的其它字段误当成血量。")
        line(" *")
        line(" * 本表是当前协议号的权威分层。未知类型一律返回 false——不猜层级，血量留空。")
        line(" */")
        line("internal object LivingEntityTypes {")
        line()
        line("    fun isLiving(type: String): Boolean = type in LIVING")
        line()
        line("    /** 与 MCPL 的 EntityType 枚举名一致：注册表键去掉命名空间后转大写。 */")
        line("    private val LIVING: Set<String> = setOf(")
        living.chunked(NAMES_PER_LINE).forEach { row ->
            line("        " + row.joinToString(", ") { "\"$it\"" } + ",")
        }
        line("    )")
        line("}")
    }

/** 每张资产文件开头的两行说明；来源与数量都写进注释，避免与生成器漂移。 */
private fun StringBuilder.header(
    source: String,
    label: String,
    count: Int,
    extra: String = "",
) {
    line("// 由 $source 的${label}自动导出（协议 ${ProtocolVersion.PROTOCOL_VERSION}，共 $count 项$extra）。")
    line("// 不要手改：升级协议库/Minestom 后重跑 “./gradlew :testing:minestom:exportRegistries” 整表替换。")
}

/** 显式 `\n`：生成的 diff 不能随平台换行策略变化。 */
private fun StringBuilder.line(text: String = "") {
    append(text).append('\n')
}

/** 每行若干项：一项一行会让生成文件膨胀到三千行，审查 diff 时反而更难读。 */
private fun StringBuilder.appendIntArray(values: IntArray) {
    values.toList().chunked(ITEMS_PER_LINE).forEach { row ->
        line("        " + row.joinToString(", ") + ",")
    }
}

private fun abort(message: String): Nothing {
    System.err.println("error=$message")
    exitProcess(1)
}
