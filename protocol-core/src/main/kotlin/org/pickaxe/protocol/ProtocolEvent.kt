package org.pickaxe.protocol

import org.pickaxe.protocol.chat.ChatMessage
import org.pickaxe.protocol.world.ChunkColumn
import org.pickaxe.protocol.world.RemotePlayer
import org.pickaxe.protocol.world.TrackedEntity
import java.util.UUID

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

    /**
     * 进入世界。服务端在 configuration 结束后用 Login 包下发身份与维度信息，
     * 这是 play 阶段真正开始的标志；此时还没有位置与区块。
     */
    public data class JoinedWorld(
        public val entityId: Int,
        public val dimension: String,
        public val gameMode: String,
        public val viewDistance: Int,
        public val simulationDistance: Int,
        public val maxPlayers: Int,
    ) : ProtocolEvent

    /**
     * 位置同步已完成确认。
     *
     * 服务端下发 Synchronize Player Position 后，适配层立即回确认包；这里报告的是
     * 应用相对量之后的绝对坐标。服务端可能随时再次下发（移动修正、传送）。
     */
    public data class PositionUpdated(
        public val x: Double,
        public val y: Double,
        public val z: Double,
        public val yaw: Float,
        public val pitch: Float,
    ) : ProtocolEvent

    /**
     * 服务端完成重生或维度切换。
     *
     * 旧维度的区块与实体全部作废：服务端会按新维度重发，本端必须丢掉旧缓存，
     * 否则会把上一个维度的 section 数量套到新维度上。位置要等紧随其后的位置同步包，
     * 在此之前没有可信坐标。
     */
    public data class Respawned(
        public val dimension: String,
        public val gameMode: String,
    ) : ProtocolEvent

    /**
     * 一个区块批次已接收并确认。[chunks] 是本批数量，[total] 是本次会话累计。
     *
     * 批次确认必须及时回送，否则服务端会停止继续发送区块；确认本身由适配层完成，
     * 上层只观察结果。
     */
    public data class ChunksReceived(
        public val chunks: Int,
        public val total: Int,
    ) : ProtocolEvent

    /**
     * 一列区块已解码。
     *
     * 这是**世界数据**而不是控制事件：出生时会连续到达数百列，单项体积也远大于其他事件。
     * 监听者应当按坐标写入缓存并立即返回，不要把它塞进任何与信用量绑定的队列，
     * 否则慢消费者会拖住整个连接。重发同一坐标表示服务端要求整列替换。
     */
    public data class ChunkLoaded(public val column: ChunkColumn) : ProtocolEvent

    /** 服务端要求丢弃某列区块（玩家离开视野）。 */
    public data class ChunkUnloaded(
        public val x: Int,
        public val z: Int,
    ) : ProtocolEvent

    /**
     * 一个实体进入视野。[entity] 是首次观测到的完整状态。
     *
     * 与区块一样属于**世界数据**：出生与传送时会成批出现，单项很小但频率远高于控制事件。
     * 监听者应当按 id 写入缓存并立即返回。
     */
    public data class EntitySpawned(public val entity: TrackedEntity) : ProtocolEvent

    /**
     * 已跟踪实体的位置、朝向或自定义名称发生变化。[entity] 是叠加增量之后的完整状态。
     *
     * 服务端的移动包大多是相对量（1/4096 格），适配层负责叠加；上层永远看到绝对坐标。
     * 未知 id 的增量包会被丢弃：增量只有叠加到已知状态上才有意义。
     *
     * 元数据包只在**本层关心的字段真的变了**时才上报（目前只有自定义名称）：
     * 服务端每秒会为实体下发与位置无关的状态字节，原样转发只是噪音。
     */
    public data class EntityUpdated(public val entity: TrackedEntity) : ProtocolEvent

    /** 服务端要求丢弃这些实体：离开视野、死亡或消失。未跟踪的 id 直接忽略。 */
    public data class EntityRemoved(public val ids: List<Int>) : ProtocolEvent

    /**
     * 收到一条聊天消息：系统、玩家或伪装三种来源统一成 [ChatMessage]。
     *
     * 这是**用户可见的即时事实**，不是可重建的状态：它不进任何缓存覆盖，适配层
     * 报一次就不再重复。文本已渲染成纯文本，样式不保留。
     */
    public data class ChatReceived(public val message: ChatMessage) : ProtocolEvent

    /**
     * 服务端要求删除一条已签名的玩家消息（管理操作）。
     *
     * 只有带签名的玩家消息能被删除；系统消息与伪装消息在协议里没有编号，删不掉。
     */
    public data class ChatDeleted(public val signatureId: Int) : ProtocolEvent

    /**
     * 玩家列表发生变化。
     *
     * 一个包对应一个事件，无论包里有多少条目：条目数量由服务端的人数决定，按条发事件
     * 会让大服的一次登录把事件缓冲打满。因此 [updated] 是“新增或字段有变化的条目”，
     * [removed] 是被移出列表的 UUID；两者都可能为空（例如只更新了本层不关心的字段）。
     *
     * 玩家列表是会话级事实：换维度不会清空它。
     */
    public data class PlayerListChanged(
        public val updated: List<RemotePlayer>,
        public val removed: List<UUID>,
    ) : ProtocolEvent

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
