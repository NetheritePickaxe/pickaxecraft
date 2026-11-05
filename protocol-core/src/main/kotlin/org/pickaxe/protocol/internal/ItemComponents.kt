package org.pickaxe.protocol.internal

import net.kyori.adventure.text.Component
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.item.ItemKeys

/**
 * 把协议里的 `ItemStack` 完整转换成公共物品快照。
 *
 * 物品 id → 键名靠 [ItemKeys]（协议不下发物品注册表），附魔 id → 键名则靠配置阶段下发的
 * `minecraft:enchantment` 注册表：附魔是可由服务端扩展的**动态**注册表，顺序不是版本常量，
 * 固化一张表反而会错。注册表条目顺序就是 wire id。
 *
 * `dataComponentsPatch` 是补丁而不是完整组件表：某个键「带了但值为 null」表示**删除**该组件，
 * 「不带这个键」表示没提。两者的对外结果一致（都回落到默认值），所以用 `get` 而不是 `contains`
 * 就能同时处理，不需要区分。
 */
internal object ItemComponents {

    const val ENCHANTMENT_REGISTRY_ID: String = "minecraft:enchantment"

    /** 条目顺序即 wire id，不能排序或过滤。 */
    fun enchantmentKeys(entries: List<RegistryEntry>): List<String> = entries.map { it.id.asString() }

    fun read(stack: ItemStack, enchantmentKeys: List<String>): InventoryItem {
        val patch = stack.dataComponentsPatch
        return InventoryItem(
            itemId = stack.id,
            count = stack.amount,
            key = ItemKeys.key(stack.id),
            customName = patch.text(DataComponentTypes.CUSTOM_NAME),
            itemName = patch.text(DataComponentTypes.ITEM_NAME),
            lore = patch?.get(DataComponentTypes.LORE)?.map(ComponentText::text) ?: emptyList(),
            damage = patch?.get(DataComponentTypes.DAMAGE),
            maxDamage = patch?.get(DataComponentTypes.MAX_DAMAGE),
            unbreakable = patch?.get(DataComponentTypes.UNBREAKABLE) != null,
            repairCost = patch?.get(DataComponentTypes.REPAIR_COST),
            enchantments = patch.enchantments(DataComponentTypes.ENCHANTMENTS, enchantmentKeys),
            storedEnchantments = patch.enchantments(DataComponentTypes.STORED_ENCHANTMENTS, enchantmentKeys),
        )
    }

    private fun DataComponents?.text(type: DataComponentType<Component>): String? =
        this?.get(type)?.let(ComponentText::text)

    private fun DataComponents?.enchantments(
        type: DataComponentType<ItemEnchantments>,
        keys: List<String>,
    ): Map<String, Int> {
        val value = this?.get(type) ?: return emptyMap()
        // id 落在注册表之外时保留数字，不静默丢掉这一条附魔。
        return value.enchantments.mapKeys { (id, _) -> keys.getOrNull(id) ?: "unknown:$id" }
    }
}
