package org.pickaxe.protocol.command

/**
 * 服务端对一次补全请求的回答。
 *
 * [start] 与 [length] 描述**要被替换的区间**（以补全请求里那段文本为基准的字符下标），
 * 不是匹配项自身的位置：服务端只回一段替换指令，把哪几个字符换掉由它决定。
 * `/gameme` 请求补全可能回 `start=1, length=6, matches=[gamemode]`，
 * 表示“把第 1 到第 7 个字符换成 gamemode”。
 *
 * [tooltips] 与 [matches] 一一对应，没有提示的项为 null（协议里是“这个条目不带提示”，
 * 不是空字符串）。提示已渲染成纯文本。
 */
public data class CommandSuggestions(
    /** 与之对应的补全请求编号；同一连接上可以同时有多个未完成的请求。 */
    public val transactionId: Int,
    /** 替换区间的起始下标（含）。 */
    public val start: Int,
    /** 替换区间的长度；0 表示在 [start] 处插入。 */
    public val length: Int,
    /** 匹配项，按服务端给出的顺序。 */
    public val matches: List<String>,
    /** 与 [matches] 等长的提示文本，缺省为 null。 */
    public val tooltips: List<String?> = emptyList(),
) {
    init {
        require(tooltips.isEmpty() || tooltips.size == matches.size) {
            "提示数量 ${tooltips.size} 与匹配项数量 ${matches.size} 不一致"
        }
    }

    /** 有匹配项时为第一个匹配，否则为 null。 */
    public val firstMatch: String? get() = matches.firstOrNull()

    /**
     * 把第一个匹配应用到 [text] 上，返回补全后的整串输入。
     *
     * [text] 必须是发起请求时的那段原文：区间下标是相对它算的，拿别的文本套会错位。
     * 返回 null 表示无法应用——没有匹配项，或服务端给的区间落在文本之外（畸形数据）。
     * 这里不做“尽量修正”，错位地替换出一段假输入比不替换更难排查。
     */
    public fun applyTo(text: String): String? {
        val match = firstMatch ?: return null
        if (start < 0 || start > text.length) return null
        val end = (start.toLong() + length).coerceAtMost(text.length.toLong()).toInt()
        if (end < start) return null
        return text.substring(0, start) + match + text.substring(end)
    }
}
