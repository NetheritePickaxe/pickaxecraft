package org.pickaxe.protocol.internal

import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand as ProtocolHand
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction
import org.pickaxe.protocol.inventory.Hand
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 方块交互到协议字段的映射。
 *
 * 与点击（`ContainerClicksTest`）同理：面、坐标位宽、光标范围都是「错了不报编译错、
 * 也不一定被服务端当场拒绝」的字段——坐标越界会被按位截断成另一个坐标，表现为
 * “挖了没反应”或“挖错了方块”，在联调日志里几乎无法倒推。
 */
class BlockInteractionTest {

    @Test
    fun `六个面的映射与协议序号一致`() {
        // 协议按枚举序号编码：DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5。
        // 顺序写错不会编译失败，只会“点了东面朝西面放”，因此逐面钉住。
        assertEquals(BlockFace.DOWN, BlockFace.entries[0])
        assertEquals(BlockFace.UP, BlockFace.entries[1])
        assertEquals(BlockFace.NORTH, BlockFace.entries[2])
        assertEquals(BlockFace.SOUTH, BlockFace.entries[3])
        assertEquals(BlockFace.WEST, BlockFace.entries[4])
        assertEquals(BlockFace.EAST, BlockFace.entries[5])

        for (face in BlockFace.entries) {
            assertEquals(face.ordinal, BlockInteraction.direction(face).ordinal)
        }
    }

    @Test
    fun `相对面互为相反`() {
        for (face in BlockFace.entries) {
            assertEquals(face, face.opposite.opposite)
        }
        assertEquals(BlockFace.UP, BlockFace.DOWN.opposite)
        assertEquals(BlockFace.EAST, BlockFace.WEST.opposite)
    }

    @Test
    fun `放置落点是点中方块加上面的方向`() {
        val target = BlockPosition(10, 64, -5)
        assertEquals(BlockPosition(10, 65, -5), target.offset(BlockFace.UP))
        assertEquals(BlockPosition(10, 63, -5), target.offset(BlockFace.DOWN))
        assertEquals(BlockPosition(10, 64, -6), target.offset(BlockFace.NORTH))
        assertEquals(BlockPosition(10, 64, -4), target.offset(BlockFace.SOUTH))
        assertEquals(BlockPosition(9, 64, -5), target.offset(BlockFace.WEST))
        assertEquals(BlockPosition(11, 64, -5), target.offset(BlockFace.EAST))
    }

    @Test
    fun `坐标超出协议位宽被拒绝而不是被截断`() {
        // x/z 是 26 位有符号数，越界值会被编码器按位截断：挖 (0, 5000, 0) 变成挖 (0, 904, 0)，
        // 两边都不报错。必须在发送前拦住。
        assertFailsWith<IllegalArgumentException> {
            BlockInteraction.requirePosition(BlockPosition(33_554_432, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            BlockInteraction.requirePosition(BlockPosition(-33_554_433, 0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            BlockInteraction.requirePosition(BlockPosition(0, 2_048, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            BlockInteraction.requirePosition(BlockPosition(0, 0, -33_554_433))
        }
        // 边界值合法。
        BlockInteraction.requirePosition(BlockPosition(33_554_431, 2_047, -33_554_432))
    }

    @Test
    fun `光标位置必须在方块内`() {
        BlockInteraction.requireCursor(0f, 0.5f, 1f)
        assertFailsWith<IllegalArgumentException> { BlockInteraction.requireCursor(-0.01f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { BlockInteraction.requireCursor(0f, 0f, 1.01f) }
    }

    @Test
    fun `挖掘三段映射到三个协议动作`() {
        val position = BlockPosition(7, -3, 9)
        val start = BlockInteraction.digPacket(DigAction.START, position, BlockFace.NORTH, sequence = 4)
        assertEquals(PlayerAction.START_DIGGING, start.action)
        assertEquals(4, start.sequence)

        val cancel = BlockInteraction.digPacket(DigAction.CANCEL, position, BlockFace.NORTH, sequence = 5)
        assertEquals(PlayerAction.CANCEL_DIGGING, cancel.action)

        val finish = BlockInteraction.digPacket(DigAction.FINISH, position, BlockFace.NORTH, sequence = 6)
        assertEquals(PlayerAction.FINISH_DIGGING, finish.action)

        // 三段必须是同一组坐标与面：服务端按两者配对，中途改动等于一次新的动作。
        assertEquals(7, start.position.x)
        assertEquals(-3, start.position.y)
        assertEquals(9, start.position.z)
        assertEquals(BlockInteraction.direction(BlockFace.NORTH), start.face)
    }

    @Test
    fun `对方块使用物品的包带齐光标与手`() {
        val packet = BlockInteraction.useOnBlockPacket(
            position = BlockPosition(1, 2, 3),
            face = BlockFace.UP,
            cursorX = 0.25f,
            cursorY = 1f,
            cursorZ = 0.75f,
            hand = Hand.OFF_HAND,
            insideBlock = true,
            sequence = 9,
        )

        assertEquals(1, packet.position.x)
        assertEquals(2, packet.position.y)
        assertEquals(3, packet.position.z)
        assertEquals(BlockInteraction.direction(BlockFace.UP), packet.face)
        assertEquals(0.25f, packet.cursorX)
        assertEquals(1f, packet.cursorY)
        assertEquals(0.75f, packet.cursorZ)
        assertEquals(ProtocolHand.OFF_HAND, packet.hand)
        assertEquals(true, packet.isInsideBlock)
        // 世界边界是服务端的概念，本端不跟踪。
        assertEquals(false, packet.isHitWorldBorder)
        assertEquals(9, packet.sequence)
    }

    @Test
    fun `对空气使用物品的包带朝向`() {
        val packet = BlockInteraction.useItemPacket(Hand.MAIN_HAND, yaw = 91.5f, pitch = -12f, sequence = 2)

        assertEquals(ProtocolHand.MAIN_HAND, packet.hand)
        assertEquals(91.5f, packet.yRot)
        assertEquals(-12f, packet.xRot)
        assertEquals(2, packet.sequence)
    }
}
