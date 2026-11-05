package org.pickaxe.samples.viewer

/**
 * “这个格子是否挡住视线”的最小输入。
 *
 * 故意不接收 `ChunkColumn`：网格化只需要一个三态查询，绑死区块类型会让下游无法把
 * 多个来源（区块缓存、服务端推送的方块变更、自己的预测地形）拼在同一份网格里。
 */
public fun interface SolidQuery {
    /** 坐标是区块内的局部坐标；越界时由实现决定（见 [blockSolidity] 的说明）。 */
    public fun isSolid(x: Int, y: Int, z: Int): Boolean
}

/**
 * 一次网格构建的产物。
 *
 * 刻意用数组而不是 libGDX 的 `Mesh`：`Mesh` 需要 GL 上下文，创建它就得先有窗口；
 * 数组可以在任何线程、任何后端（包括 CI 的 headless）里产出并断言，
 * 上屏时再 `mesh.setVertices(positions)` 即可。
 */
public class MeshData(
    /** 每顶点 3 个 float，顺序为 x, y, z。 */
    public val positions: FloatArray,
    /** 每顶点 3 个 float，与 [positions] 一一对应。 */
    public val normals: FloatArray,
    /** 每面 6 个下标（两个三角形），顺序为顺时针。 */
    public val indices: IntArray,
) {
    public val vertexCount: Int get() = positions.size / 3

    public val quadCount: Int get() = indices.size / 6

    /** 顶点与索引占用的字节数；用来判断“这一列到底能不能留在内存里”。 */
    public val byteSize: Int get() = positions.size * 4 + normals.size * 4 + indices.size * 4
}
