package org.pickaxe.protocol.command

/**
 * 命令树节点的类型。
 *
 * 对应协议里的 `ROOT` / `LITERAL` / `ARGUMENT`：根是整棵树的入口（客户端通常不显示它），
 * 字面量是可执行的确切名字，参数是占位符——只有参数节点带参数类型。
 */
public enum class CommandNodeKind {
    ROOT,
    LITERAL,
    ARGUMENT,
}

/**
 * 命令树里的一个节点。
 *
 * [children] 与 [redirect] 存的是**节点下标**，不是节点本身：协议按扁平数组下发，
 * 下标才是一手信息，需要节点时用 [CommandTree.node] 查。找不到的下标不猜，
 * 直接返回 null——畸形数据不该让查询抛异常。
 *
 * [name] 只在 [kind] 是 [CommandNodeKind.LITERAL] 或 [CommandNodeKind.ARGUMENT] 时存在。
 * [property] 只在参数节点的参数类型带附加属性时存在（数值范围、文本模式、注册表键……）。
 * [suggestionType] 是服务端声明的补全候选类型（`minecraft:ask_server` 之类），
 * 本层不解释它的含义，只原样转交。
 */
public data class CommandNode(
    /** 节点在扁平数组中的下标，与 [CommandNode.children] / [CommandNode.redirect] 同一编号体系。 */
    public val index: Int,
    public val kind: CommandNodeKind,
    public val name: String? = null,
    /** 参数类型；[kind] 不是 [CommandNodeKind.ARGUMENT] 时是 [CommandArgumentType.UNKNOWN]。 */
    public val argumentType: CommandArgumentType = CommandArgumentType.UNKNOWN,
    /** 参数类型自带的属性，没有附加属性时为 null。 */
    public val property: CommandArgumentProperty? = null,
    /** 子节点下标，按服务端下发顺序。 */
    public val children: List<Int> = emptyList(),
    /** 重定向目标下标（`execute run ...` 这类共享子树）；没有重定向时为 null。 */
    public val redirect: Int? = null,
    /** 这个节点本身可以执行（不带更多参数就能提交）。 */
    public val executable: Boolean = false,
    /** 需要权限才可见/可用的受限节点。 */
    public val restricted: Boolean = false,
    /** 服务端声明的补全类型键名，没有声明时为 null。 */
    public val suggestionType: String? = null,
)

/**
 * 服务端下发的完整命令树。
 *
 * 这是**版本相关的只读快照**：节点数量、参数类型与补全声明都由服务端决定，
 * 权限变化时服务端可能整棵重发（见 `ClientEvent.CommandTreeReceived`）。
 *
 * 本类只做导航，不做命令解析：客户端要执行命令就把原文交给
 * `Session.sendCommand`，是否合法由服务端判断。
 */
public class CommandTree(
    nodes: List<CommandNode>,
    /** 根节点下标。通常为 0，但协议把它单独下发，不能假设。 */
    public val rootIndex: Int,
) {
    /** 全部节点，下标即 [CommandNode.index]。 */
    public val nodes: List<CommandNode> = nodes.toList()

    private val byIndex: Map<Int, CommandNode> = this.nodes.associateBy { it.index }

    /** 节点总数，含根。 */
    public val size: Int get() = this.nodes.size

    /** 根节点；下标不存在时抛 [IllegalStateException]（树本身已损坏）。 */
    public val root: CommandNode
        get() = node(rootIndex) ?: throw IllegalStateException("命令树缺少根节点 $rootIndex")

    /** 按下标取节点；下标不存在时返回 null。 */
    public fun node(index: Int): CommandNode? = byIndex[index]

    /** 某个节点的子节点，跳过下标不存在的项。 */
    public fun childrenOf(index: Int): List<CommandNode> =
        byIndex[index]?.children?.mapNotNull(::node).orEmpty()

    /** 某个节点的重定向目标；没有重定向或目标不存在时返回 null。 */
    public fun redirectOf(index: Int): CommandNode? = byIndex[index]?.redirect?.let(::node)

    /**
     * 顶层字面量命令名，例如 `gamemode`、`pickaxe`。
     *
     * 参数节点不会出现在这里——根下面直接挂参数（少见但合法）时它没有可显示的名字。
     */
    public val topLevelLiterals: List<String>
        get() = childrenOf(rootIndex).mapNotNull { node ->
            node.name?.takeIf { node.kind == CommandNodeKind.LITERAL }
        }

    /**
     * 只由字面量组成的路径，用于展示与离线提示；参数节点到此为止。
     *
     * 参数节点不产生路径，也不继续向下——它的子节点（`execute as <目标> at <目标>` 这种）
     * 只有知道参数值之后才有意义，列出来只是噪音。因此这里的长度远小于命令树的真实规模。
     *
     * [limit] 是**返回条数**上限：大服的命令树可能有几百条路径，全部展开没有意义。
     * 达到上限时提前返回，因此结果是前缀而不是全集——需要精确内容时请按节点自行遍历。
     */
    public fun literalPaths(limit: Int = 256): List<String> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<String>(minOf(limit, size))
        val stack = ArrayDeque<Pair<CommandNode, String>>()
        // 逆序压栈，保证弹出的顺序与服务端下发顺序一致。
        childrenOf(rootIndex).asReversed().forEach { stack.addLast(it to (it.name ?: "")) }
        while (stack.isNotEmpty() && out.size < limit) {
            val (node, path) = stack.removeLast()
            if (node.kind != CommandNodeKind.LITERAL) continue
            out.add(path)
            childrenOf(node.index).asReversed().forEach { child ->
                if (child.kind == CommandNodeKind.LITERAL) {
                    stack.addLast(child to "$path ${child.name}")
                }
            }
        }
        return out
    }

    override fun toString(): String = "CommandTree(nodes=$size, literals=${topLevelLiterals.size})"
}
