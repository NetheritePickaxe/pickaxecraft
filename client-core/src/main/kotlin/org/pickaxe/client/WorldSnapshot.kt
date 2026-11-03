package org.pickaxe.client

/** 玩家在世界中的位置与朝向，来自服务端的位置同步。 */
public data class Position(
    public val x: Double,
    public val y: Double,
    public val z: Double,
    public val yaw: Float,
    public val pitch: Float,
)

/**
 * 会话级的世界状态快照。
 *
 * 字段是逐步填充的：进入 play 时 [entityId]、[dimension]、[gameMode] 先就位，
 * 位置同步与区块到达后再补上 [position] 与 [chunksLoaded]。读取方不需要自己
 * 拼装这些事实，也不该假设它们来自哪一个包。
 */
public data class WorldSnapshot(
    public val entityId: Int? = null,
    public val dimension: String? = null,
    public val gameMode: String? = null,
    public val position: Position? = null,
    /** 本次会话累计接收的区块数；只在批次收尾时更新，不代表已解码的方块内容。 */
    public val chunksLoaded: Int = 0,
)
