package org.pickaxe.samples.viewer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 网格构建的算术：面剔除与顶点排布。
 *
 * 这些数字一旦错位不会抛异常，只会画出“少一堵墙”或“法线朝里”的图，肉眼调试代价很高，
 * 所以用能算准的形状直接对账。
 */
class ChunkMeshBuilderTest {

    @Test
    fun `单个实心格产生六个面`() {
        val mesh = ChunkMeshBuilder.build(singleBlock(), 1, 1, 1)

        assertEquals(6, mesh.quadCount)
        assertEquals(24, mesh.vertexCount, "每面 4 个顶点，不做顶点共享")
        assertEquals(6 * 6, mesh.indices.size, "每面两个三角形")
        assertEquals(mesh.vertexCount * 3, mesh.positions.size)
        assertEquals(mesh.vertexCount * 3, mesh.normals.size)
    }

    @Test
    fun `内部面被剔除`() {
        // 3×3×3 实心：表面 6 面 × 9 格 = 54 个四边形；内部接触面一个都不该生成。
        val cube = SolidQuery { x, y, z -> x in 0..2 && y in 0..2 && z in 0..2 }
        val mesh = ChunkMeshBuilder.build(cube, 3, 3, 3)

        assertEquals(54, mesh.quadCount)
        // 若内部面没被剔除，会是 27 格 × 6 面 = 162。
        assertTrue(mesh.quadCount < 162)
    }

    @Test
    fun `全空不产生几何`() {
        val mesh = ChunkMeshBuilder.build(SolidQuery { _, _, _ -> false }, 4, 4, 4)

        assertEquals(0, mesh.quadCount)
        assertEquals(0, mesh.vertexCount)
        assertEquals(0, mesh.indices.size)
        assertEquals(0, mesh.byteSize)
    }

    @Test
    fun `六个方向各出现一次且为单位向量`() {
        val mesh = ChunkMeshBuilder.build(singleBlock(), 1, 1, 1)
        // 每个面 4 个顶点，取每面的第一个顶点读法线。
        val directions = (0 until mesh.vertexCount step 4).map { vertex ->
            val i = vertex * 3
            Triple(mesh.normals[i], mesh.normals[i + 1], mesh.normals[i + 2])
        }.toSet()

        assertEquals(6, directions.size, "六个面应当六个方向")
        assertTrue(
            directions.all { (x, y, z) -> abs(x) + abs(y) + abs(z) == 1f },
            "法线必须是轴向单位向量，实际为 $directions",
        )
        assertEquals(
            setOf(
                Triple(1f, 0f, 0f), Triple(-1f, 0f, 0f),
                Triple(0f, 1f, 0f), Triple(0f, -1f, 0f),
                Triple(0f, 0f, 1f), Triple(0f, 0f, -1f),
            ),
            directions,
        )
    }

    @Test
    fun `顶点落在格子边界上且坐标有限`() {
        val mesh = ChunkMeshBuilder.build(singleBlock(), 1, 1, 1)

        assertTrue(mesh.positions.all { it.isFinite() }, "坐标不能有 NaN 或无穷")
        assertTrue(mesh.positions.all { it == 0f || it == 1f }, "单位立方体的顶点只能是 0 或 1")
        assertTrue(mesh.indices.all { it in 0 until mesh.vertexCount }, "下标不能越界")
    }

    @Test
    fun `示例地形面数可算准`() {
        // 16×16 地面：顶面 256 格，被柱子压住的 4 格不生成 → 252；底面与四周朝外不生成。
        // 柱子 8 格：顶面 4，侧面每层 8、两层 16，底面贴着地面不生成 → 20。
        val mesh = ChunkMeshBuilder.build(demoTerrain(16), 16, 16, 16)

        assertEquals(252 + 20, mesh.quadCount)
        assertEquals((252 + 20) * 4, mesh.vertexCount)
    }

    @Test
    fun `尺寸非法时拒绝构建`() {
        assertFailsWith<IllegalArgumentException> {
            ChunkMeshBuilder.build(singleBlock(), 0, 1, 1)
        }
    }

    private fun singleBlock(): SolidQuery = SolidQuery { x, y, z -> x == 0 && y == 0 && z == 0 }
}
