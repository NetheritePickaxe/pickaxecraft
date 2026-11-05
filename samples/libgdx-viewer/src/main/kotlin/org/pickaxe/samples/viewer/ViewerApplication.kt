package org.pickaxe.samples.viewer

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx

/** 演示网格的边长（格）。16 与一个区块 section 的边长一致。 */
internal const val DEMO_SIZE: Int = 16

/**
 * 最小消费示例：在 libGDX 的生命周期里构建一次区块网格，打印统计后退出。
 *
 * 用 headless 后端：这一步不需要 GL 上下文，也就不会要求 CI 有显示器或显卡。
 * 真正上屏的那一半（`Mesh` / `ShaderProgram` / 相机）留给下游仓库——
 * 它会绑定一个具体后端，而那是宿主的选择，不是核心库或示例能替它定的。
 */
internal class ViewerApplication : ApplicationAdapter() {

    override fun create() {
        val size = DEMO_SIZE
        // 地形实现在 DemoTerrain 里，测试用同一份：面数才有据可查。
        val started = System.nanoTime()
        val mesh = ChunkMeshBuilder.build(demoTerrain(size), size, size, size)
        val elapsedMicros = (System.nanoTime() - started) / 1_000

        println(
            "event=sample.mesh size=${size}^3 quads=${mesh.quadCount} " +
                "vertices=${mesh.vertexCount} bytes=${mesh.byteSize} micros=$elapsedMicros",
        )
        // 顶点不能有 NaN：解码或网格化的错位常常先表现为坐标变成 NaN，
        // 到了 GL 里才变成“什么都没画出来”，那时已经很难回溯。
        println("event=sample.mesh.finite=${mesh.positions.all { it.isFinite() }}")
        Gdx.app.exit()
    }
}
