package org.pickaxe.testing.minestom

import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession
import org.geysermc.mcprotocollib.protocol.ClientListener
import org.geysermc.mcprotocollib.protocol.MinecraftConstants
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.ProtocolState
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo
import org.geysermc.mcprotocollib.protocol.data.status.handler.ServerInfoHandler
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket
import org.pickaxe.protocol.ProtocolVersion
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用真实客户端把 harness 的入站链路打通：状态查询与离线登录。
 *
 * 这里刻意直接驱动 MCProtocolLib：client-core 尚未成型，此用例的价值是守住
 * 「依赖组合可用」这件事——Netty 模块裁剪、协议库版本、Minestom 版本三者一旦错配，
 * 编译仍然通过，只有这种真实握手会失败。
 *
 * 待 client-core 提供自有 API 后，本用例应改为调用自有客户端。
 */
class ClientInteropTest {

    @Test
    fun `客户端可查询状态并完成离线登录`() {
        // 端口 0 由系统分配，避免与本机 25565 冲突。
        val port = ServerSocket(0).use { it.localPort }
        startHarness(port)
        try {
            assertStatusQuery(port)
            assertLoginReachesGame(port)
        } finally {
            stopHarness()
        }
    }

    private fun assertStatusQuery(port: Int) {
        val protocol = MinecraftProtocol()
        // 默认监听器会按 LOGIN 意图发握手；状态查询必须自己装配 STATUS 意图。
        protocol.setUseDefaultListeners(false)

        val session = newSession(port, protocol)
        session.addListener(ClientListener(HandshakeIntent.STATUS))

        var status: ServerStatusInfo? = null
        val received = CountDownLatch(1)
        session.setFlag(
            MinecraftConstants.SERVER_INFO_HANDLER_KEY,
            ServerInfoHandler { _, info ->
                status = info
                received.countDown()
            },
        )

        session.connect()
        assertTrue(received.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "状态查询超时")

        val info = assertNotNull(status, "应收到服务端状态")
        val version = assertNotNull(info.versionInfo, "状态响应应包含版本信息")
        assertEquals(ProtocolVersion.PROTOCOL_VERSION, version.protocolVersion, "服务端协议号应与锁定值一致")
        assertEquals(ProtocolVersion.MINECRAFT_VERSION, version.versionName, "服务端版本名应与锁定值一致")

        session.disconnect("interop done")
    }

    private fun assertLoginReachesGame(port: Int) {
        // 只给用户名即离线登录，与 harness 的 Auth.Offline() 对应。
        val protocol = MinecraftProtocol("InteropBot")
        val session = newSession(port, protocol)

        val registryPackets = AtomicInteger()
        val finished = CountDownLatch(1)
        var stateAtFinish: ProtocolState? = null

        session.addListener(
            object : SessionAdapter() {
                override fun packetReceived(session: Session, packet: Packet) {
                    if (packet is ClientboundRegistryDataPacket) {
                        registryPackets.incrementAndGet()
                    }
                }

                // 本监听器注册得比库自带的 ClientListener 早，收包回调会先于它执行，
                // 因此在 packetReceived 里读状态只会看到 CONFIGURATION。
                // 收尾确认包发出时，库已把入站状态切到 play，这里才是稳定的观察点。
                override fun packetSent(session: Session, packet: Packet) {
                    if (packet is ServerboundFinishConfigurationPacket) {
                        stateAtFinish = protocol.inboundState
                        finished.countDown()
                    }
                }
            },
        )

        session.connect()
        assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "登录流程超时")

        assertEquals(ProtocolState.GAME, stateAtFinish, "配置阶段结束后应进入 play 状态")
        assertTrue(registryPackets.get() > 0, "配置阶段应收到服务端注册表数据")
        assertTrue(session.isConnected, "进入 play 后连接应保持")

        session.disconnect("interop done")
    }

    private fun newSession(port: Int, protocol: MinecraftProtocol): ClientNetworkSession =
        ClientNetworkSession(
            InetSocketAddress(DEFAULT_HOST, port),
            protocol,
            // 库的包处理线程模型由调用方提供；测试里就地执行，避免额外的线程与收尾竞态。
            Executor { it.run() },
            null,
            null,
        )

    private companion object {
        /** 真实握手涉及服务端注册表下发的十几个包，超时给足但必须有限。 */
        const val TIMEOUT_SECONDS: Long = 20
    }
}
