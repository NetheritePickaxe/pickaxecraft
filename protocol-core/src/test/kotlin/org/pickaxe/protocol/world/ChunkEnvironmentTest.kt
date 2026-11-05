package org.pickaxe.protocol.world

import org.pickaxe.protocol.internal.ChunkDecoder
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 区块的“其余三部分”：光照、高度图、方块实体。
 *
 * 这三样都不由本库的 section 解码器处理（协议库已经解析好），但换算规则仍然容易写错：
 * 半字节顺序、光照层的 ±1 偏移、高度图的 9 位打包、方块实体的坐标是层内还是世界。
 * 用例直接构造数据，逐条钉住这些换算。
 */
class ChunkEnvironmentTest {

    @Test
    fun `光照按半字节解包且偶数格取低四位`() {
        val layer = ByteArray(ChunkLight.LAYER_BYTES)
        // 第 0 格是低半字节（0xA），第 1 格是高半字节（0x3）：写反了整片光照都会错位。
        layer[0] = 0x3A.toByte()
        val light = ChunkLight.of(3).withPatch(LightPatch(sky = mapOf(1 to layer), block = emptyMap()))

        assertEquals(0xA, light.skyAt(1, 0, 0, 0))
        assertEquals(0x3, light.skyAt(1, 1, 0, 0))
        assertNull(light.skyAt(0, 0, 0, 0), "没收到数据的层应当是未知，而不是 0")
        assertNull(light.blockAt(1, 0, 0, 0))
    }

    @Test
    fun `全黑的层返回 0 而不是未知`() {
        val light = ChunkLight.of(3).withPatch(
            LightPatch(sky = mapOf(2 to ChunkLight.dark()), block = emptyMap()),
        )

        assertEquals(0, light.skyAt(2, 5, 6, 7))
    }

    @Test
    fun `光照更新只替换出现的层`() {
        val first = ByteArray(ChunkLight.LAYER_BYTES) { 0x11 }
        val second = ByteArray(ChunkLight.LAYER_BYTES) { 0x22 }
        val light = ChunkLight.of(3)
            .withPatch(LightPatch(sky = mapOf(1 to first), block = emptyMap()))
            .withPatch(LightPatch(sky = mapOf(2 to second), block = emptyMap()))

        assertEquals(0x1, light.skyAt(1, 0, 0, 0), "没被本次更新提到的层要保持原值")
        assertEquals(0x2, light.skyAt(2, 0, 0, 0))
        assertSame(light, light.withPatch(LightPatch(emptyMap(), emptyMap())), "空更新应当返回自己")
    }

    @Test
    fun `光照层号或长度不合法时失败`() {
        val light = ChunkLight.of(3)

        assertFailsWith<IllegalArgumentException> {
            light.withPatch(LightPatch(sky = mapOf(9 to ChunkLight.dark()), block = emptyMap()))
        }
        assertFailsWith<IllegalArgumentException> {
            light.withPatch(LightPatch(sky = mapOf(1 to ByteArray(16)), block = emptyMap()))
        }
    }

    @Test
    fun `世界 Y 换算到光照层时向上多一层`() {
        val layer = ByteArray(ChunkLight.LAYER_BYTES) { 0x0F }
        val column = column(light = ChunkLight.of(3).withPatch(LightPatch(mapOf(1 to layer), emptyMap())))

        // minY = -64、一个 section：光照层 1 覆盖 [-64, -48)，层 0 是地表之下那一层。
        assertEquals(15, column.skyLightAt(0, -64, 0))
        assertEquals(15, column.skyLightAt(0, -49, 0))
        assertNull(column.skyLightAt(0, -48, 0), "层 2 没有数据")
        assertNull(column.skyLightAt(0, -80, 0), "层 0 覆盖 [minY-16, minY)，本次没有下发数据")
        assertNull(column.skyLightAt(0, -96, 0), "再往下就没有光照层了")
    }

    @Test
    fun `高度图按 9 位打包解出世界 Y`() {
        val packed = LongArray(37)
        packed.put(index = 0, value = 104) // (x=0, z=0) → -64 + 104
        packed.put(index = 16, value = 105) // (x=0, z=1)
        packed.put(index = 255, value = 0) // (x=15, z=15)

        val column = column(heightmaps = mapOf(HeightmapType.MOTION_BLOCKING to packed))

        assertEquals(40, column.heightAt(HeightmapType.MOTION_BLOCKING, 0, 0))
        assertEquals(41, column.heightAt(HeightmapType.MOTION_BLOCKING, 0, 1))
        assertEquals(-64, column.heightAt(HeightmapType.MOTION_BLOCKING, 15, 15))
        assertNull(column.heightAt(HeightmapType.WORLD_SURFACE, 0, 0), "没有下发的表返回 null")
    }

    @Test
    fun `高度图长度不对时拒绝整列`() {
        assertFailsWith<IllegalArgumentException> {
            column(heightmaps = mapOf(HeightmapType.WORLD_SURFACE to LongArray(36)))
        }
    }

    @Test
    fun `方块实体随方块变更清除并可由新包放回`() {
        val chest = BlockEntity(
            position = BlockPosition(1, -60, 2),
            type = "minecraft:chest",
            data = NbtValue.Compound(mapOf("id" to NbtValue.Text("minecraft:chest"))),
        )
        val column = column(blockEntities = listOf(chest))

        assertEquals(chest, column.blockEntity(1, -60, 2))
        assertEquals(chest, column.blockEntity(BlockPosition(1, -60, 2)))
        assertNull(column.blockEntity(1, -60, 3))

        // 服务端换掉方块时不发“删除方块实体”的包：那条 block_update 就说明旧实体不存在了。
        val replaced = assertNotNull(column.withBlock(1, -60, 2, stateId = 1))
        assertNull(replaced.blockEntity(1, -60, 2))

        val restored = replaced.withBlockEntity(chest)
        assertEquals(chest, restored.blockEntity(1, -60, 2))
    }

    @Test
    fun `同位置的方块实体整条替换`() {
        val first = BlockEntity(BlockPosition(0, -64, 0), "minecraft:sign")
        val second = BlockEntity(BlockPosition(0, -64, 0), "minecraft:sign", NbtValue.Compound(emptyMap()))
        val column = column(blockEntities = listOf(first)).withBlockEntity(second)

        assertEquals(1, column.blockEntities.size)
        assertEquals(second, column.blockEntity(0, -64, 0))
    }

    /** 单 section、全空气的一列，只用来承载环境数据。 */
    private fun column(
        light: ChunkLight = ChunkLight.of(3),
        heightmaps: Map<HeightmapType, LongArray> = emptyMap(),
        blockEntities: List<BlockEntity> = emptyList(),
    ): ChunkColumn = ChunkDecoder.decode(
        x = 0,
        z = 0,
        minY = -64,
        sectionCount = 1,
        data = airSection(),
        heightmaps = heightmaps,
        light = light,
        blockEntities = blockEntities,
    )

    private fun airSection(): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(2) { out.write(0) } // 非空气数
        repeat(2) { out.write(0) } // 流体数
        out.write(0) // 方块：单值调色板
        out.write(0) // 值为 0
        out.write(0) // 生物群系：单值调色板
        out.write(0)
        return out.toByteArray()
    }

    private fun LongArray.put(index: Int, value: Int) {
        val perLong = Long.SIZE_BITS / 9
        val longIndex = index / perLong
        val shift = (index % perLong) * 9
        this[longIndex] = this[longIndex] or (value.toLong() shl shift)
    }
}
