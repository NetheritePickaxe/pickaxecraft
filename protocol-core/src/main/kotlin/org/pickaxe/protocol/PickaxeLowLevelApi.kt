package org.pickaxe.protocol

/**
 * 标记“低于 `client-core` 的协议适配入口”。
 *
 * 常规使用应当走 `client-core` 的 `MinecraftClient` / `Session`：它们负责会话状态机、
 * 有界事件缓冲、区块与实体缓存，以及错误分类。直接使用 [ProtocolConnection] 需要调用方
 * 自己承担：
 *
 * - 背压：事件在连接自己的网络线程上回调，慢消费者会阻塞收包或让服务端超时；
 * - 顺序：事件按到达顺序投递，但没有重放，调用方必须自己保存需要回看的状态；
 * - 关闭语义：`close()` 是幂等的，但关闭之后的发送会失败，且不会再有任何事件。
 *
 * 因此本注解的 opt-in 是错误级别的：不是不能直接用，而是必须先明确自己接过了这些责任。
 * 这些入口的签名仍属于 0.x 实验契约，可能在小版本内调整。
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "这是低于 client-core 的低层协议入口；请确认已自行处理背压、事件顺序与关闭语义。",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class PickaxeLowLevelApi
