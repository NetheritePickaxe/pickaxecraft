package org.pickaxe.client

import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.command.CommandSuggestions
import org.pickaxe.protocol.command.CommandTree
import org.pickaxe.protocol.entity.EntityAttribute
import org.pickaxe.protocol.entity.MobEffect
import org.pickaxe.protocol.entity.PlayerAbilities
import org.pickaxe.protocol.world.BlockChange
import org.pickaxe.protocol.world.BlockEntity
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
     * 服务端下发了方块变更；区块缓存已更新，[Session.blockStateAt] 能查到新值。
     *
     * 一个服务端包对应一个事件：单格变更是一条、爆炸或水流是整批。批量变更可能跨越
     * 未加载的区块，那些格不会出现在 [changes] 里（它们在服务端本来就没有落点）。
     */
    public data class BlocksChanged(public val changes: List<BlockChange>) : ClientEvent

    /**
     * 服务端确认处理了本端的某个方块交互（见 [Session.digBlock] 等）。
     *
     * [sequence] 是本端自增、由服务端原样回送的序号；这是“动作确实到达并处理完了”的
     * 唯一直接证据。本库不做方块预测，这个事件主要是给需要严格时序的调用方对账用的。
     */
    public data class BlockAcknowledged(public val sequence: Int) : ClientEvent

    /**
     * 一列区块的光照变了（放火把、挖开地面、服务端重算）。
     *
     * 事件只报坐标：光照是整列属性，逐层逐格通知既慢又没必要，渲染层按列重建即可。
     * 事件到达时缓存已合并，[Session.skyLightAt] / [Session.blockLightAt] 能查到新值。
     *
     * 区块不在缓存里时不会产生这个事件——那条更新在本端没有落点。
     */
    public data class ChunkLightUpdated(
        public val chunkX: Int,
        public val chunkZ: Int,
    ) : ClientEvent

    /**
     * 一个方块实体的数据被创建或更新（箱子、告示牌、刷怪笼）。
     *
     * 事件到达时缓存已更新，可以用 [Session.blockEntity] 取到同一份 [BlockEntity]。
     * 方块被换成非方块实体时**没有**对应事件：那条 [BlocksChanged] 已经说明旧实体不存在，
     * 事件里的 `changes` 会带上这一格。
     */
    public data class BlockEntityChanged(public val entity: BlockEntity) : ClientEvent

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
     * 实体 [entityId] 的属性被服务端更新了。
     *
     * [changed] 只包含本包出现的项；完整表在 [Session.attributes]，事件到达时已合并。
     * 本端自己的属性变化也在其中。
     */
    public data class EntityAttributesUpdated(
        public val entityId: Int,
        public val changed: List<EntityAttribute>,
    ) : ClientEvent

    /**
     * 实体 [entityId] 受到了伤害。
     *
     * [sourceType] 是伤害类型的数字 id（协议不带类型注册表，本层不猜名字）；
     * [sourceCause] / [sourceDirect] 是造成伤害的实体 id，协议里 0 表示“没有”，转成 null。
     */
    public data class EntityDamaged(
        public val entityId: Int,
        public val sourceType: Int,
        public val sourceCause: Int?,
        public val sourceDirect: Int?,
    ) : ClientEvent

    /**
     * 生物实体的生命值变为 [health]，[Session.entity] 里的快照已同步更新。
     *
     * 只有适配层确认实体类型属于生物层级、且元数据里的值真的变了才发；本端玩家自己的
     * 血量走 [StatusChanged]。
     */
    public data class EntityHealthChanged(
        public val entityId: Int,
        public val health: Float,
    ) : ClientEvent

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

    /**
     * 服务端下发了本端能力快照，[Session.abilities] 已更新。
     *
     * 这是对飞行请求的权威答复：本端 `setFlying(true)` 之后只有收到这里的 `flying=true`
     * 才算真的在飞。切换游戏模式、服务端拒绝请求都会体现为这一条事件。
     */
    public data class AbilitiesChanged(public val abilities: PlayerAbilities) : ClientEvent

    /**
     * 实体 [entityId] 的状态效果发生变化，[Session.effects] 已合并。
     *
     * [changed] 是本包新增或被重新设置的效果，[removed] 是本包移除的效果键名；
     * 两个列表都很短——协议一次只推一个效果。
     */
    public data class EntityEffectsChanged(
        public val entityId: Int,
        public val changed: List<MobEffect>,
        public val removed: List<String>,
    ) : ClientEvent

    /** 窗口数值属性变化；[Session.containerProperties] 已更新。 */
    public data class ContainerPropertyChanged(
        public val windowId: Int,
        public val property: Int,
        public val value: Int,
    ) : ClientEvent

    /**
     * 服务端下发了命令树（登录后一次，权限变化时可能重发），[Session.commands] 已更新。
     *
     * 这是补全与命令提示的数据来源：节点、参数类型都由服务端决定，本层只转成只读模型。
     */
    public data class CommandTreeReceived(public val tree: CommandTree) : ClientEvent

    /**
     * 服务端回答了一次补全请求。
     *
     * `Session.complete` 的返回值是同一份数据；这个事件是给“不想用挂起 API”的调用方
     * 准备的旁路。两者互不影响：事件总是上报，编组由 [CommandSuggestions.transactionId] 承担。
     */
    public data class CommandSuggestionsReceived(
        public val suggestions: CommandSuggestions,
    ) : ClientEvent

    /** 正常关闭：调用方主动关闭，或服务端按协议收尾。 */
    public data object Closed : ClientEvent

    /** 异常终止，[error] 是可分类的失败原因。 */
    public data class Failed(public val error: ClientException) : ClientEvent
}
