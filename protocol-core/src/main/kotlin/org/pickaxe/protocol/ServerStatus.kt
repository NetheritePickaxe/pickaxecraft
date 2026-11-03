package org.pickaxe.protocol

/**
 * 服务端在状态查询中返回的公开信息。
 *
 * 这里的字段全部是纯数据：MOTD 已经由适配层从 Adventure 组件渲染成文本，
 * 上层模块不会因为协议库的文本组件类型而改变自己的公共 API。
 */
public data class ServerStatus(
    /** 服务端声明的游戏版本名，例如 `26.2`；服务端未提供时为 null。 */
    public val versionName: String?,
    /** 服务端声明的协议号，例如 776；服务端未提供时为 null。 */
    public val protocolVersion: Int?,
    /** 当前在线人数；服务端未提供时为 null。 */
    public val onlinePlayers: Int?,
    /** 人数上限；服务端未提供时为 null。 */
    public val maxPlayers: Int?,
    /** 已渲染成纯文本的 MOTD，多行为 `\n` 分隔。 */
    public val description: String,
    /** 从发出 Ping 到收到 Pong 的往返耗时。 */
    public val latencyMillis: Long,
)
