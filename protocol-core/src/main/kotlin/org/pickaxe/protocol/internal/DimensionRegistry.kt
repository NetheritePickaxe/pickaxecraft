package org.pickaxe.protocol.internal

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry

/**
 * 维度的垂直范围，来自配置阶段 `minecraft:dimension_type` 注册表。
 *
 * 区块包里**没有** section 数量：它等于 `height / 16`，必须先知道当前维度才能解码。
 * 这也是解区块前必须先处理注册表的原因。
 */
internal data class DimensionShape(
    val id: String,
    val minY: Int,
    val height: Int,
) {
    val sectionCount: Int get() = height / SECTION_HEIGHT
}

internal object DimensionRegistry {

    const val REGISTRY_ID: String = "minecraft:dimension_type"

    /** 注册表条目顺序即 `ClientboundLoginPacket` 里的维度 id，不能重排或过滤。 */
    fun parse(entries: List<RegistryEntry>): List<DimensionShape> = entries.mapIndexed { index, entry ->
        val id = entry.id.asString()
        val data = entry.data
            ?: throw IllegalStateException("dimension_type 第 $index 项 $id 没有 NBT 数据")

        val shape = DimensionShape(
            id = id,
            minY = data.requireInt("min_y", id),
            height = data.requireInt("height", id),
        )
        check(shape.minY % SECTION_HEIGHT == 0) {
            "维度 $id 的 min_y=${shape.minY} 不是 $SECTION_HEIGHT 的整数倍"
        }
        check(shape.height > 0 && shape.height % SECTION_HEIGHT == 0) {
            "维度 $id 的 height=${shape.height} 必须是 $SECTION_HEIGHT 的正整数倍"
        }
        shape
    }

    private fun NbtMap.requireInt(key: String, dimensionId: String): Int {
        check(containsKey(key, NbtType.INT)) { "维度类型 $dimensionId 缺少整数字段 $key" }
        return getInt(key)
    }
}

private const val SECTION_HEIGHT: Int = 16
