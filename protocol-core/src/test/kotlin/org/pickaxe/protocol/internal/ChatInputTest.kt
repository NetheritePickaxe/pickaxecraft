package org.pickaxe.protocol.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 上行输入校验。
 *
 * 这些边界与协议绑定：长度上限来自服务端解码时的同一个数字，换行是本层额外加的
 * 单行渲染约束。写错任何一条都表现为“消息发出去后服务端断开”，没有别的地方能拦住。
 */
class ChatInputTest {

    @Test
    fun `普通消息原样返回`() {
        assertEquals("你好，世界", ChatInput.message("你好，世界"))
    }

    @Test
    fun `空消息与纯空白被拒绝`() {
        assertFailsWith<IllegalArgumentException> { ChatInput.message("") }
        assertFailsWith<IllegalArgumentException> { ChatInput.message("   ") }
        assertFailsWith<IllegalArgumentException> { ChatInput.message("\t") }
    }

    @Test
    fun `长度上限按字符计算`() {
        val max = "字".repeat(ChatInput.MAX_LENGTH)
        assertEquals(max, ChatInput.message(max), "恰好 256 个字符应当可发")
        assertFailsWith<IllegalArgumentException> { ChatInput.message(max + "字") }
    }

    @Test
    fun `换行被拒绝`() {
        // 协议接受换行，但下游按单行渲染：放过去等于让对端在界面上伪造一行自己没说过的话。
        assertFailsWith<IllegalArgumentException> { ChatInput.message("第一行\n第二行") }
        assertFailsWith<IllegalArgumentException> { ChatInput.message("第一行\r第二行") }
    }

    @Test
    fun `命令的前导斜杠被剥掉`() {
        assertEquals("help", ChatInput.command("/help"))
        assertEquals("help", ChatInput.command("help"), "不带斜杠同样可用")
        assertEquals("tp @s 0 64 0", ChatInput.command("/tp @s 0 64 0"))
    }

    @Test
    fun `只剥一层斜杠`() {
        // 只剥一层：`//help` 是名为 `/help` 的命令，不能悄悄改成 `help`。
        assertEquals("/help", ChatInput.command("//help"))
    }

    @Test
    fun `空命令被拒绝`() {
        assertFailsWith<IllegalArgumentException> { ChatInput.command("") }
        assertFailsWith<IllegalArgumentException> { ChatInput.command("/") }
        assertFailsWith<IllegalArgumentException> { ChatInput.command("   ") }
    }

    @Test
    fun `命令同样受长度与换行限制`() {
        // 长度按剥掉斜杠之后计算：`/` 本身不占协议里的额度。
        val max = "a".repeat(ChatInput.MAX_LENGTH)
        assertEquals(max, ChatInput.command("/$max"))
        assertFailsWith<IllegalArgumentException> { ChatInput.command("/$max" + "a") }
        assertFailsWith<IllegalArgumentException> { ChatInput.command("/say 第一行\n第二行") }
    }
}
