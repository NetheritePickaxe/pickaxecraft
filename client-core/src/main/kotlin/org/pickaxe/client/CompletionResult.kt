package org.pickaxe.client

import org.pickaxe.protocol.command.CommandSuggestions

/** 一次命令补全请求的默认等待时间：服务端正常会在一个往返内回答。 */
public const val DEFAULT_COMPLETION_TIMEOUT_MILLIS: Long = 3_000

/**
 * 一次命令补全请求的结果。
 *
 * 三种情况必须能分开看：真的拿到了候选（哪怕列表为空）、服务端没有回答、以及
 * “还没发出去就已经失败”（后者抛异常）。把它们都塞进“空列表”会让调用方
 * 分不清“没有匹配项”和“服务端不支持补全”。
 */
public sealed interface CompletionResult {
    /**
     * 服务端给出了回答。
     *
     * [CommandSuggestions.matches] 可能为空：服务端认可了请求但算不出候选
     * （例如当前位置没有可选值）。这与 [NoAnswer] 是两件不同的事。
     */
    public data class Suggestions(
        public val suggestions: CommandSuggestions,
    ) : CompletionResult

    /** 等待超时，或会话在等待期间结束：服务端没有回答这个请求。 */
    public data object NoAnswer : CompletionResult
}
