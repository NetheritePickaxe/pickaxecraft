package org.pickaxe.protocol.internal

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * 把协议库的文本组件渲染成纯文本。
 *
 * 协议库自己生成的断开原因用的是翻译键（`disconnect.endOfStream` 之类），
 * 而引擎内的翻译表不在客户端类路径上，直接序列化只会得到键名。这类键由本层
 * 直接给出可读文本；服务端下发的字面文本按原样输出。
 */
internal object ComponentText {

    private val plain = PlainTextComponentSerializer.plainText()

    private val libraryKeys: Map<String, String> = mapOf(
        "disconnect.endOfStream" to "连接已被对端关闭",
        "disconnect.timeout" to "读取超时",
        "disconnect.transfer" to "服务端要求转移连接",
        "multiplayer.status.finished" to "状态查询完成",
    )

    fun render(component: Component): String {
        if (component is TranslatableComponent) {
            libraryKeys[component.key()]?.let { return it }
        }
        return plain.serialize(component).trim().ifEmpty { "（服务端未给出原因）" }
    }
}
