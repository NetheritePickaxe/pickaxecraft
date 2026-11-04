package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 移动包选择。
 *
 * 这里断言的是**发哪个包**，而不是“发了包”：协议用三个只带部分字段的包表达移动，
 * 服务端对缺的字段沿用上一状态。选错包不会报错，只会表现为“朝向对了但人没动”或
 * “人动了但朝向被重置”——这类错误在联调里极难定位，所以在这一层用单测钉住。
 */
class MovementTest {

    private val base = LocalPose(x = 0.5, y = 40.0, z = 0.5, yaw = 0f, pitch = 0f, onGround = true)

    @Test
    fun `姿态完全一致时不发包`() {
        assertNull(Movement.packet(base, base.copy()))
    }

    @Test
    fun `只改坐标发坐标包`() {
        val packet = assertIs<ServerboundMovePlayerPosPacket>(
            Movement.packet(base, base.copy(x = 2.5, y = 41.0, z = 0.5)),
        )
        assertEquals(2.5, packet.x)
        assertEquals(41.0, packet.y)
        assertEquals(0.5, packet.z)
    }

    @Test
    fun `只改朝向发朝向包`() {
        val packet = assertIs<ServerboundMovePlayerRotPacket>(
            Movement.packet(base, base.copy(yaw = 90f, pitch = -15f)),
        )
        assertEquals(90f, packet.yaw)
        assertEquals(-15f, packet.pitch)
    }

    @Test
    fun `坐标与朝向同时变化合成一个包`() {
        // 拆成两个包也能动，但会让服务端看到两次自相矛盾的状态：先到的新坐标配旧朝向。
        val packet = assertIs<ServerboundMovePlayerPosRotPacket>(
            Movement.packet(base, base.copy(x = 3.0, yaw = 45f, pitch = 10f)),
        )
        assertEquals(3.0, packet.x)
        assertEquals(45f, packet.yaw)
        assertEquals(10f, packet.pitch)
    }

    @Test
    fun `只有落地标记变化时发状态包`() {
        val packet = assertIs<ServerboundMovePlayerStatusOnlyPacket>(
            Movement.packet(base, base.copy(onGround = false)),
        )
        assertEquals(false, packet.isOnGround)
    }

    @Test
    fun `落地标记随坐标一起上报`() {
        val packet = assertIs<ServerboundMovePlayerPosPacket>(
            Movement.packet(base, base.copy(y = 41.0, onGround = false)),
        )
        assertEquals(false, packet.isOnGround, "坐标包也带落地标记，不能丢掉这个状态")
    }

    @Test
    fun `永不声明水平碰撞`() {
        // 声明 true 会让服务端替本端减速；无头客户端不做碰撞检测，不知道的事不该报。
        val packet = assertIs<ServerboundMovePlayerPosRotPacket>(
            Movement.packet(base, base.copy(x = 1.5, yaw = 10f)),
        )
        assertEquals(false, packet.isHorizontalCollision)
    }

    @Test
    fun `非有限坐标被拒绝`() {
        assertFailsWith<IllegalArgumentException> { Movement.requirePosition(Double.NaN, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Movement.requirePosition(0.0, Double.POSITIVE_INFINITY, 0.0) }
        assertFailsWith<IllegalArgumentException> { Movement.requirePosition(0.0, 0.0, Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `非有限角度被拒绝`() {
        assertFailsWith<IllegalArgumentException> { Movement.requireAngles(Float.NaN, 0f) }
        assertFailsWith<IllegalArgumentException> { Movement.requireAngles(0f, Float.POSITIVE_INFINITY) }
        // 角度不做归一化：720 度是合法值，服务端自己取模。
        Movement.requireAngles(720f, -90f)
        assertTrue(true)
    }
}
