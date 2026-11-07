package org.pickaxe.protocol.world

/**
 * 一次破坏进度观测：谁在挖哪一格、挖到了第几阶段。
 *
 * 服务端在别人挖方块时用 `block_destruction` 包广播进度，1–10 越大越接近破坏；
 * 观测消失（方块挖掉、挖掘取消、换目标）由**删除条目**表达，不是 stage 归零，
 * 因此查询接口返回 null 就是「这一格当前没有可见的破坏进度」。
 *
 * 这是**观察他人**的能力：本端自己挖掘时原版服务端不会把进度发回来（客户端自己渲染），
 * 自己的进度仍以 `dig` + `block_changed_ack` 为准。
 */
public data class BlockDestruction(
    /** 破坏者的实体 id；上层用实体与玩家列表查询可把它关联到具体玩家。 */
    public val breakerEntityId: Int,
    /** 正在被破坏的方块坐标。 */
    public val position: BlockPosition,
    /** 破坏阶段，1–10；越大越接近破坏完成。 */
    public val stage: Int,
)
