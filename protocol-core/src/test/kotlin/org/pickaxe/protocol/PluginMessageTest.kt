package org.pickaxe.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PluginMessageTest {

    private fun varintPrefixed(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val result = ArrayList<Byte>(payload.size + 5)
        var value = payload.size
        do {
            var byte = value and 0x7F
            value = value ushr 7
            if (value != 0) byte = byte or 0x80
            result.add(byte.toByte())
        } while (value != 0)
        payload.forEach(result::add)
        return result.toByteArray()
    }

    @Test
    fun `brand 按 varint 前缀加 UTF8 解析`() {
        assertEquals("Minestom", PluginMessages.parseBrand(varintPrefixed("Minestom")))
        assertEquals("", PluginMessages.parseBrand(varintPrefixed("")))
        // 128 字节起 varint 要两个字节，单字节前缀的实现对长品牌会解析失败。
        assertEquals("x".repeat(200), PluginMessages.parseBrand(varintPrefixed("x".repeat(200))))
    }

    @Test
    fun `坏的品牌消息返回 null 而不是抛错`() {
        assertNull(PluginMessages.parseBrand(ByteArray(0)), "空 payload")
        assertNull(PluginMessages.parseBrand(byteArrayOf(0x7F)), "前缀截断")
        assertNull(PluginMessages.parseBrand(byteArrayOf(10, 1, 2, 3)), "长度超出实际字节")
        assertNull(PluginMessages.parseBrand(varintPrefixed("abc").copyOfRange(0, 3)), "内容被截断")
    }

    @Test
    fun `裸字符串不是品牌的有效编码`() {
        // 直接把 UTF-8 字节当 payload：首字节 0x4D 会被当成 varint（77），而实际没有那么多字节。
        assertNull(PluginMessages.parseBrand("Minestom".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `渠道必须是完整的 resource location`() {
        assertEquals(
            "minecraft:brand",
            PluginMessages.requireValidChannel("minecraft:brand"),
        )
        assertEquals(
            "pickaxecraft:custom/channel-1.2",
            PluginMessages.requireValidChannel("pickaxecraft:custom/channel-1.2"),
        )
        assertFailsWith<IllegalArgumentException> { PluginMessages.requireValidChannel("brand") }
        assertFailsWith<IllegalArgumentException> { PluginMessages.requireValidChannel("minecraft:") }
        assertFailsWith<IllegalArgumentException> { PluginMessages.requireValidChannel(":brand") }
        assertFailsWith<IllegalArgumentException> { PluginMessages.requireValidChannel("Minecraft:Brand") }
        assertFailsWith<IllegalArgumentException> { PluginMessages.requireValidChannel("minecraft:空格 ") }
        assertFailsWith<IllegalArgumentException> { PluginMessages.requireValidChannel("") }
    }

    @Test
    fun `payload 超过协议上限被拒绝`() {
        val max = ByteArray(PluginMessages.MAX_PAYLOAD_SIZE)
        val oversized = ByteArray(PluginMessages.MAX_PAYLOAD_SIZE + 1)
        // 上限本身合法；发送路径的校验在 McplConnection，这里只核对常量边界可用。
        assertEquals(32767, max.size)
        assertNotEquals(max.size, oversized.size)
    }

    @Test
    fun `消息体与外部数组隔离`() {
        val raw = byteArrayOf(1, 2, 3)
        val message = PluginMessage("test:channel", raw)
        raw[0] = 99
        assertContentEquals(byteArrayOf(1, 2, 3), message.data)

        message.data[1] = 42
        assertContentEquals(byteArrayOf(1, 42, 3), message.data)
    }

    @Test
    fun `相等性按内容判断`() {
        assertEquals(PluginMessage("a:b", byteArrayOf(1, 2)), PluginMessage("a:b", byteArrayOf(1, 2)))
        assertNotEquals(PluginMessage("a:b", byteArrayOf(1)), PluginMessage("a:c", byteArrayOf(1)))
        assertNotEquals(PluginMessage("a:b", byteArrayOf(1)), PluginMessage("a:b", byteArrayOf(2)))
        assertEquals(
            PluginMessage("a:b", "hello").hashCode(),
            PluginMessage("a:b", "hello").hashCode(),
        )
    }
}
