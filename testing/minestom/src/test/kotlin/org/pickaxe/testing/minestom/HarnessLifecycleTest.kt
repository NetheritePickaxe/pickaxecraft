package org.pickaxe.testing.minestom

import net.minestom.server.MinecraftServer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessLifecycleTest {

    @Test
    fun `服务端可启动并显式停止`() {
        // 端口 0 由操作系统分配，避免与本机 25565 冲突导致测试随机失败。
        startHarness(port = 0)
        assertTrue(MinecraftServer.isStarted(), "启动后应处于运行状态")

        stopHarness()
        assertFalse(MinecraftServer.isStarted(), "停止后不应再处于运行状态")
    }
}
