package org.pickaxe.protocol.entity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分层表是协议版本资产：升级时整表重导，锚点用来发现「名字对不上」这类静默错位。
 */
class LivingEntityTypesTest {

    @Test
    fun `生物、玩家与盔甲架走生物元数据层级`() {
        for (type in listOf("ZOMBIE", "PLAYER", "ARMOR_STAND", "ENDER_DRAGON", "MANNEQUIN")) {
            assertTrue(LivingEntityTypes.isLiving(type), "$type 应属于生物层级")
        }
    }

    @Test
    fun `非生物与未知类型不读血量下标`() {
        for (type in listOf("ITEM", "OAK_BOAT", "ARROW", "ITEM_DISPLAY", "INTERACTION", "UNKNOWN_TYPE", "")) {
            assertFalse(LivingEntityTypes.isLiving(type), "$type 不应被当成生物")
        }
    }
}
