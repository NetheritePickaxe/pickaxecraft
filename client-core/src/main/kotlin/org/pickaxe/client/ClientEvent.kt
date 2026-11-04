package org.pickaxe.client

import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.world.RemotePlayer
import org.pickaxe.protocol.world.TrackedEntity
import java.util.UUID

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

    /**
     * 服务端下发了位置同步（确认包已自动回送）。
     *
     * 这是**服务端的权威坐标**：出生、传送、以及对本端移动的修正都走它。
     * 本端自己用 [Session.moveTo] 声明的移动不会产生这个事件——服务端接受移动时不回包，
     * 快照由 [Session.world] 直接推进；只在这里看到“服务端改了本端的位置”。
     */
    public data class PositionUpdated(public val position: Position) : ClientEvent

    /**
     * 服务端完成重生或维度切换。
     *
     * 旧维度的区块与实体已作废，随后会重发；此事件到达时缓存已清空，位置等待新的
     * [PositionUpdated]。
     */
    public data class Respawned(public val dimension: String, public val gameMode: String) : ClientEvent

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

    /**
     * 玩家列表发生变化。
     *
     * 一个服务端包对应一个事件：[updated] 是新增或有字段变化的条目，[removed] 是被移出
     * 列表的 UUID。事件到达时缓存已更新，[Session.players] 能查到同一份数据。
     */
    public data class PlayerListChanged(
        public val updated: List<RemotePlayer>,
        public val removed: List<UUID>,
    ) : ClientEvent

    /**
     * 收到一条聊天消息（系统、玩家或伪装）。事件到达时已写入 [Session.chat]。
     *
     * 聊天是可回看的事实而不是可重建的状态：同一条消息不会再次到达，因此逐条投递。
     */
    public data class ChatReceived(public val message: ChatMessage) : ClientEvent

    /** 服务端要求删除一条已签名消息；对应条目已从 [Session.chat] 移除。 */
    public data class ChatDeleted(public val signatureId: Int) : ClientEvent

    /**
     * 一个窗口的内容发生变化。窗口 0 是玩家背包。
     *
     * 事件只带窗口 id：内容可能一次改一格、也可能整包替换，逐槽通知既慢又没必要。
     * 事件到达时缓存已更新，用 [Session.inventory] / [Session.container] 取同一份快照。
     */
    public data class InventoryChanged(public val windowId: Int) : ClientEvent

    /** 服务端打开了新窗口（箱子、工作台……）；会话缓存里已可查到它，内容随后下发。 */
    public data class ContainerOpened(
        public val windowId: Int,
        public val type: String,
        public val title: String,
    ) : ClientEvent

    /** 服务端关闭了某个窗口；缓存里已移除，窗口 0（玩家背包）不会出现这个事件。 */
    public data class ContainerClosed(public val windowId: Int) : ClientEvent

    /**
     * 快捷栏选中格发生变化。
     *
     * 服务端接受切换时不回包，因此本端上行成功后立即触发；服务端拒绝或改写时以它的值为准
     * （`set_held_slot`），同样触发这个事件。
     */
    public data class HeldSlotChanged(public val slot: Int) : ClientEvent

    /**
     * 本端生命/饥饿或经验发生变化，[status] 是合并后的完整快照。
     *
     * 一个服务端包对应一个事件；两个包各管一半，因此事件里另一半可能还是旧值。
     * 快照同时可从 [Session.status] 读取。
     */
    public data class StatusChanged(public val status: PlayerStatus) : ClientEvent

    /** 正常关闭：调用方主动关闭，或服务端按协议收尾。 */
    public data object Closed : ClientEvent

    /** 异常终止，[error] 是可分类的失败原因。 */
    public data class Failed(public val error: ClientException) : ClientEvent
}
