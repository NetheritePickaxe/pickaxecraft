package org.pickaxe.protocol.world

/**
 * 方块坐标：整数三元组。
 *
 * 协议把方块坐标打包进一个 long：x 与 z 各 26 位有符号、y 12 位有符号。超出范围的值不会
 * 报错，而是被**静默截断**——`(0, 5000, 0)` 会变成 `(0, 904, 0)`，表现为“点了没反应”。
 * 因此坐标是一个带范围校验的值类型，而不是三个散装 int；校验发生在发送前，错误信息里带上
 * 原始坐标（见 [org.pickaxe.protocol.ProtocolConnection.digBlock]）。
 *
 * 与 `client-core` 里的 `Position` 区分：那个是实体坐标（double），这个永远是方块格。
 */
public data class BlockPosition(
    public val x: Int,
    public val y: Int,
    public val z: Int,
) {

    /** 沿 [face] 走一格；放置方块时的目标位置就是“点中的方块 + 面”。 */
    public fun offset(face: BlockFace): BlockPosition = when (face) {
        BlockFace.DOWN -> BlockPosition(x, y - 1, z)
        BlockFace.UP -> BlockPosition(x, y + 1, z)
        BlockFace.NORTH -> BlockPosition(x, y, z - 1)
        BlockFace.SOUTH -> BlockPosition(x, y, z + 1)
        BlockFace.WEST -> BlockPosition(x - 1, y, z)
        BlockFace.EAST -> BlockPosition(x + 1, y, z)
    }

    override fun toString(): String = "($x, $y, $z)"
}

/**
 * 方块的六个面，序号与协议里的方向编码一致。
 *
 * 不直接暴露协议库的 `Direction`：那会把依赖带到公共 API 上，而下游渲染工程只依赖本库。
 * 枚举成员的**顺序**是有意义的（协议按序号编码），因此不要重排——`BlockInteractionTest`
 * 会逐个钉住映射。
 */
public enum class BlockFace {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST,
    ;

    /** 相对的那一面：点在石头顶上放方块，落点就是 `UP` 方向；冷却时朝 `opposite` 看回去。 */
    public val opposite: BlockFace
        get() = when (this) {
            DOWN -> UP
            UP -> DOWN
            NORTH -> SOUTH
            SOUTH -> NORTH
            WEST -> EAST
            EAST -> WEST
        }
}

/**
 * 一次方块变更：某坐标变成了某个方块状态 id。
 *
 * 服务端把变更拆成两种包下发：单格（`block_update`）与整段批量（`section_blocks_update`，
 * 爆炸、水流、区块回滚都用它）。本库把两者统一成这个类型，用一条事件批量携带——
 * 一次爆炸可能改动几百格，逐格发事件会把有界缓冲打满。
 */
public data class BlockChange(
    public val position: BlockPosition,
    /** 新的全局方块状态 id；0 是空气（原版注册表从 `minecraft:air` 开始）。 */
    public val stateId: Int,
)
