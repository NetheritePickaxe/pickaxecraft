package org.pickaxe.client

/**
 * 会话事件。
 *
 * 事件流按到达顺序投递，只有一个消费者；终止事件 [Closed] 与 [Failed] 恰好出现一次，
 * 且之后不再有事件。状态迁移也通过 [StateChanged] 通知，不依赖调用方轮询。
 */
public sealed interface ClientEvent {

    /** 会话进入新状态；进入终止状态时以 [Closed] / [Failed] 代替。 */
    public data class StateChanged(public val state: SessionState) : ClientEvent

    /** 配置阶段收到一份注册表数据。 */
    public data class RegistryReceived(
        public val registryId: String,
        public val entryCount: Int,
    ) : ClientEvent

    /** 正常关闭：调用方主动关闭，或服务端按协议收尾。 */
    public data object Closed : ClientEvent

    /** 异常终止，[error] 是可分类的失败原因。 */
    public data class Failed(public val error: ClientException) : ClientEvent
}
