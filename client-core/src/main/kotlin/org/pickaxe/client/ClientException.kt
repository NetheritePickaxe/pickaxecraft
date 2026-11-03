package org.pickaxe.client

/**
 * 客户端可分类的失败。
 *
 * 每种失败都对应一个可区分的处理路径：`ConnectFailed` 可以换地址重试，
 * `ServerDisconnected` 通常意味着服务端拒绝（白名单、人数上限、维护），
 * `ProtocolFailure` 意味着版本或依赖不匹配，`EventOverflow` 是消费者跟不上。
 */
public sealed class ClientException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** TCP 连接未能建立：地址不可达、端口未监听、DNS 失败。 */
    public class ConnectFailed(message: String, cause: Throwable? = null) : ClientException(message, cause)

    /** 连接建立后的网络层错误：读超时、连接被重置、解码失败之外的 IO 异常。 */
    public class NetworkFailure(cause: Throwable) : ClientException("网络错误：${cause.message}", cause)

    /** 服务端主动结束会话。 */
    public class ServerDisconnected(public val reason: String) : ClientException("服务端断开：$reason")

    /** 协议层错误：状态机不符、包解码失败等。 */
    public class ProtocolFailure(cause: Throwable) : ClientException("协议错误：${cause.message}", cause)

    /** 等待某个阶段超时。 */
    public class Timeout(public val timeoutMillis: Long) : ClientException("操作超时（${timeoutMillis}ms）")

    /** 事件缓冲写满：按 M1 的保守策略终止会话，不静默丢弃事件。 */
    public class EventOverflow(public val capacity: Int) : ClientException("事件缓冲已满（容量 $capacity），会话终止")

    /** 会话已经关闭，无法继续操作。 */
    public class Closed : ClientException("会话已关闭")
}
