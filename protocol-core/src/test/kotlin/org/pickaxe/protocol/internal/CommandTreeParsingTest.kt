package org.pickaxe.protocol.internal

import net.kyori.adventure.key.Key
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode as McplCommandNode
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.DoubleProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.EntityProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.IntegerProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket
import org.pickaxe.protocol.command.CommandArgumentProperty
import org.pickaxe.protocol.command.CommandArgumentType
import org.pickaxe.protocol.command.CommandNodeKind
import org.pickaxe.protocol.command.CommandSuggestions
import org.pickaxe.protocol.command.CommandTextMode
import java.util.OptionalInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 命令树与补全回答的转换。
 *
 * 这两个结构都是服务端说了算、本层只做形状转换的东西：转换写错不会报错，
 * 只会让下游看到错的参数类型、错的下标或错位的替换区间，所以逐字段钉住。
 */
class CommandTreeParsingTest {

    @Test
    fun `参数类型表覆盖协议库的全部取值`() {
        val ours = CommandArgumentType.entries.map { it.name }.toSet()
        for (parser in CommandParser.entries) {
            assertTrue(parser.name in ours, "协议库的 ${parser.name} 在本层没有对应项")
        }
        // UNKNOWN 是本层自造的兜底值，协议里不该出现同名项。
        assertTrue(CommandParser.entries.none { it.name == CommandArgumentType.UNKNOWN.name })
    }

    @Test
    fun `节点下标与数组位置一致`() {
        val tree = treeOf(sampleNodes())

        assertEquals(0, tree.rootIndex)
        assertEquals(4, tree.size)
        assertEquals(CommandNodeKind.ROOT, tree.node(0)?.kind)
        assertEquals(listOf(1, 3), tree.node(0)?.children, "根的子节点下标应原样保留")
        assertEquals("say", tree.node(1)?.name)
    }

    @Test
    fun `参数节点带上参数类型与范围`() {
        val tree = treeOf(sampleNodes())

        val argument = tree.node(2)
        assertEquals(CommandNodeKind.ARGUMENT, argument?.kind)
        assertEquals("times", argument?.name)
        assertEquals(CommandArgumentType.INTEGER, argument?.argumentType)
        assertEquals(
            CommandArgumentProperty.IntegerRange(min = 1L, max = 10L),
            argument?.property,
            "上下限应被还原成原始数值",
        )
        assertNull(tree.node(3)?.property, "没有附加属性的参数类型不带属性")
    }

    @Test
    fun `整型范围未设置的一侧还原成不限`() {
        // 协议用 Integer.MIN_VALUE / MAX_VALUE 当“没设置”的哨兵值。
        val node = commandNode(
            type = CommandType.ARGUMENT,
            name = "amount",
            parser = CommandParser.INTEGER,
            properties = IntegerProperties(Int.MIN_VALUE, 64),
        )

        assertEquals(
            CommandArgumentProperty.IntegerRange(min = null, max = 64L),
            argumentPropertyOf(node),
        )
    }

    @Test
    fun `浮点语义按 float 的哨兵值判断`() {
        // Float.MAX_VALUE 不等于 Double.MAX_VALUE：直接套 double 的判据会把上限当成“有值”。
        val node = commandNode(
            type = CommandType.ARGUMENT,
            name = "scale",
            parser = CommandParser.FLOAT,
            properties = org.geysermc.mcprotocollib.protocol.data.game.command.properties.FloatProperties(
                -1.5f,
                Float.MAX_VALUE,
            ),
        )

        assertEquals(
            CommandArgumentProperty.DecimalRange(min = -1.5, max = null),
            argumentPropertyOf(node),
        )
    }

    @Test
    fun `文本模式与实体选择器属性被保留`() {
        val text = commandNode(
            type = CommandType.ARGUMENT,
            name = "message",
            parser = CommandParser.STRING,
            properties = StringProperties.GREEDY_PHRASE,
        )
        assertEquals(
            CommandArgumentProperty.Text(CommandTextMode.GREEDY_PHRASE),
            argumentPropertyOf(text),
        )

        val entity = commandNode(
            type = CommandType.ARGUMENT,
            name = "target",
            parser = CommandParser.ENTITY,
            properties = EntityProperties(true, false),
        )
        assertEquals(
            CommandArgumentProperty.Entity(singleTarget = true, playersOnly = false),
            argumentPropertyOf(entity),
        )
    }

    @Test
    fun `重定向与补全类型与可执行标记都保留`() {
        val redirect = McplCommandNode(
            CommandType.LITERAL,
            true,
            false,
            intArrayOf(),
            OptionalInt.of(0),
            "run",
            null,
            null,
            null,
        )
        val withSuggestion = McplCommandNode(
            CommandType.ARGUMENT,
            false,
            true,
            intArrayOf(),
            OptionalInt.empty(),
            "target",
            CommandParser.ENTITY,
            null,
            Key.key("minecraft", "ask_server"),
        )
        val tree = treeOf(
            arrayOf(
                McplCommandNode(CommandType.ROOT, false, false, intArrayOf(1, 2), OptionalInt.empty(), null, null, null, null),
                redirect,
                withSuggestion,
            ),
        )

        assertEquals(0, tree.node(1)?.redirect)
        assertEquals("minecraft:ask_server", tree.node(2)?.suggestionType)
        assertTrue(tree.node(2)?.restricted == true, "受限标记应保留")
        assertTrue(tree.node(1)?.executable == true)
        assertEquals(listOf("run"), tree.topLevelLiterals)
    }

