package org.pickaxe.client

import kotlin.math.ceil

/**
 * 客户端行为参数。
 *
 * 超时全部是有限值：M1 的验收要求是“失败必须是一个可观察的结果”，
 * 不是无限等待。
 */
public data class ClientOptions(
    /** TCP 连接建立的超时。协议库按秒计时，实际生效值向上取整。 */
    public val connectTimeoutMillis: Long = 10_000,
    /** 从发起到进入 play 的总超时。 */
    public val loginTimeoutMillis: Long = 20_000,
    /** 一次状态查询的总超时。 */
    public val statusTimeoutMillis: Long = 10_000,
    /** 空闲读取超时，超过后由协议库判定连接失效。 */
    public val readTimeoutMillis: Long = 30_000,
    /**
     * 网络线程到会话 Actor 之间的事件缓冲容量。
     *
     * 缓冲写满意味着消费者长期跟不上；M1 的策略是终止该会话并报告
     * [ClientException.EventOverflow]，不做静默丢弃。
     */
    public val eventBufferCapacity: Int = 256,
) {
    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis 必须为正数" }
        require(loginTimeoutMillis > 0) { "loginTimeoutMillis 必须为正数" }
        require(statusTimeoutMillis > 0) { "statusTimeoutMillis 必须为正数" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis 必须为正数" }
        require(eventBufferCapacity > 0) { "eventBufferCapacity 必须为正数" }
    }

    internal fun connectTimeoutSeconds(): Int = toSeconds(connectTimeoutMillis)

    internal fun readTimeoutSeconds(): Int = toSeconds(readTimeoutMillis)

    private fun toSeconds(millis: Long): Int = ceil(millis / 1000.0).toInt().coerceAtLeast(1)
}
