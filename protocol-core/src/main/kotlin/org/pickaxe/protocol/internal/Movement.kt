package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket

/**
 * 本端声明的位置与朝向。
 *
 * 这是**客户端认为自己在哪**，不是服务端确认的坐标：服务端接受移动时不会回包，
 * 因此本地状态只能由本端维护；服务端不同意时下发位置同步或传送包，把状态改回来。
 *
 * 不可变，因此可以整体替换：网络线程（服务端同步）与调用线程（[Movement.packet] 的
 * 比较基准）都能安全读，最后写入者胜出——这与真实客户端面对延迟时的行为一致。
 */
internal data class LocalPose(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
)

/**
 * 移动上行的校验与最小包选择。
 *
 * 协议有三个只带部分字段的移动包（仅坐标、仅朝向、全部），服务端按“没带的字段沿用上一状态”
 * 解释它们。所以发送前必须知道上一状态是什么：**同一个包不能凭调用方给的坐标单独构造**，
 * 否则会变成“只发朝向却期望坐标一起改”。这里把“与上一状态比较、挑最小的包”的逻辑独立出来，
 * 既不依赖网络也不依赖会话，可以直接单测。
 *
 * 选择规则（服务端语义对齐）：
 * - 坐标与朝向都变了 → 一个 `pos_rot` 包，不拆成两个；
 * - 只变坐标 → `pos`；只变朝向 → `rot`；
 * - 只有落地标记变了 → `status_only`（协议里确实存在这个包，专门用来报状态）；
 * - 完全没变 → 不发包。重复发送相同坐标会被服务端忽略，但那是无谓的流量。
 */
internal object Movement {

    /**
     * 校验坐标。
     *
     * 只要求有限值：NaN 与无穷会直接让服务端的距离校验得出错误结论（Minestom 在
     * 这类坐标上会踢人），而“坐标太远该不该拒绝”是服务端的判断，本层不设任意上限。
     *
     * @throws IllegalArgumentException 任一坐标为 NaN 或无穷
     */
    fun requirePosition(x: Double, y: Double, z: Double) {
        require(x.isFinite()) { "坐标 x 必须是有限值，实际为 $x" }
        require(y.isFinite()) { "坐标 y 必须是有限值，实际为 $y" }
        require(z.isFinite()) { "坐标 z 必须是有限值，实际为 $z" }
    }

    /**
     * 校验朝向，单位是度。
     *
     * 角度不做归一化：协议按 float 原样传，服务端自己取模。这里只挡 NaN 与无穷。
     *
     * @throws IllegalArgumentException 任一角度为 NaN 或无穷
     */
    fun requireAngles(yaw: Float, pitch: Float) {
        require(yaw.isFinite()) { "朝向 yaw 必须是有限值，实际为 $yaw" }
        require(pitch.isFinite()) { "俯仰 pitch 必须是有限值，实际为 $pitch" }
    }

    /**
     * 与上一状态比较后返回最小的那个包；状态完全一致时返回 null。
     *
     * `horizontalCollision` 恒为 false：无头客户端不做碰撞检测，报告 true 等于向服务端
     * 声明“我撞到了东西”，会让它替本端减速。不知道的事就不报。
     */
    fun packet(current: LocalPose, target: LocalPose): Packet? {
        val moved = current.x != target.x || current.y != target.y || current.z != target.z
        val turned = current.yaw != target.yaw || current.pitch != target.pitch
        return when {
            moved && turned -> ServerboundMovePlayerPosRotPacket(
                target.onGround,
                false,
                target.x,
                target.y,
                target.z,
                target.yaw,
                target.pitch,
            )
            moved -> ServerboundMovePlayerPosPacket(target.onGround, false, target.x, target.y, target.z)
            turned -> ServerboundMovePlayerRotPacket(target.onGround, false, target.yaw, target.pitch)
            current.onGround != target.onGround -> ServerboundMovePlayerStatusOnlyPacket(target.onGround, false)
            else -> null
        }
    }
}
