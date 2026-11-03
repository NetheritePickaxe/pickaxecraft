package org.pickaxe.protocol

import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 版本常量是外部契约：写错会让客户端用错误的包 ID 与服务端对话，
 * 症状是“能连上但立刻断开”，非常难查。这里直接和协议库对账。
 */
class ProtocolVersionTest {

    @Test
    fun `锁定版本与 MCProtocolLib 一致`() {
        assertEquals(ProtocolVersion.MINECRAFT_VERSION, MinecraftCodec.CODEC.minecraftVersion)
        assertEquals(ProtocolVersion.PROTOCOL_VERSION, MinecraftCodec.CODEC.protocolVersion)
    }

    @Test
    fun `内置注册表数据可加载`() {
        // 配置阶段的服务端注册表校验依赖这份数据；jar 里缺资源时构建期就该暴露，
        // 而不是等到玩家卡在 configuration 阶段。
        val codec = MinecraftProtocol.loadNetworkCodec()
        assertTrue(codec.isNotEmpty(), "networkCodec.nbt 应包含注册表数据")
    }
}
