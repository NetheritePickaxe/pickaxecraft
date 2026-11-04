package org.pickaxe.protocol.world

import java.util.UUID

/**
 * 服务端告知本端存在的一个实体。
 *
 * 不可变快照：实体每次移动都会产生一个新对象，上层拿到的是“某一时刻的状态”，
 * 而不是一个会被并发改写的可变记录。这样它既能跨线程读取，也能直接当作缓存值。
 *
 * 只保留定位实体所需的最小事实。装备、状态效果、生命值、实体元数据（NBT）都不在这里，
 * 它们属于另一类数据：调用方通常按需查询，而不是每 tick 跟随。
 *
 * [type] 是协议里的实体类型名（如 `ZOMBIE`），由协议库的枚举转换而来。
 * 本库不持有类型资源表，也不把实体类型当作可扩展的名字空间。
 */
public data class TrackedEntity(
    /** 协议内的实体 id，只在本会话内有效；服务端重连或重进世界后会重新分配。 */
    public val id: Int,
    /** 实体的持久 UUID，可用于跨会话识别同一个玩家或生物。 */
    public val uuid: UUID,
    /** 实体类型名，取自协议枚举，例如 `ZOMBIE`、`PLAYER`。 */
    public val type: String,
    /** 脚部坐标，与服务端世界坐标一致。 */
    public val x: Double,
    public val y: Double,
    public val z: Double,
    /** 身体朝向（水平旋转，度数）。 */
    public val yaw: Float,
    /** 视线俯仰（度数）。 */
    public val pitch: Float,
    /** 头部朝向，独立于 [yaw]：实体可以身体朝前而头转向别处。 */
    public val headYaw: Float,
)
