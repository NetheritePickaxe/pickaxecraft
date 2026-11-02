package org.pickaxe.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 锁定值即外部契约：M1 的握手与 M2 的联调都依赖这两个数字。
 * 改动这里等于切换协议版本，必须同步替换 schema 目录并在 SOURCE.md 记录来源。
 */
class ProtocolVersionTest {

    @Test
    fun `锁定版本与 schema 目录一致`() {
        assertEquals("26.2", ProtocolVersion.MINECRAFT_VERSION)
        assertEquals(776, ProtocolVersion.PROTOCOL_VERSION)
    }
}
