package org.pickaxe.client

import org.pickaxe.protocol.world.TrackedEntity

/**
 * 会话事件。
 *
 * 事件流按到达顺序投递，只有一个消费者；终止事件 [Closed] 与 [Failed] 恰好出现一次，
 * 且之后不再有事件。状态迁移也通过 [StateChanged] 通知，不依赖调用方轮询。
 *
 * 进入 play 之后的事件（[JoinedWorld]、[PositionUpdated]、[ChunksReceived]）描述出生过程：
 * 它们是可观察的里程碑，而不是需要调用方回应的请求——协议要求的确认由适配层完成。
 */
public sealed interface ClientEvent {

    /** 会话进入新状态；进入终止状态时以 [Closed] / [Failed] 代替。 */
    public data class StateChanged(public val state: SessionState) : ClientEvent

    /** 配置阶段收到一份注册表数据。 */
    public data class RegistryReceived(
        public val registryId: String,
        public val entryCount: Int,
    ) : ClientEvent

    /** 服务端下发了世界身份信息，play 阶段开始。 */
    public data class JoinedWorld(
        public val entityId: Int,
        public val dimension: String,
        public val gameMode: String,
    ) : ClientEvent

    /** 位置同步已完成（确认包已自动回送）。 */
    public data class PositionUpdated(public val position: Position) : ClientEvent

    /** 一个区块批次已接收并确认。[total] 是本次会话累计区块数。 */
    public data class ChunksReceived(public val chunks: Int, public val total: Int) : ClientEvent

    /**
     * 一个实体进入视野。
     *
     * 事件到达时实体已写入会话缓存，可以直接用 [Session.entity] 取到同一份快照。
     * 后续位置变化不逐条投递，按需读取即可——位置是高频状态，不是里程碑。
     */
    public data class EntitySpawned(public val entity: TrackedEntity) : ClientEvent

    /** 这些实体离开了视野，或已死亡/消失。 */
    public data class EntityRemoved(public val ids: List<Int>) : ClientEvent

    /** 正常关闭：调用方主动关闭，或服务端按协议收尾。 */
    public data object Closed : ClientEvent

    /** 异常终止，[error] 是可分类的失败原因。 */
    public data class Failed(public val error: ClientException) : ClientEvent
}
