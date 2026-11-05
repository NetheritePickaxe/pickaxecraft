package org.pickaxe.samples.viewer

import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration

/**
 * 示例入口：启动 libGDX 的 headless 应用，构建一次网格后退出。
 *
 * 它存在的意义是证明“下游消费核心库”这条路径是通的：示例只看得见公共 API，
 * 编译类路径里没有 Netty、没有协议库、没有 Minestom（由 `verifyModuleBoundaries` 断言）。
 */
public fun main() {
    val config = HeadlessApplicationConfiguration()
    // 只跑一帧：create() 里就打印并退出，渲染循环不会真的转起来。
    config.updatesPerSecond = 1
    HeadlessApplication(ViewerApplication(), config)
}
