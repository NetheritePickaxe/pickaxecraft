package org.pickaxe.samples.viewer

/**
 * 把实心格子转成带面剔除的四边形网格。
 *
 * 只做**同一份查询内**的邻接判断：相邻列应当由调用方一次传入（见 [blockSolidity] 的越界约定）。
 * 这条约束把“区块边界怎么衔接”留给下游，而不是在示例里假装知道。
 */
public object ChunkMeshBuilder {

    /**
     * @param query 实心查询，坐标是网格局部坐标。
     * @param sizeX / [sizeY] / [sizeZ] 网格覆盖的格子数。
     */
    public fun build(
        query: SolidQuery,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
    ): MeshData {
        require(sizeX > 0 && sizeY > 0 && sizeZ > 0) {
            "网格尺寸必须为正，实际为 ${sizeX}x${sizeY}x${sizeZ}"
        }

        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val indices = ArrayList<Int>()

        for (y in 0 until sizeY) {
            for (z in 0 until sizeZ) {
                for (x in 0 until sizeX) {
                    if (!query.isSolid(x, y, z)) continue
                    for (face in FACES) {
                        // 邻居也是实心 → 这个面看不见，直接不生成。
                        if (query.isSolid(x + face.nx, y + face.ny, z + face.nz)) continue

                        val base = positions.size / 3
                        for (corner in 0 until 4) {
                            positions.add((x + face.vx[corner]).toFloat())
                            positions.add((y + face.vy[corner]).toFloat())
                            positions.add((z + face.vz[corner]).toFloat())
                            normals.add(face.nx.toFloat())
                            normals.add(face.ny.toFloat())
                            normals.add(face.nz.toFloat())
                        }
                        // 两个三角形共用对角线：0-1-2 与 0-2-3，绕序与法线一致（外部看为逆时针）。
                        indices.add(base)
                        indices.add(base + 1)
                        indices.add(base + 2)
                        indices.add(base)
                        indices.add(base + 2)
                        indices.add(base + 3)
                    }
                }
            }
        }

        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }
}

/**
 * 一个面：法线与四个角的单位偏移。
 *
 * 顶点顺序统一为“从外面看逆时针”，这样下游用 GL 默认的 CCW 正面剔除就能直接画。
 */
private class Face(
    val nx: Int,
    val ny: Int,
    val nz: Int,
    val vx: IntArray,
    val vy: IntArray,
    val vz: IntArray,
)

private val FACES: List<Face> = listOf(
    // +X
    Face(1, 0, 0, intArrayOf(1, 1, 1, 1), intArrayOf(0, 1, 1, 0), intArrayOf(0, 0, 1, 1)),
    // -X
    Face(-1, 0, 0, intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, 1, 1), intArrayOf(0, 1, 1, 0)),
    // +Y（俯视看是逆时针：+z 在屏幕下方）
    Face(0, 1, 0, intArrayOf(0, 1, 1, 0), intArrayOf(1, 1, 1, 1), intArrayOf(1, 1, 0, 0)),
    // -Y
    Face(0, -1, 0, intArrayOf(0, 1, 1, 0), intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, 1, 1)),
    // +Z
    Face(0, 0, 1, intArrayOf(1, 0, 0, 1), intArrayOf(0, 0, 1, 1), intArrayOf(1, 1, 1, 1)),
    // -Z
    Face(0, 0, -1, intArrayOf(0, 1, 1, 0), intArrayOf(0, 0, 1, 1), intArrayOf(0, 0, 0, 0)),
)
