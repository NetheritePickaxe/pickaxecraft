package org.pickaxe.protocol.internal

import org.cloudburstmc.math.vector.Vector3i
// `object` 是 Kotlin 关键字，包名必须用反引号转义。
import org.geysermc.mcprotocollib.protocol.data.game.entity.`object`.Direction
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket
import org.pickaxe.protocol.inventory.Hand
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand as ProtocolHand

/** 挖掘的三段。协议里的枚举还有丢弃物品、交换副手等，那些不是“挖”的一部分。 */
internal enum class DigAction {
    START,
    CANCEL,
    FINISH,
    ;

    fun toProtocol(): PlayerAction = when (this) {
        START -> PlayerAction.START_DIGGING
        CANCEL -> PlayerAction.CANCEL_DIGGING
        FINISH -> PlayerAction.FINISH_DIGGING
    }
}

/**
 * 方块交互的包构造与校验。
 *
 * 单独成对象，是因为这一轮有三处“错了不会编译失败、只会在服务端静默无效”的映射：
 * 面的序号、坐标的位宽、光标位置的范围。把它们集中在一处并由单测钉住，比散在
 * [McplConnection] 的各个方法里可靠。
 */
internal object BlockInteraction {

    /** x / z 是 26 位有符号数：越界会被协议静默截断。 */
    const val MIN_HORIZONTAL: Int = -33_554_432
    const val MAX_HORIZONTAL: Int = 33_554_431

    /** y 是 12 位有符号数。 */
    const val MIN_VERTICAL: Int = -2_048
    const val MAX_VERTICAL: Int = 2_047

    /**
     * 校验方块坐标。
     *
     * 不做这一步的话，越界坐标会在编码时被按位截断：客户端以为自己挖了 `(0, 5000, 0)`，
     * 服务端收到的是 `(0, 904, 0)`，两边都没报错。这是唯一一处“范围错误必须提前拦住”的地方。
     */
    fun requirePosition(position: BlockPosition) {
        require(position.x in MIN_HORIZONTAL..MAX_HORIZONTAL && position.z in MIN_HORIZONTAL..MAX_HORIZONTAL) {
            "方块坐标超出协议范围（x、z 必须在 $MIN_HORIZONTAL..$MAX_HORIZONTAL 之间）：$position"
        }
        require(position.y in MIN_VERTICAL..MAX_VERTICAL) {
            "方块高度超出协议范围（y 必须在 $MIN_VERTICAL..$MAX_VERTICAL 之间）：$position"
        }
    }

    /**
     * 校验光标位置：点击点在目标方块内的相对坐标，三轴都在 0..1。
     *
     * 服务端用它判断“点到的是哪一面、哪一格”，越界会被当成非法包；默认值取方块中心。
     */
    fun requireCursor(x: Float, y: Float, z: Float) {
        require(x in 0f..1f && y in 0f..1f && z in 0f..1f) {
            "光标位置必须在 0..1 之间，实际为 ($x, $y, $z)"
        }
    }

    /** 公共枚举 → 协议方向。序号一致，但仍然显式写出来：将来任一侧加值时要在这里报错。 */
    fun direction(face: BlockFace): Direction = when (face) {
        BlockFace.DOWN -> Direction.DOWN
        BlockFace.UP -> Direction.UP
        BlockFace.NORTH -> Direction.NORTH
        BlockFace.SOUTH -> Direction.SOUTH
        BlockFace.WEST -> Direction.WEST
        BlockFace.EAST -> Direction.EAST
    }

    fun hand(hand: Hand): ProtocolHand = when (hand) {
        Hand.MAIN_HAND -> ProtocolHand.MAIN_HAND
        Hand.OFF_HAND -> ProtocolHand.OFF_HAND
    }

    /**
     * 挖掘包。
     *
     * [face] 是**被挖的那一面**：开始挖掘时客户端的准星贴着哪个面，服务端只把它当提示，
     * 但取消与完成都必须原样带回同一组坐标与面，否则服务端会认为这是两个不同的动作。
     */
    fun digPacket(
        action: DigAction,
        position: BlockPosition,
        face: BlockFace,
        sequence: Int,
    ): ServerboundPlayerActionPacket {
        requirePosition(position)
        return ServerboundPlayerActionPacket(
            action.toProtocol(),
            Vector3i.from(position.x, position.y, position.z),
            direction(face),
            sequence,
        )
    }

    /**
     * 对方块使用物品：右键点在某格上。
     *
     * 同一个包承担两件事——在主手拿着方块时是**放置**，在拿着工具/空手时是**交互**
     * （开箱子、按按钮、耕地）。服务端按手里的物品与目标方块的处理器决定，本端不判断：
     * 判断需要物品组件与方块行为表，那是下游的资产。
     *
     * `insideBlock` 表示玩家头部是否在目标方块内部（贴墙放置时才有意义），恒填 false 会让
     * 极少数“头在方块里”的放置被服务端拒绝；调用方有需要时可以传 true。
     */
    fun useOnBlockPacket(
        position: BlockPosition,
        face: BlockFace,
        cursorX: Float,
        cursorY: Float,
        cursorZ: Float,
        hand: Hand,
        insideBlock: Boolean,
        sequence: Int,
    ): ServerboundUseItemOnPacket {
        requirePosition(position)
        requireCursor(cursorX, cursorY, cursorZ)
        return ServerboundUseItemOnPacket(
            Vector3i.from(position.x, position.y, position.z),
            direction(face),
            hand(hand),
            cursorX,
            cursorY,
            cursorZ,
            insideBlock,
            // 世界边界是服务端的概念，本端不跟踪；恒 false 的效果是“交给服务端判断”。
            false,
            sequence,
        )
    }

    /**
     * 对着空气使用物品（吃、喝、投掷、拉弓）。
     *
     * 包里的朝向不是装饰：服务端拿它决定投掷物的初始速度方向。因此必须用**本端当前视角**，
     * 而不是固定 0。
     */
    fun useItemPacket(hand: Hand, yaw: Float, pitch: Float, sequence: Int): ServerboundUseItemPacket =
        ServerboundUseItemPacket(hand(hand), sequence, yaw, pitch)
}
