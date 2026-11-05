package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode as McplCommandNode
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.CommandProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.DoubleProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.EntityProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.FloatProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.IntegerProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.LongProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.ResourceProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.ScoreHolderProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.TimeProperties
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket
import org.pickaxe.protocol.command.CommandArgumentProperty
import org.pickaxe.protocol.command.CommandArgumentType
import org.pickaxe.protocol.command.CommandNode
import org.pickaxe.protocol.command.CommandNodeKind
import org.pickaxe.protocol.command.CommandSuggestions
import org.pickaxe.protocol.command.CommandTextMode
import org.pickaxe.protocol.command.CommandTree

/**
 * 把协议库的命令树转成本层的只读模型。
 *
 * 节点下标**就是数组位置**：协议本身用下标互相引用，所以这里不重排、不压缩，
 * 否则子节点与重定向会指错地方。名字与参数类型原样保留，不去猜含义。
 */
internal fun commandTree(packet: ClientboundCommandsPacket): CommandTree {
    val nodes: Array<McplCommandNode> = packet.nodes
    val converted: List<CommandNode> = nodes.mapIndexed { index, node: McplCommandNode -> commandNode(index, node) }
    return CommandTree(converted, packet.firstNodeIndex)
}

private fun commandNode(index: Int, node: McplCommandNode): CommandNode {
    val kind = commandNodeKind(node.type)
    val children: IntArray = node.childIndices
    val parser: CommandParser? = node.parser
    return CommandNode(
        index = index,
        kind = kind,
        name = node.name,
        argumentType = if (kind == CommandNodeKind.ARGUMENT && parser != null) argumentType(parser) else CommandArgumentType.UNKNOWN,
        property = if (kind == CommandNodeKind.ARGUMENT && parser != null) {
            argumentProperty(node.properties)
        } else {
            null
        },
        children = children.toList(),
        redirect = if (node.redirectIndex.isPresent) node.redirectIndex.getAsInt() else null,
        executable = node.isExecutable,
        restricted = node.isAllowsRestricted,
        suggestionType = node.suggestionType?.asString(),
    )
}

private fun commandNodeKind(type: CommandType): CommandNodeKind = when (type) {
    CommandType.ROOT -> CommandNodeKind.ROOT
    CommandType.LITERAL -> CommandNodeKind.LITERAL
    CommandType.ARGUMENT -> CommandNodeKind.ARGUMENT
}

/**
 * 按名字而不是序号对齐：协议库新增参数类型时既有值不会整体错位。
 *
 * 名字对不上时给 [CommandArgumentType.UNKNOWN] 而不是抛错——一个不认识的参数类型
 * 不该让整棵树失效，其余节点仍然可用。升级协议库时 `CommandTreeParsingTest` 会先失败。
 */
private fun argumentType(parser: CommandParser): CommandArgumentType =
    CommandArgumentType.entries.firstOrNull { it.name == parser.name } ?: CommandArgumentType.UNKNOWN

private fun argumentProperty(properties: CommandProperties?): CommandArgumentProperty? = when (properties) {
    null -> null
    is IntegerProperties -> CommandArgumentProperty.IntegerRange(
        min = properties.min.takeUnless { it == Int.MIN_VALUE }?.toLong(),
        max = properties.max.takeUnless { it == Int.MAX_VALUE }?.toLong(),
    )
    is LongProperties -> CommandArgumentProperty.IntegerRange(
        min = properties.min.takeUnless { it == Long.MIN_VALUE },
        max = properties.max.takeUnless { it == Long.MAX_VALUE },
    )
    is DoubleProperties -> CommandArgumentProperty.DecimalRange(
        min = properties.min.takeUnless { it == -Double.MAX_VALUE },
        max = properties.max.takeUnless { it == Double.MAX_VALUE },
    )
    is FloatProperties -> CommandArgumentProperty.DecimalRange(
        // 转成 double 再比较：float 的 ±MAX_VALUE 与 double 的哨兵值不同，必须先映射。
        min = properties.min.takeUnless { it == -Float.MAX_VALUE }?.toDouble(),
        max = properties.max.takeUnless { it == Float.MAX_VALUE }?.toDouble(),
    )
    is StringProperties -> CommandArgumentProperty.Text(
        when (properties) {
            StringProperties.SINGLE_WORD -> CommandTextMode.SINGLE_WORD
            StringProperties.QUOTABLE_PHRASE -> CommandTextMode.QUOTABLE_PHRASE
            StringProperties.GREEDY_PHRASE -> CommandTextMode.GREEDY_PHRASE
        },
    )
    is EntityProperties -> CommandArgumentProperty.Entity(
        singleTarget = properties.isSingleTarget,
        playersOnly = properties.isPlayersOnly,
    )
    is ScoreHolderProperties -> CommandArgumentProperty.ScoreHolder(
        allowMultiple = properties.isAllowMultiple,
    )
    is TimeProperties -> CommandArgumentProperty.Time(properties.min)
    is ResourceProperties -> CommandArgumentProperty.Resource(properties.registryKey.asString())
    // 其余参数类型在协议里不带附加字段；出现未知实现时宁可少报，也不猜字段含义。
    else -> null
}

/**
 * 把补全回答转成本层模型。
 *
 * 提示是文本组件，渲染成纯文本：本层不保留样式，补全项在无头客户端里只是一行候选。
 */
internal fun commandSuggestions(packet: ClientboundCommandSuggestionsPacket): CommandSuggestions {
    val matches: Array<String> = packet.matches
    val tooltips = packet.tooltips
    return CommandSuggestions(
        transactionId = packet.transactionId,
        start = packet.start,
        length = packet.length,
        matches = matches.toList(),
        tooltips = tooltips.map { component -> component?.let { ComponentText.text(it) } },
    )
}
