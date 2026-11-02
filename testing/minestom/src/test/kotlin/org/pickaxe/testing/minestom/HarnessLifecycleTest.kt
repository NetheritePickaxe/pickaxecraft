package org.pickaxe.testing.minestom

import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.Block
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessLifecycleTest {

    /**
     * Minestom 是进程级单例，本类只能有一个用例：一个 JVM 内无法二次初始化。
     * 因此启动、区块生成、停止、端口释放一次性验证。
     */
    @Test
    fun `服务端可启动生成区块并释放端口`() {
        // 端口 0 由操作系统分配，避免与本机 25565 冲突导致测试随机失败；
        // 但要验证端口释放，必须先拿到确定的端口号。
        val port = ServerSocket(0).use { it.localPort }

        val instance = startHarness(port = port)
        assertTrue(MinecraftServer.isStarted(), "启动后应处于运行状态")

        // 强制生成出生区块：这一步才真正执行 Generator，
        // 能在联调前暴露方块键写错、生成器未接线之类只在玩家进服时才出现的问题。
        // 区块读取必须持读锁，否则 Minestom 会抛 “Not holding read-lock”。
        val chunk = instance.loadChunk(0, 0).join()
        chunk.lockReadLock()
        try {
            assertEquals(Block.fromKey("stone"), chunk.getBlock(0, 10, 0), "出生平台应为石头")
        } finally {
            chunk.unlockReadLock()
        }

        stopHarness()
        assertFalse(MinecraftServer.isStarted(), "停止后不应再处于运行状态")

        // 端口能重新连上说明监听没有释放，后续联调会随机失败。
        assertFailsWith<ConnectException>("停止后不应再接受连接") {
            Socket().use { it.connect(InetSocketAddress(DEFAULT_HOST, port), 1000) }
        }
    }
}
