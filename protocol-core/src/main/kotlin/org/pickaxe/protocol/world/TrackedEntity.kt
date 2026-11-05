package org.pickaxe.protocol.world

import org.pickaxe.protocol.inventory.InventoryItem
import java.util.UUID

/**
 * 服务端告知本端存在的一个实体。
 *
 * 不可变快照：实体每次移动都会产生一个新对象，上层拿到的是“某一时刻的状态”，
 * 而不是一个会被并发改写的可变记录。这样它既能跨线程读取，也能直接当作缓存值。
 *
 * 只保留定位与识别实体所需的最小事实。状态效果与属性走独立缓存，不塞进这张快照；
 * 装备和实体元数据里的 [customName]、[health] 则直接挂在这里，方便按 id 一次取全。
 * [customName] 是服务端明确要求显示的名字，与玩家列表里的用户名互不替代。
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
    /**
     * 服务端给实体设置的自定义名称（已渲染成纯文本，`null` 表示没有）。
     *
     * 玩家实体通常没有这个字段：玩家的名字来自玩家列表（见 [RemotePlayer]）。
     * 生物与掉落物上的名牌才走这里。
     */
    public val customName: String? = null,
    /**
     * 装备槽 → 物品；没有装备的槽位不出现。
     *
     * 装备走**独立的包**（`set_equipment`），不依赖实体元数据下标，因此这里能给出所有实体都
     * 成立的装备信息——这与 [customName] 只能读下标 2 的处境不同。
     * 键是协议的槽位名：`MAIN_HAND`、`OFF_HAND`、`HELMET`、`CHESTPLATE`、`LEGGINGS`、`BOOTS`，
     * 以及 26.2 新增的 `BODY`、`SADDLE`。
     */
    public val equipment: Map<String, InventoryItem> = emptyMap(),
    /**
     * 实体当前生命值；`null` 表示「还没收到过」或「这类实体没有血量」。
     *
     * 元数据下标 9 只对生物层级成立（见 `LivingEntityTypes`），适配层先按 [type] 判定层级
     * 再取值，因此船、掉落物、展示实体不会拿到一个碰巧同下标的无关数字。玩家**自己**的
     * 血量不走这里：那是 `set_health` 包，落在 `Session.status`。
     */
    public val health: Float? = null,
)