    @Test
    fun `字面量路径遇到参数就停`() {
        val tree = treeOf(sampleNodes())

        // 根 → say（字面量）→ times（参数）：参数不产生路径，也不继续向下。
        assertEquals(listOf("say", "silent"), tree.literalPaths())
        assertEquals(listOf("say"), tree.literalPaths(limit = 1), "上限是返回条数，不是遍历深度")
        assertEquals(emptyList<String>(), tree.literalPaths(limit = 0))
    }

    @Test
    fun `缺失的下标不抛异常`() {
        val tree = treeOf(
            arrayOf(
                McplCommandNode(
                    CommandType.ROOT,
                    false,
                    false,
                    intArrayOf(7),
                    OptionalInt.of(9),
                    null,
                    null,
                    null,
                    null,
                ),
            ),
        )

        assertEquals(emptyList(), tree.childrenOf(0))
        assertNull(tree.redirectOf(0))
        assertNull(tree.node(7))
    }

    @Test
    fun `补全回答按区间替换原文`() {
        val suggestions = commandSuggestions(
            ClientboundCommandSuggestionsPacket(
                7,
                1,
                6,
                arrayOf("gamemode", "give"),
                arrayOf(null, null),
            ),
        )

        assertEquals(7, suggestions.transactionId)
        assertEquals("gamemode", suggestions.firstMatch)
        assertEquals("/gamemode", suggestions.applyTo("/gameme"), "区间是相对原文的字符下标")
        // 同一份回答套到长度不同的原文上时，替换的是服务端指定的那一段，其余原样保留。
        assertEquals("/gamemodede", suggestions.applyTo("/gamemode"))
    }

    @Test
    fun `无法应用时返回 null 而不是猜`() {
        val none = commandSuggestions(ClientboundCommandSuggestionsPacket(1, 0, 0, arrayOf(), arrayOf()))
        assertNull(none.applyTo("/x"), "没有匹配项时无可应用")

        val outOfRange = commandSuggestions(ClientboundCommandSuggestionsPacket(2, 99, 1, arrayOf("x"), arrayOf(null)))
        assertNull(outOfRange.applyTo("/x"), "区间落在文本之外时不修正")
    }

    @Test
    fun `提示文本被渲染成纯文本`() {
        val suggestions = commandSuggestions(
            ClientboundCommandSuggestionsPacket(
                3,
                0,
                2,
                arrayOf("red"),
                arrayOf(net.kyori.adventure.text.Component.text("红色染料")),
            ),
        )

        assertEquals(listOf("红色染料"), suggestions.tooltips)
    }

    @Test
    fun `提示数量与匹配项不一致时拒绝构造`() {
        assertFailsWith<IllegalArgumentException> {
            CommandSuggestions(
                transactionId = 1,
                start = 0,
                length = 1,
                matches = listOf("a", "b"),
                tooltips = listOf(null),
            )
        }
    }

    /** 根 → { say(times) , silent } 与一个带浮点范围的无名参数，覆盖三种节点类型。 */
    private fun sampleNodes(): Array<McplCommandNode> = arrayOf(
        McplCommandNode(
            CommandType.ROOT,
            false,
            false,
            intArrayOf(1, 3),
            OptionalInt.empty(),
            null,
            null,
            null,
            null,
        ),
        McplCommandNode(
            CommandType.LITERAL,
            true,
            false,
            intArrayOf(2),
            OptionalInt.empty(),
            "say",
            null,
            null,
            null,
        ),
        McplCommandNode(
            CommandType.ARGUMENT,
            false,
            false,
            intArrayOf(),
            OptionalInt.empty(),
            "times",
            CommandParser.INTEGER,
            IntegerProperties(1, 10),
            null,
        ),
        McplCommandNode(
            CommandType.LITERAL,
            true,
            false,
            intArrayOf(),
            OptionalInt.empty(),
            "silent",
            null,
            null,
            null,
        ),
    )

    private fun treeOf(nodes: Array<McplCommandNode>) = commandTree(ClientboundCommandsPacket(nodes, 0))

    private fun commandNode(
        type: CommandType,
        name: String?,
        parser: CommandParser?,
        properties: org.geysermc.mcprotocollib.protocol.data.game.command.properties.CommandProperties?,
    ): McplCommandNode = McplCommandNode(
        type,
        false,
        false,
        intArrayOf(),
        OptionalInt.empty(),
        name,
        parser,
        properties,
        null,
    )

    private fun argumentPropertyOf(node: McplCommandNode) =
        commandTree(ClientboundCommandsPacket(arrayOf(ROOT, node), 0)).node(1)?.property

    private companion object {
        val ROOT = McplCommandNode(
            CommandType.ROOT,
            false,
            false,
            intArrayOf(1),
            OptionalInt.empty(),
            null,
            null,
            null,
            null,
        )
    }
}
