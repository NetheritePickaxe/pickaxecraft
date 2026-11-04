package org.pickaxe.protocol.internal

import net.kyori.adventure.key.Key
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.geysermc.mcprotocollib.protocol.data.game.Holder
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry
import org.geysermc.mcprotocollib.protocol.data.game.chat.ChatType
import org.geysermc.mcprotocollib.protocol.data.game.chat.ChatTypeDecoration
import org.pickaxe.protocol.chat.ChatParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 聊天格式解析。
 *
 * 这里锁的是**服务端 NBT 的形状**：字段名对不上时不会报错，只会让所有消息失去
 * 格式（回退成朴素显示），那种问题在联调里很难看出来。因此缺字段、少参数都各有用例。
 */
class ChatTypeRegistryTest {

    @Test
    fun `解析翻译键与参数顺序`() {
        val formats = ChatTypeRegistry.parse(listOf(entry("minecraft:chat", "chat.type.text", "sender", "content")))

        assertEquals(1, formats.size)
        assertEquals("chat.type.text", formats[0].key)
        assertEquals(listOf(ChatParameter.SENDER, ChatParameter.CONTENT), formats[0].parameters)
    }

    @Test
    fun `缺少 parameters 视为没有占位符`() {
        val formats = ChatTypeRegistry.parse(listOf(entry("minecraft:raw", "chat.type.announcement")))

        assertEquals("chat.type.announcement", formats[0].key)
        assertEquals(emptyList(), formats[0].parameters)
    }

    @Test
    fun `未知占位符被跳过而不是整条失败`() {
        // 参数名是服务端数据，原版之外可能出现新名字；已认识的部分仍然有用。
        val formats = ChatTypeRegistry.parse(listOf(entry("custom:x", "custom.format", "sender", "future_thing")))

        assertEquals(listOf(ChatParameter.SENDER), formats[0].parameters)
    }

    @Test
    fun `条目没有 NBT 数据时失败`() {
        val failure = assertFailsWith<IllegalStateException> {
            ChatTypeRegistry.parse(listOf(RegistryEntry(Key.key("minecraft", "chat"), null)))
        }

        assertEquals(true, failure.message!!.contains("没有 NBT 数据"))
    }

    @Test
    fun `条目缺少 chat 字段时失败`() {
        val failure = assertFailsWith<IllegalStateException> {
            ChatTypeRegistry.parse(
                listOf(RegistryEntry(Key.key("minecraft", "chat"), NbtMap.builder().putInt("x", 1).build())),
            )
        }

        assertEquals(true, failure.message!!.contains("缺少 chat 字段"))
    }

    @Test
    fun `条目缺少 translation_key 时失败`() {
        val failure = assertFailsWith<IllegalStateException> {
            ChatTypeRegistry.parse(
                listOf(RegistryEntry(Key.key("minecraft", "chat"), NbtMap.builder().putCompound("chat", NbtMap.EMPTY).build())),
            )
        }

        assertEquals(true, failure.message!!.contains("translation_key"))
    }

    @Test
    fun `下标引用按注册表顺序解析`() {
        val formats = ChatTypeRegistry.parse(
            listOf(
                entry("minecraft:chat", "chat.type.text", "sender", "content"),
                entry("minecraft:say_command", "chat.type.announcement", "sender", "content"),
            ),
        )

        assertEquals("chat.type.announcement", ChatTypeRegistry.formatOf(Holder.ofId(1), formats)?.key)
    }

    @Test
    fun `下标越界返回 null 而不是失败`() {
        // 注册表没下发（服务端认为客户端内置原版数据）时会走到这里；聊天不能因此断线。
        assertNull(ChatTypeRegistry.formatOf(Holder.ofId(9), emptyList()))
    }

    @Test
    fun `内联格式不经过注册表`() {
        val decoration = ChatType.ChatTypeDecorationImpl(
            "chat.type.emote",
            listOf(ChatTypeDecoration.Parameter.SENDER, ChatTypeDecoration.Parameter.CONTENT),
            NbtMap.EMPTY,
        )
        val inline = Holder.ofCustom(ChatType(decoration, decoration))

        val format = ChatTypeRegistry.formatOf(inline, emptyList())

        assertEquals("chat.type.emote", format?.key)
        assertEquals(listOf(ChatParameter.SENDER, ChatParameter.CONTENT), format?.parameters)
    }

    private fun entry(id: String, key: String, vararg parameters: String): RegistryEntry {
        val chat = NbtMap.builder()
            .putString("translation_key", key)
            .putList("parameters", NbtType.STRING, parameters.toList())
            .build()
        return RegistryEntry(Key.key(id), NbtMap.builder().putCompound("chat", chat).build())
    }
}
