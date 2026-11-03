package org.pickaxe.protocol

import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.pickaxe.protocol.internal.McplConnection

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
