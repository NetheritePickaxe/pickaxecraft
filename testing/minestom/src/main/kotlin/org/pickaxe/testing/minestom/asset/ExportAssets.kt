package org.pickaxe.testing.minestom.asset

import net.minestom.server.component.DataComponents
import net.minestom.server.item.Material
import net.minestom.server.registry.Registries
import org.pickaxe.protocol.ProtocolVersion
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/** 生成数组时每行放多少项：一项一行会让文件膨胀到三千行，审查 diff 反而更难读。 */
private const val ITEMS_PER_LINE: Int = 16

/** 表里 -1 表示“原型没有这个组件”；0 是合法取值，不能当哨兵。 */
private const val ABSENT: Int = -1

private const val USAGE: String =
    "用法: export-assets item-prototypes <输出路径> <来源版本>"

/**
 * 版本资产导出器。
 *
 * 物品键名、属性、效果这些表的权威来源是 Minestom 的注册表，但它们必须固化进 `protocol-core`
 * 才能被客户端使用。固化必须**可重跑**：升级协议库后重新执行同一个任务，用生成的 diff 判断
 * 上游改了什么，而不是依赖某次手工脚本的记忆。
 *
 * 因此这里只有一个入口、按子命令分支：本期实现 `item-prototypes`，后续资产复用同一套
 * 参数校验与文件写出，而不是每个资产各写一份脚手架。
 *
 * 导出器不启动服务端：`Registries.vanilla()` 就够了——原版注册表在 `VanillaRegistries`
 * 构造末尾绑定物品原型，绑定前读 `Material#prototype()` 会抛 `IllegalStateException`。
 */
public fun main(args: Array<String>) {
    when (val command = args.firstOrNull() ?: abort(USAGE)) {
        "item-prototypes" -> exportItemPrototypes(
            target = Path(args.getOrNull(1) ?: abort("item-prototypes 缺少输出路径")),
            source = args.getOrNull(2) ?: abort("item-prototypes 缺少来源版本，例如 “Minestom 2026.10.07-26.2”"),
        )

        else -> abort("未知子命令 “$command”；$USAGE")
    }
}

/**
 * 导出 `ItemPrototypes.kt`。
 *
 * 只写**原型真实带了的组件**：用 `has` 判断存在性，不拿 `get` 的默认值代替“没有这个组件”。
 * 表按物品 id 稠密排列，缺省写 -1（0 是合法取值，不能当哨兵）。
 */
private fun exportItemPrototypes(target: Path, source: String) {
    // 第一次读注册表会加载全部原版数据并绑定物品原型，耗时数秒，属于预期成本。
    Registries.vanilla()

    val materials = Material.values().sortedBy { it.id() }
    // 物品 id 必须连续：生成表按下标查，不连续说明上游改了数量，写出来的表会和 ItemKeys 错位。
    materials.forEachIndexed { index, material ->
        check(material.id() == index) {
            "物品 id 不连续：下标 $index 上是 ${material.key()}，id 为 ${material.id()}"
        }
    }

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

    val text = render(source, maxDamage, maxStackSize)
    target.parent?.let { it.toFile().mkdirs() }
    target.writeText(text)
    println(
        "event=asset.exported asset=item-prototypes items=${materials.size} " +
            "durable=${maxDamage.count { it != ABSENT }} customStack=${maxStackSize.count { it != ABSENT }} " +
            "target=$target",
    )
}

private fun render(source: String, maxDamage: IntArray, maxStackSize: IntArray): String = buildString {
    appendLine(
        "// 由 $source 的物品注册表自动导出（协议 ${ProtocolVersion.PROTOCOL_VERSION}，共 ${maxDamage.size} 项）。",
    )
    appendLine("// 不要手改：升级协议库/Minestom 后重跑 “./gradlew :testing:minestom:exportItemPrototypes” 整表替换。")
    appendLine("package org.pickaxe.protocol.item")
    appendLine()
    appendLine("/**")
    appendLine(" * 物品原型的固有组件：最大耐久与堆叠上限。")
    appendLine(" *")
    appendLine(" * 物品包里的 `data_components` 是**补丁**，只带与原型不同的项：钻石剑的 `max_damage`")
    appendLine(" * 恰好是默认值 1561 时，补丁里没有这个键。要还原“这把剑能修多少次”，只能查这张表。")
    appendLine(" * 与 [ItemKeys] 一样是版本资产，升级协议库时必须重跑导出。")
    appendLine(" */")
    appendLine("internal object ItemPrototypes {")
    appendLine()
    appendLine("    /** 物品总数（与 [ItemKeys] 的条目数一致）；越界查询返回 null。 */")
    appendLine("    const val SIZE: Int = ${maxDamage.size}")
    appendLine()
    appendLine("    /** 能承受的耐久；原型没有 `max_damage`（不可损坏）或 id 越界时为 null。0 是合法值。 */")
    appendLine("    fun maxDamage(itemId: Int): Int? = if (itemId in 0 until SIZE) DAMAGE[itemId].orAbsent() else null")
    appendLine()
    appendLine("    /** 堆叠上限；原型没有 `max_stack_size` 时为 64，id 越界时为 null。 */")
    appendLine("    fun maxStackSize(itemId: Int): Int? = when {")
    appendLine("        itemId !in 0 until SIZE -> null")
    appendLine("        else -> STACK_SIZE[itemId].orAbsent() ?: DEFAULT_STACK_SIZE")
    appendLine("    }")
    appendLine()
    appendLine("    /** 原版在没有 `max_stack_size` 组件时的默认堆叠上限。 */")
    appendLine("    private const val DEFAULT_STACK_SIZE: Int = 64")
    appendLine()
    appendLine("    private fun Int.orAbsent(): Int? = takeIf { it != ABSENT }")
    appendLine()
    appendLine("    /** -1 表示原型没有这个组件；0 是真实取值，不参与合并。 */")
    appendLine("    private const val ABSENT: Int = -1")
    appendLine()
    appendLine("    private val DAMAGE: IntArray = intArrayOf(")
    appendIntArray(maxDamage)
    appendLine("    )")
    appendLine()
    appendLine("    private val STACK_SIZE: IntArray = intArrayOf(")
    appendIntArray(maxStackSize)
    appendLine("    )")
    appendLine("}")
}

/** 每行若干项：一项一行会让生成文件膨胀到三千行，审查 diff 时反而更难读。 */
private fun StringBuilder.appendIntArray(values: IntArray) {
    values.toList().chunked(ITEMS_PER_LINE).forEach { row ->
        append("        ")
        append(row.joinToString(", "))
        appendLine(",")
    }
}

private fun abort(message: String): Nothing {
    System.err.println("error=$message")
    exitProcess(1)
}
