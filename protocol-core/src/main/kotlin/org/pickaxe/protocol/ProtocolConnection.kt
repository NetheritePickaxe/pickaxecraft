package org.pickaxe.protocol

import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.pickaxe.protocol.internal.ChatInput
import org.pickaxe.protocol.internal.McplConnection
import org.pickaxe.protocol.inventory.PlayerInventoryLayout

/**
 * 一条协议连接的句柄。
 *
 * 创建即分配好协议状态，[connect] 才会真正发起网络连接；[close] 是幂等的，
 * 会释放该连接独占的网络线程。上层拿不到 `Session`、`Packet` 或 `ByteBuf`。
 *
 * 订阅关系在创建时确定：事件只发给构造时传入的 [ProtocolListener]，且回调发生在
 * 该连接自己的网络线程上。
 */
public class ProtocolConnection private constructor(
    private val connection: McplConnection,
) : AutoCloseable {

    /** 发起连接。只允许调用一次，网络结果通过事件报告，不会阻塞调用线程。 */
    public fun connect() {
        connection.start()
    }

    /** 关闭连接并回收网络线程。重复调用无副作用。 */
    override fun close() {
        connection.shutdown()
    }

    /** 是否已经进入关闭流程。 */
    public val isClosed: Boolean
        get() = connection.isShutDown

    /**
     * 发送一条聊天消息。
     *
     * 消息不带签名：离线模式的服务端（`enforce-secure-profile=false`）可以直接接收，
     * 这也是本库当前支持的唯一场景；启用安全聊天的在线服务端需要会话密钥，属于 M5。
     *
     * 发送不与收包同步：服务端把消息广播回来之后，同一文本会作为
     * [ProtocolEvent.ChatReceived] 再出现一次，包括发送者自己发的那条。本端不在本地插入，
     * 因为服务端可能过滤、改写或拒收——本地插入会让“显示过”与“服务端认可”不一致。
     *
     * 调用线程不限；同一线程内的多次调用按调用顺序发出。
     *
     * @throws IllegalArgumentException 内容为空、只有空白、含换行或超过 256 个字符
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun sendChat(message: String) {
        connection.sendChat(ChatInput.message(message))
    }

    /**
     * 发送一条命令，例如 `sendCommand("/help")`。
     *
     * 前导斜杠可写可不写，包里的命令不带斜杠。命令包不参与聊天签名，因此在启用安全聊天的
     * 服务端上同样可用（能否执行仍取决于玩家权限）。
     *
     * @throws IllegalArgumentException 去掉斜杠后为空、只有空白、含换行或超过 256 个字符
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun sendCommand(command: String) {
        connection.sendCommand(ChatInput.command(command))
    }

    /**
     * 把本端位置与朝向声明给服务端。
     *
     * 只发送与上一状态不同的分量（协议里 `pos`、`rot`、`pos_rot` 是三个包）；坐标与朝向
     * 都没变且落地标记也没变时什么也不发。
     *
     * 服务端**接受**移动时不回包，因此这条调用不产生“确认”事件；服务端不同意时下发位置
     * 同步，表现为 [ProtocolEvent.PositionUpdated]。本端在发送后立即把自己记录的位置推进到
     * 目标值，并上报 [ProtocolEvent.PositionSent]。
     *
     * 调用线程不限；同一线程内的多次调用按调用顺序发出。
     *
     * @throws IllegalArgumentException 任一坐标为 NaN 或无穷
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun moveTo(
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
        onGround: Boolean = false,
    ) {
        connection.moveTo(x, y, z, yaw, pitch, onGround)
    }

    /**
     * 只改朝向，位置不变。
     *
     * 协议里的 `rot` 包不带坐标，因此这条路径不要求已经收到过位置同步。
     *
     * @throws IllegalArgumentException 任一角度为 NaN 或无穷
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun look(yaw: Float, pitch: Float, onGround: Boolean = false) {
        connection.look(yaw, pitch, onGround)
    }

    /**
     * 切换快捷栏选中格（0–8）。
     *
     * 本端不预设结果：服务端会用 `set_held_slot` 回一条，表现为
     * [ProtocolEvent.HeldSlotChanged]；被插件取消时回的是旧值。
     *
     * @throws IllegalArgumentException 下标不在 0..8 之间
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun selectHotbarSlot(slot: Int) {
        require(slot in 0 until PlayerInventoryLayout.HOTBAR_SIZE) {
            "快捷栏下标应在 0..${PlayerInventoryLayout.HOTBAR_SIZE - 1} 之间，实际为 $slot"
        }
        connection.selectHotbarSlot(slot)
    }

    public companion object {

        /**
         * 创建一次状态查询。
         *
         * 状态查询在协议层是短连接：拿到服务端信息与 Ping 往返耗时后由协议库收尾，
         * 上层随后调用 [close] 回收资源即可。
         */
        public fun status(
            address: ServerAddress,
            listener: ProtocolListener,
            connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
            readTimeoutSeconds: Int = DEFAULT_READ_TIMEOUT_SECONDS,
        ): ProtocolConnection = create(
            address = address,
            protocol = MinecraftProtocol(),
            listener = listener,
            statusQuery = true,
            connectTimeoutSeconds = connectTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds,
        )

        /**
         * 创建一次离线登录。
         *
         * 只支持离线模式：在线模式需要微软认证与令牌存储，属于 M5。
         */
        public fun login(
            address: ServerAddress,
            username: String,
            listener: ProtocolListener,
            connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
            readTimeoutSeconds: Int = DEFAULT_READ_TIMEOUT_SECONDS,
        ): ProtocolConnection {
            require(username.isNotBlank()) { "username 不能为空" }
            require(username.length <= MAX_USERNAME_LENGTH) {
                "username 最长 $MAX_USERNAME_LENGTH 个字符，实际为 ${username.length}"
            }
            return create(
                address = address,
                protocol = MinecraftProtocol(username),
                listener = listener,
                statusQuery = false,
                connectTimeoutSeconds = connectTimeoutSeconds,
                readTimeoutSeconds = readTimeoutSeconds,
            )
        }

        private fun create(
            address: ServerAddress,
            protocol: MinecraftProtocol,
            listener: ProtocolListener,
            statusQuery: Boolean,
            connectTimeoutSeconds: Int,
            readTimeoutSeconds: Int,
        ): ProtocolConnection {
            require(connectTimeoutSeconds > 0) { "connectTimeoutSeconds 必须为正数" }
            require(readTimeoutSeconds > 0) { "readTimeoutSeconds 必须为正数" }
            return ProtocolConnection(
                McplConnection(
                    target = address,
                    protocol = protocol,
                    listener = listener,
                    statusQuery = statusQuery,
                    connectTimeoutSeconds = connectTimeoutSeconds,
                    readTimeoutSeconds = readTimeoutSeconds,
                ),
            )
        }

        private const val DEFAULT_CONNECT_TIMEOUT_SECONDS: Int = 10
        private const val DEFAULT_READ_TIMEOUT_SECONDS: Int = 30

        /** Minecraft 用户名长度上限，超长会被服务端直接踢下线。 */
        private const val MAX_USERNAME_LENGTH: Int = 16
    }
}
