package org.pickaxe.protocol.command

/** 文本参数的分词模式，对应原版 `string` 参数的三种形态。 */
public enum class CommandTextMode {
    /** 单个词，遇到空格结束。 */
    SINGLE_WORD,

    /** 可用引号包裹的短语。 */
    QUOTABLE_PHRASE,

    /** 贪婪：吃掉这一行剩下的全部内容（`/say` 的正文）。 */
    GREEDY_PHRASE,
}

/**
 * 参数类型自带的附加属性。
 *
 * 协议里只有部分参数类型会在树上附带额外字段（数值上下限、文本模式、是否需要实体、
 * 目标注册表……）。这些字段影响**输入是否合法**，与 [CommandArgumentType] 分开表达：
 * 类型说明“这是什么”，属性说明“允许什么”。
 *
 * 数值范围的可空语义：协议用「有没有设置」的位标记上下限，没设置时线上填的是
 * `Integer.MIN_VALUE` / `Double.MAX_VALUE` 这类哨兵值。本层把哨兵值还原成 null，
 * 表达“不限”；若服务端真的把下限设成 `Integer.MIN_VALUE`，也会被读成不限——
 * 两者行为等价，不另作区分。
 */
public sealed interface CommandArgumentProperty {
    /** 整数/长整数参数的闭区间；null 表示该侧不限。 */
    public data class IntegerRange(
        public val min: Long? = null,
        public val max: Long? = null,
    ) : CommandArgumentProperty

    /** 浮点参数的闭区间；null 表示该侧不限。 */
    public data class DecimalRange(
        public val min: Double? = null,
        public val max: Double? = null,
    ) : CommandArgumentProperty

    /** 文本参数的分词模式。 */
    public data class Text(public val mode: CommandTextMode) : CommandArgumentProperty

    /**
     * 实体选择器参数。
     *
     * [singleTarget] 表示只接受一个目标，[playersOnly] 表示 `@e` 之类被限制为玩家。
     */
    public data class Entity(
        public val singleTarget: Boolean,
        public val playersOnly: Boolean,
    ) : CommandArgumentProperty

    /** 记分板持有者名字：是否允许多个（`*` 与列表）。 */
    public data class ScoreHolder(public val allowMultiple: Boolean) : CommandArgumentProperty

    /** 时间参数的最小值，单位是游戏刻。 */
    public data class Time(public val minTicks: Int) : CommandArgumentProperty

    /** 资源参数（`RESOURCE` / `RESOURCE_OR_TAG` 等）指向的注册表键名。 */
    public data class Resource(public val registry: String) : CommandArgumentProperty
}
