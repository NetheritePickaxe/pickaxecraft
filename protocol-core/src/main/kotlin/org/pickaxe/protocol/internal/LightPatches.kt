package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData
import org.pickaxe.protocol.world.ChunkLight
import org.pickaxe.protocol.world.LightPatch
import java.util.BitSet

/**
 * 光照包 → 本库的增量。
 *
 * 线上格式是“两张掩码 + 两串数组”：掩码里**第 n 个为 1 的位**对应数组里的第 n 段，
 * 而不是“层号就是数组下标”。漏掉这层换算的表现是整片天空光错位，且看起来像地形问题，
 * 因此这段单独成文件并有用例钉住（`LightPatchesTest`）。
 *
 * 空掩码（`emptySkyYMask` / `emptyBlockYMask`）表示“这一层全黑”，与“本包没提这一层”
 * 是两件事：前者要覆盖缓存里的旧值，后者不能动。两者在这里都表现为一条替换项——
 * 全黑映射到一段共享的全零数组，合并逻辑因此只有一条路径。
 */
internal fun lightPatch(data: LightUpdateData, layers: Int): LightPatch = LightPatch(
    sky = lightLayers(data.skyYMask, data.skyUpdates, data.emptySkyYMask, layers, "天空光"),
    block = lightLayers(data.blockYMask, data.blockUpdates, data.emptyBlockYMask, layers, "方块光"),
)

private fun lightLayers(
    mask: BitSet,
    updates: List<ByteArray>,
    emptyMask: BitSet,
    layers: Int,
    what: String,
): Map<Int, ByteArray> {
    val result = LinkedHashMap<Int, ByteArray>()
    var cursor = 0
    var layer = mask.nextSetBit(0)
    while (layer >= 0) {
        require(cursor < updates.size) {
            "$what 的掩码声明了第 $layer 层，但只带了 ${updates.size} 段数据"
        }
        require(layer < layers) { "$what 的层号 $layer 超出本列 $layers 层" }
        result[layer] = updates[cursor++]
        layer = mask.nextSetBit(layer + 1)
    }
    require(cursor == updates.size) {
        "$what 带了 ${updates.size} 段数据，掩码只声明了 $cursor 层"
    }

    var darkLayer = emptyMask.nextSetBit(0)
    while (darkLayer >= 0) {
        require(darkLayer < layers) { "$what 的空层号 $darkLayer 超出本列 $layers 层" }
        result[darkLayer] = ChunkLight.dark()
        darkLayer = emptyMask.nextSetBit(darkLayer + 1)
    }
    return result
}
