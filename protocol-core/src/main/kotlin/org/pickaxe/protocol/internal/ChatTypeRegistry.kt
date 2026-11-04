package org.pickaxe.protocol.internal

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.geysermc.mcprotocollib.protocol.data.game.Holder
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry
import org.geysermc.mcprotocollib.protocol.data.game.chat.ChatType
import org.geysermc.mcprotocollib.protocol.data.game.chat.ChatTypeDecoration
import org.pickaxe.protocol.chat.ChatFormat
import org.pickaxe.protocol.chat.ChatParameter

/**
 * `minecraft:chat_type` 注册表：消息格式模板。
 *
 * 聊天包不传“拼好的整行”，只传一个格式的引用（`Holder`）。普通聊天、表情、私聊的
 * 差别全在格式里，所以要先有一张下标 → 格式的表，才能说清楚一条消息该怎么显示。
 *
 * 引用有两种形态：下标（注册表在配置阶段下发）与内联定义（包内直接带完整格式）。
 * 内联不需要注册表，但真服务端几乎只用下标。
 */
internal object ChatTypeRegistry {

    const val REGISTRY_ID: String = "minecraft:chat_type"

    /** 注册表条目顺序即包里的下标，不能重排或过滤。 */
    fun parse(entries: List<RegistryEntry>): List<ChatFormat> = entries.mapIndexed { index, entry ->
        val id = entry.id.asString()
        val data = entry.data
            ?: throw IllegalStateException("chat_type 第 $index 项 $id 没有 NBT 数据")
        if (!data.containsKey("chat", NbtType.COMPOUND)) {
            throw IllegalStateException("chat_type 条目 $id 缺少 chat 字段")
        }
        data.toFormat(id)
    }

    /**
     * 把消息包里的格式引用解析成 [ChatFormat]。
     *
     * 查不到时返回 null 而不是失败：聊天格式只影响排版，一条消息显示得朴素些
     * 远好过因此断开会话。真实原因是注册表可能没下发（服务端认为客户端内置了
     * 原版数据），这属于可以降级的情形。
     */
    fun formatOf(holder: Holder<ChatType>, registry: List<ChatFormat>): ChatFormat? =
        if (holder.isCustom) holder.custom().chat().toFormat() else registry.getOrNull(holder.id())

    private fun NbtMap.toFormat(id: String): ChatFormat {
        val decoration = getCompound("chat")
        val key = decoration.getString("translation_key", "")
        if (key.isEmpty()) {
            throw IllegalStateException("chat_type 条目 $id 的 chat 缺少 translation_key")
        }
        // parameters 缺省是合法的（模板可以没有占位符），按空列表处理。
        val parameters = decoration.getList("parameters", NbtType.STRING, emptyList())
            .mapNotNull(::parameterOf)
        return ChatFormat(key, parameters)
    }

    private fun ChatTypeDecoration.toFormat(): ChatFormat = ChatFormat(
        key = translationKey(),
        parameters = parameters().mapNotNull { parameterOf(it.name) },
    )

    /**
     * 未知占位符直接跳过：参数名是服务端数据，原版之外可能有新名字。
     * 保留已认识的部分，比整条格式作废更有用。
     */
    private fun parameterOf(name: String): ChatParameter? = when (name.lowercase()) {
        "content" -> ChatParameter.CONTENT
        "sender" -> ChatParameter.SENDER
        "target" -> ChatParameter.TARGET
        else -> null
    }
}
