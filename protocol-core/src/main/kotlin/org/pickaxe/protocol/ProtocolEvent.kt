package org.pickaxe.protocol

/**
 * 客户端视角的协议阶段。
 *
 * 只包含登录链路经过的阶段：状态查询是一次性请求，由 [ProtocolEvent.StatusReceived]
 * 单独表达，不进入这个枚举。
 */
public enum class ProtocolPhase {
    LOGIN,
    CONFIGURATION,
    PLAY,
}

/**
 * 适配层向上报告的事实。
 *
 * 这些事件由协议库的回调转换而来，**发生在会话的网络线程上**：
 * 监听者只能做入队之类的短操作，不得阻塞，也不得在其中执行调用方业务逻辑。
 */
public sealed interface ProtocolEvent {
    /** TCP 连接已建立，握手包已交给协议库处理。 */
    public data object Connected : ProtocolEvent

    /** 协议阶段发生变化。同一阶段不会重复上报。 */
    public data class PhaseChanged(public val phase: ProtocolPhase) : ProtocolEvent

    /** 配置阶段收到一份注册表数据。 */
    public data class RegistryData(
        public val registryId: String,
        public val entryCount: Int,
    ) : ProtocolEvent

    /** 状态查询完成，携带服务端信息与实测往返耗时。 */
    public data class StatusReceived(public val status: ServerStatus) : ProtocolEvent

    /** 会话终止，且这是本次连接唯一一次终止通知。 */
    public data class Closed(public val reason: ProtocolCloseReason) : ProtocolEvent
}

/** 会话终止的原因分类。 */
public sealed interface ProtocolCloseReason {
    /** 由本端主动关闭，或状态查询按协议正常收尾。 */
    public data object Local : ProtocolCloseReason

    /** 服务端结束会话，[message] 是已渲染成纯文本的原因。 */
    public data class Server(public val message: String) : ProtocolCloseReason

    /** 网络或协议错误。连接被拒绝、读超时、解码失败都会走到这里。 */
    public data class Failure(
        public val kind: ProtocolFailureKind,
        public val cause: Throwable,
    ) : ProtocolCloseReason
}

/**
 * 失败发生在哪一层。
 *
 * 由适配层判定：它知道连接是否建立过、异常来自哪一段管道；上层据此选择
 * “换地址重试”“等待网络恢复”还是“检查版本与依赖”，无需识别具体异常类。
 */
public enum class ProtocolFailureKind {
    /** 连接尚未建立：地址不可达、端口未监听、DNS 失败、连接超时。 */
    CONNECT,

    /** 连接建立之后的传输层错误：读超时、连接重置。 */
    NETWORK,

    /** 协议层错误：状态机不符、包解码或编码失败。 */
    PROTOCOL,
}

/**
 * 适配层事件出口。
 *
 * 实现必须是线程安全且非阻塞的：[onEvent] 会被网络线程直接调用。
 */
public fun interface ProtocolListener {
    public fun onEvent(event: ProtocolEvent)
}
