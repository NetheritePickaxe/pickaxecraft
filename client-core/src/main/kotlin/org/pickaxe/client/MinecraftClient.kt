package org.pickaxe.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.pickaxe.protocol.ProtocolCloseReason
import org.pickaxe.protocol.ProtocolConnection
import org.pickaxe.protocol.ProtocolEvent
import org.pickaxe.protocol.ProtocolListener
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.ServerStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 客户端入口。
 *
 * 一个实例至多持有一个活动 [Session]；需要多个隔离连接时使用多个实例，
 * 会话之间不共享状态、协程作用域或网络线程。
 *
 * 所有挂起函数都不在网络线程上执行，也不会阻塞调用线程等待网络。
 */
public class MinecraftClient(
    /** 本实例的行为参数，创建后不再变更。 */
    public val options: ClientOptions = ClientOptions(),
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<Session?>(null)

    /**
     * 查询服务端状态。
     *
     * 这是短连接：拿到服务端信息与 Ping 往返耗时后连接即被关闭。
     */
    public suspend fun status(address: ServerAddress): ServerStatus {
        ensureOpen()

        val result = CompletableDeferred<ServerStatus>()
        val connection = ProtocolConnection.status(
            address = address,
            listener = ProtocolListener { event ->
                when (event) {
                    is ProtocolEvent.StatusReceived -> result.complete(event.status)
                    is ProtocolEvent.Closed -> when (val reason = event.reason) {
                        is ProtocolCloseReason.Server ->
                            result.completeExceptionally(ClientException.ServerDisconnected(reason.message))
                        is ProtocolCloseReason.Failure ->
                            result.completeExceptionally(reason.toClientException())
                        ProtocolCloseReason.Local ->
                            result.completeExceptionally(
                                ClientException.ProtocolFailure(
                                    IllegalStateException("状态查询在收到响应前结束"),
                                ),
                            )
                    }
                    else -> Unit
                }
            },
            connectTimeoutSeconds = options.connectTimeoutSeconds(),
            readTimeoutSeconds = options.readTimeoutSeconds(),
        )

        connection.connect()
        try {
            return withTimeout(options.statusTimeoutMillis) { result.await() }
        } catch (e: TimeoutCancellationException) {
            throw ClientException.Timeout(options.statusTimeoutMillis)
        } finally {
            connection.close()
        }
    }

    /**
     * 离线登录并等待进入 play。
     *
     * 返回时 [Session.state] 为 [SessionState.PLAY]；连接失败、服务端断开或超时会抛出
     * 对应的 [ClientException]，此时不会留下半开连接。
     */
    public suspend fun connect(
        address: ServerAddress,
        username: String = DEFAULT_USERNAME,
    ): Session {
        ensureOpen()

        val previous = active.get()
        if (previous != null && previous.state.value != SessionState.CLOSED) {
            throw IllegalStateException("同一客户端已有活动会话，请先关闭它")
        }

        val session = Session(address, username, options, scope)
        active.set(session)

        try {
            session.start()
            withTimeout(options.loginTimeoutMillis) { session.awaitPlay() }
            return session
        } catch (e: TimeoutCancellationException) {
            // 超时是我们自己的判定，与外部取消区分开，便于调用方决定是否重试。
            throw failClosed(session, ClientException.Timeout(options.loginTimeoutMillis))
        } catch (e: Throwable) {
            throw failClosed(session, e)
        }
    }

    /** 关闭客户端、其活动会话与所有协程。重复调用无副作用。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val session = active.getAndSet(null)
        try {
            session?.close()
        } finally {
            // 关闭失败也必须收回作用域，否则残留协程会让进程无法退出。
            scope.cancel()
        }
    }

    /** 失败时清理会话，并把清理过程中的次生异常挂在原始异常上，不掩盖真正的原因。 */
    private fun failClosed(session: Session, error: Throwable): Throwable {
        active.compareAndSet(session, null)
        return try {
            session.close()
            error
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
            error
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "客户端已关闭，不能继续使用" }
    }

    public companion object {
        /** 未指定用户名时使用的离线登录名。 */
        public const val DEFAULT_USERNAME: String = "Pickaxe"
    }
}
