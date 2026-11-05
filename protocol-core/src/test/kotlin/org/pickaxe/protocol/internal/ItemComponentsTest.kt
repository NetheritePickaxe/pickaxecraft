package org.pickaxe.protocol.internal

import net.kyori.adventure.text.Component
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Unit as McplUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemComponentsTest {

    private val enchantmentKeys = listOf(
        "minecraft:sharpness",
        "minecraft:unbreaking",
        "minecraft:mending",
    )

    @Test
    fun `常用组件按补丁取值`() {
        val patch = DataComponents(HashMap()).apply {
            put(DataComponentTypes.CUSTOM_NAME, Component.text("试金石"))
            put(DataComponentTypes.ITEM_NAME, Component.text("石头"))
            put(DataComponentTypes.LORE, listOf(Component.text("第一行"), Component.text("第二行")))
            put(DataComponentTypes.DAMAGE, 7)
            put(DataComponentTypes.MAX_DAMAGE, 1561)
            put(DataComponentTypes.REPAIR_COST, 2)
            put(DataComponentTypes.UNBREAKABLE, McplUnit.INSTANCE)
            put(DataComponentTypes.ENCHANTMENTS, ItemEnchantments(mapOf(0 to 3, 2 to 1)))
            put(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantments(mapOf(1 to 2)))
        }

        val item = ItemComponents.read(ItemStack(1, 3, patch), enchantmentKeys)
        assertEquals(1, item.itemId)
        assertEquals(3, item.count)
        assertEquals("minecraft:stone", item.key)
        assertEquals("试金石", item.customName)
        assertEquals("石头", item.itemName)
        assertEquals(listOf("第一行", "第二行"), item.lore)
        assertEquals(7, item.damage)
        assertEquals(1561, item.maxDamage)
        assertTrue(item.unbreakable)
        assertEquals(2, item.repairCost)
        assertEquals(mapOf("minecraft:sharpness" to 3, "minecraft:mending" to 1), item.enchantments)
        assertEquals(mapOf("minecraft:unbreaking" to 2), item.storedEnchantments)
    }

    @Test
    fun `补丁里显式删除组件与缺省等价`() {
        val patch = DataComponents(HashMap()).apply {
            removeExplicitly(DataComponentTypes.CUSTOM_NAME)
            removeExplicitly(DataComponentTypes.UNBREAKABLE)
            removeExplicitly(DataComponentTypes.ENCHANTMENTS)
        }

        val item = ItemComponents.read(ItemStack(1, 1, patch), enchantmentKeys)
        assertNull(item.customName)
        assertFalse(item.unbreakable)
        assertTrue(item.enchantments.isEmpty())
    }

    @Test
    fun `附魔 id 越界时保留数字而不是丢数据`() {
        val patch = DataComponents(HashMap()).apply {
            put(DataComponentTypes.ENCHANTMENTS, ItemEnchantments(mapOf(9 to 4)))
        }

        assertEquals(
            mapOf("unknown:9" to 4),
            ItemComponents.read(ItemStack(1, 1, patch), enchantmentKeys).enchantments,
        )
    }

    @Test
    fun `没有组件补丁的物品字段全部为默认`() {
        val item = ItemComponents.read(ItemStack(1), enchantmentKeys)
        assertNull(item.customName)
        assertNull(item.itemName)
        assertTrue(item.lore.isEmpty())
        assertNull(item.damage)
        assertNull(item.maxDamage)
        assertFalse(item.unbreakable)
        assertNull(item.repairCost)
        assertTrue(item.enchantments.isEmpty())
        assertTrue(item.storedEnchantments.isEmpty())
    }

    /** 补丁里「带键但值为 null」才是删除；直接写 null 会走协议库的 null 组件工厂。 */
    private fun DataComponents.removeExplicitly(type: DataComponentType<*>) {
        @Suppress("UNCHECKED_CAST")
        dataComponents[type] = type.readNullDataComponent()
    }
}
