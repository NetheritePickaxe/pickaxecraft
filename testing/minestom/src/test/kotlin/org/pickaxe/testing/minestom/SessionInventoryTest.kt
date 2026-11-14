package org.pickaxe.testing.minestom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.SessionState
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.CreativeItem
import org.pickaxe.protocol.inventory.DragButton
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import org.pickaxe.protocol.inventory.SlotClick
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 背包与容器：物品与组件、生命与装备、槽位点击、拖拽与创造模式。
 *
 * 槽位编号、组件补丁与服务端回执是三条独立的换算，用例都从客户端缓存读到结果、
 * 再由服务端的槽位快照或事件对账。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SessionInventoryTest : SessionTestBase() {

    @Test
    fun `背包与容器内容可从会话读到`() = runBlocking {
        // 背包有两套编号（窗口槽位与背包槽位），容器窗口又在自己内容后面接上玩家背包。
        // 这里按“服务端放的物品应出现在哪个槽位”逐格对账，而不是只看列表非空。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, BAG_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("背包与容器内容可从会话读到") { it is ClientEvent.ChunksReceived && it.total > 0 }
                // 出生时服务端就下发窗口 0 的整包内容：背包不是“打开才有”。
                awaitCondition("背包与容器内容可从会话读到", EVENT_TIMEOUT_MILLIS) { session.inventory() != null }
                assertTrue(assertNotNull(session.inventory()).isPlayerInventory, "窗口 0 就是玩家背包")

                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(BAG_USERNAME),
                    "服务端应已保存该玩家",
                )

                // Minestom 的槽位 0 是快捷栏第一格，协议窗口里是 36：中间那次换算是本层的责任。
                player.inventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 3))
                awaitCondition("背包与容器内容可从会话读到", EVENT_TIMEOUT_MILLIS) {
                    val item = session.inventory()?.slot(PlayerInventoryLayout.HOTBAR_FIRST)
                    item?.itemId == Material.DIAMOND.id() && item.count == 3
                }

                // 打开箱子：类型与标题来自打开屏幕包，内容随后整包下发。
                val chest = Inventory(InventoryType.CHEST_3_ROW, Component.text(CHEST_TITLE))
                chest.setItemStack(0, ItemStack.of(Material.GOLD_INGOT, 2))
                player.openInventory(chest)

                log.await("背包与容器内容可从会话读到") { it is ClientEvent.ContainerOpened }
                val opened = log.snapshot().filterIsInstance<ClientEvent.ContainerOpened>().first()
                assertEquals(CHEST_TYPE, opened.type, "箱子的容器类型应原样来自服务端")
                assertEquals(CHEST_TITLE, opened.title, "标题应渲染成纯文本")

                awaitCondition("背包与容器内容可从会话读到", EVENT_TIMEOUT_MILLIS) {
                    session.container()?.slot(0)?.itemId == Material.GOLD_INGOT.id()
                }
                // 打开容器期间，玩家背包仍然从窗口 0 读：Minestom 的容器整包只带容器自己的
                // 27 格（原版会把后面的背包段一起发），因此把背包当成“容器窗口的一部分”
                // 在两种服务端上都不成立——窗口 0 才是玩家背包的权威来源。
                assertEquals(
                    Material.DIAMOND.id(),
                    session.inventory()?.slot(PlayerInventoryLayout.HOTBAR_FIRST)?.itemId,
                    "打开容器不应影响玩家背包",
                )

                // 单槽更新：箱子第 6 格放一个苹果，其他槽位必须保持不变。
                chest.setItemStack(5, ItemStack.of(Material.APPLE, 1))
                awaitCondition("背包与容器内容可从会话读到", EVENT_TIMEOUT_MILLIS) {
                    session.container()?.slot(5)?.itemId == Material.APPLE.id()
                }
                assertEquals(Material.GOLD_INGOT.id(), session.container()?.slot(0)?.itemId, "单槽更新不应清掉别的格子")

                player.closeInventory()
                log.await("背包与容器内容可从会话读到") { it is ClientEvent.ContainerClosed }
                awaitCondition("背包与容器内容可从会话读到", EVENT_TIMEOUT_MILLIS) { session.container() == null }
                assertNotNull(session.inventory(), "关掉箱子不影响玩家自己的背包")

                // 手持槽位：服务端接受时不回包，本地立即推进；服务端字段是这次上行的证据。
                session.selectHotbarSlot(HELD_SLOT)
                log.await("背包与容器内容可从会话读到") { it is ClientEvent.HeldSlotChanged && it.slot == HELD_SLOT }
                assertEquals(HELD_SLOT, session.heldSlot, "接受无回执，本地在发送后立即推进")
                awaitCondition("背包与容器内容可从会话读到", EVENT_TIMEOUT_MILLIS) { player.heldSlot.toInt() == HELD_SLOT }
                assertNull(session.heldItem(), "第 $HELD_SLOT 格此时是空的")

                player.inventory.setItemStack(HELD_SLOT, ItemStack.of(Material.STONE, 7))
                awaitCondition("背包与容器内容可从会话读到", EVENT_TIMEOUT_MILLIS) {
                    val held = session.heldItem()
                    held?.itemId == Material.STONE.id() && held.count == 7
                }

                assertFailsWith<IllegalArgumentException> {
                    session.selectHotbarSlot(PlayerInventoryLayout.HOTBAR_SIZE)
                }
                assertEquals(SessionState.PLAY, session.state.value, "校验失败后会话应仍在 play")
            } finally {
                log.close()
                session.close()
            }

            assertNull(session.inventory(), "关闭后不应继续持有背包")
            val rejected = assertFailsWith<IllegalStateException> { session.selectHotbarSlot(0) }
            assertTrue(
                "CLOSED" in rejected.message.orEmpty() && "快捷栏" in rejected.message.orEmpty(),
                "拒绝原因应说明当前状态与操作，实际为 ${rejected.message}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `生命、饥饿、经验与装备对客户端可见`() = runBlocking {
        // 三件事各走独立的路：生命/饥饿一个包、经验一个包、装备一个包。
        // 装备能读到，靠的是它是独立包——不像实体元数据那样依赖实体层级。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, STATE_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("生命、饥饿、经验与装备对客户端可见") { it is ClientEvent.ChunksReceived && it.total > 0 }
                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(STATE_USERNAME),
                    "服务端应已保存该玩家",
                )

                player.setHealth(STATE_HEALTH)
                player.setFood(STATE_FOOD)
                player.setFoodSaturation(STATE_SATURATION)

                awaitCondition("生命、饥饿、经验与装备对客户端可见", EVENT_TIMEOUT_MILLIS) {
                    val status = session.status.value
                    status.health == STATE_HEALTH &&
                        status.food == STATE_FOOD &&
                        status.foodSaturation == STATE_SATURATION
                }
                log.await("生命、饥饿、经验与装备对客户端可见") { it is ClientEvent.StatusChanged && it.status.health == STATE_HEALTH }

                player.setLevel(STATE_LEVEL)
                player.setExp(STATE_EXPERIENCE_BAR)
                awaitCondition("生命、饥饿、经验与装备对客户端可见", EVENT_TIMEOUT_MILLIS) {
                    val status = session.status.value
                    status.level == STATE_LEVEL && status.experienceBar == STATE_EXPERIENCE_BAR
                }
                // 经验包里的总量由服务端决定；Minestom 恒发 0，这里只断言“读得到”，不假设数值。
                assertNotNull(session.status.value.totalExperience, "经验包应带总量字段")
                assertEquals(STATE_HEALTH, session.status.value.health, "经验包不应影响生命值")

                // 装备：独立包，进实体快照；清空时槽位应当消失而不是留下“数量 0 的物品”。
                val zombie = EntityCreature(EntityType.ZOMBIE)
                zombie.setNoGravity(true)
                zombie.setInstance(instance, Pos(6.5, GROUND_LEVEL, 6.5)).join()
                awaitCondition("生命、饥饿、经验与装备对客户端可见", EVENT_TIMEOUT_MILLIS) { session.entity(zombie.entityId) != null }

                zombie.setEquipment(EquipmentSlot.HELMET, ItemStack.of(Material.GOLDEN_HELMET))
                awaitCondition("生命、饥饿、经验与装备对客户端可见", EVENT_TIMEOUT_MILLIS) {
                    session.entity(zombie.entityId)?.equipment?.get(EQUIPMENT_HELMET)?.itemId ==
                        Material.GOLDEN_HELMET.id()
                }
                assertEquals(
                    1,
                    session.entity(zombie.entityId)?.equipment?.get(EQUIPMENT_HELMET)?.count,
                    "装备的物品堆数量应为 1",
                )

                zombie.setEquipment(EquipmentSlot.HELMET, ItemStack.AIR)
                awaitCondition("生命、饥饿、经验与装备对客户端可见", EVENT_TIMEOUT_MILLIS) {
                    session.entity(zombie.entityId)?.equipment?.containsKey(EQUIPMENT_HELMET) == false
                }
                zombie.remove()
            } finally {
                log.close()
                session.close()
            }

            assertNull(session.status.value.health, "关闭后不应继续持有生命状态")
        } finally {
            client.close()
        }
    }

    @Test
    fun `容器点击能把物品搬到背包`() = runBlocking {
        // 点击不做本地预测：每一步都以“服务端事实 + 服务端回给客户端的内容”双侧对账。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, CLICK_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("容器点击能把物品搬到背包") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) { session.inventory() != null }

                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(CLICK_USERNAME),
                    "服务端应已保存该玩家",
                )

                val chest = Inventory(InventoryType.CHEST_3_ROW, Component.text(CLICK_CHEST_TITLE))
                chest.setItemStack(CLICK_SLOT, ItemStack.of(Material.GOLD_INGOT, GOLD_COUNT))
                chest.setItemStack(SHIFT_SLOT, ItemStack.of(Material.APPLE, APPLE_COUNT))
                chest.setItemStack(THROW_SLOT, ItemStack.of(Material.DIRT, DIRT_COUNT))
                player.openInventory(chest)

                log.await("容器点击能把物品搬到背包") { it is ClientEvent.ContainerOpened }
                val windowId = log.snapshot().filterIsInstance<ClientEvent.ContainerOpened>().first().windowId
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) {
                    session.container(windowId)?.slot(CLICK_SLOT)?.itemId == Material.GOLD_INGOT.id()
                }

                // ① 左键点箱子第 0 格：整堆到光标上。光标由服务端回给本端（本端不预测）。
                session.clickSlot(windowId, CLICK_SLOT, SlotClick.Pickup())
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) {
                    session.container(windowId)?.cursor?.itemId == Material.GOLD_INGOT.id()
                }
                assertEquals(GOLD_COUNT, session.container(windowId)?.cursor?.count, "左键拿整堆")
                assertNull(session.container(windowId)?.slot(CLICK_SLOT), "箱子该格应已空")
                assertEquals(windowId, session.activeWindowId, "开着箱子时活动窗口就是它")

                // ② 再左键点窗口里的快捷栏第一格（3 行箱子：27 格容器 + 27 格背包之后）：物品落进背包。
                session.clickSlot(windowId, CHEST_HOTBAR_FIRST_SLOT, SlotClick.Pickup())
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slot(PlayerInventoryLayout.HOTBAR_FIRST)?.itemId ==
                        Material.GOLD_INGOT.id()
                }
                assertEquals(
                    GOLD_COUNT,
                    session.inventory()?.slot(PlayerInventoryLayout.HOTBAR_FIRST)?.count,
                    "背包槽位（36）拿到整堆",
                )
                assertEquals(
                    GOLD_COUNT,
                    player.inventory.getItemStack(0).amount(),
                    "服务端背包第 0 格与客户端第 36 格是同一格",
                )
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) { session.container(windowId)?.cursor == null }

                // ③ Shift 点击：服务端自己决定落到背包哪一格，本端只认结果。
                // 一次点击会改两个容器，服务端先发背包整包、再发容器整包，两边都要等，不能只看一半。
                session.clickSlot(windowId, SHIFT_SLOT, SlotClick.QuickMove())
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slots?.any { it?.itemId == Material.APPLE.id() } == true
                }
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) { session.container(windowId)?.slot(SHIFT_SLOT) == null }
                assertEquals(
                    APPLE_COUNT,
                    session.inventory()?.slots?.firstOrNull { it?.itemId == Material.APPLE.id() }?.count,
                    "Shift 点击整堆转移，数量不变",
                )
                assertTrue(chest.getItemStack(SHIFT_SLOT).isAir, "服务端箱子里这一格也应已空")

                // ④ 丢弃整堆：箱子这一格清空（掉落物实体不在本用例的断言范围）。
                session.clickSlot(windowId, THROW_SLOT, SlotClick.Throw(wholeStack = true))
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) { session.container(windowId)?.slot(THROW_SLOT) == null }
                assertTrue(chest.getItemStack(THROW_SLOT).isAir, "服务端箱子里这一格应已空")

                // ⑤ 关闭窗口在发出时就生效（服务端接受时不回包），事件因此早于服务端处理到达；
                // 服务端侧的状态要等一个来回。
                session.closeContainer(windowId)
                log.await("容器点击能把物品搬到背包") { it is ClientEvent.ContainerClosed && it.windowId == windowId }
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) { player.getOpenInventory() == null }
                awaitCondition("容器点击能把物品搬到背包", EVENT_TIMEOUT_MILLIS) { session.container() == null }
                assertNotNull(session.inventory(), "关掉箱子不影响玩家自己的背包")
                assertEquals(Container.PLAYER_WINDOW_ID, session.activeWindowId)

                // 越界槽位与已经不存在的窗口都在本地被挡下，不会发出坏包。
                assertFailsWith<IllegalArgumentException> {
                    session.clickSlot(PlayerInventoryLayout.SIZE, SlotClick.Pickup())
                }
                assertFailsWith<IllegalArgumentException> { session.clickSlot(-1, SlotClick.Pickup()) }
                assertFailsWith<IllegalStateException> { session.clickSlot(windowId, 0, SlotClick.Pickup()) }
                assertEquals(SessionState.PLAY, session.state.value, "校验失败后会话应仍在 play")
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `物品键名与自定义名可读`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, ITEM_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("物品键名与自定义名可读") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("物品键名与自定义名可读", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
                assertTrue(
                    session.inventory()?.slots?.all { it == null } == true,
                    "测试开始时背包应为空",
                )

                // 服务端塞物品：Minestom 会把背包槽更新下发给客户端。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        giveNamedItem(ITEM_USERNAME, Material.STONE, 16, NAMED_ITEM_NAME) &&
                            giveNamedItem(ITEM_USERNAME, Material.DIAMOND, 1, null)
                    },
                    "服务端应能塞进物品",
                )

                awaitCondition("物品键名与自定义名可读", EVENT_TIMEOUT_MILLIS) {
                    val slots = session.inventory()?.slots.orEmpty()
                    slots.any { it?.key == "minecraft:stone" && it.count == 16 && it.customName == NAMED_ITEM_NAME } &&
                        slots.any { it?.key == "minecraft:diamond" && it.customName == null }
                }
                val stone = session.inventory()?.slots.orEmpty()
                    .first { it?.key == "minecraft:stone" }
                assertEquals(NAMED_ITEM_NAME, stone?.customName, "custom_name 组件应穿过协议")
                val diamond = session.inventory()?.slots.orEmpty()
                    .first { it?.key == "minecraft:diamond" }
                assertNull(diamond?.customName, "没有组件就没有名字，不应伪造空串")
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `物品组件可读且附魔键名来自动态注册表`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, COMPONENT_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("物品组件可读且附魔键名来自动态注册表") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("物品组件可读且附魔键名来自动态注册表", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
                assertTrue(
                    withContext(Dispatchers.IO) { giveComponentItem(COMPONENT_USERNAME) },
                    "服务端应把组件齐全的剑放进背包",
                )
                awaitCondition("物品组件可读且附魔键名来自动态注册表", EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slots.orEmpty().any {
                        it?.key == "minecraft:diamond_sword" && it.customName == COMPONENT_SWORD_NAME
                    }
                }
                val sword = assertNotNull(
                    session.inventory()?.slots.orEmpty()
                        .firstOrNull { it?.key == "minecraft:diamond_sword" },
                )
                assertEquals(COMPONENT_ITEM_NAME, sword.itemName)
                assertEquals(listOf("第一行", "第二行"), sword.lore)
                assertEquals(COMPONENT_DAMAGE, sword.damage)
                assertEquals(COMPONENT_MAX_DAMAGE, sword.maxDamage)
                assertTrue(sword.unbreakable, "unbreakable 是 Unit 组件，存在即为真")
                assertEquals(COMPONENT_REPAIR_COST, sword.repairCost)
                assertEquals(
                    mapOf("minecraft:sharpness" to 3),
                    sword.enchantments,
                    "附魔 wire id 应由配置阶段下发的注册表还原成键名",
                )
                assertTrue(sword.storedEnchantments.isEmpty(), "普通武器不该有附魔书组件")
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `物品原型默认值可读`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, PROTOTYPE_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("物品原型默认值可读") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("物品原型默认值可读", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }
                // 三件物品都不设任何组件：服务端只会下发 id 与数量，耐久与堆叠上限只能由
                // 客户端查自己的物品原型表补齐。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        givePlainItem(PROTOTYPE_USERNAME, Material.DIAMOND_SWORD, 1) &&
                            givePlainItem(PROTOTYPE_USERNAME, Material.STONE, 3) &&
                            givePlainItem(PROTOTYPE_USERNAME, Material.ENDER_PEARL, 2)
                    },
                    "服务端应把三件物品放进背包",
                )
                awaitCondition("物品原型默认值可读", EVENT_TIMEOUT_MILLIS) {
                    val keys = session.inventory()?.slots.orEmpty().mapNotNull { it?.key }.toSet()
                    keys.containsAll(PROTOTYPE_ITEM_KEYS)
                }
                val slots = session.inventory()?.slots.orEmpty()
                val sword = assertNotNull(slots.firstOrNull { it?.key == "minecraft:diamond_sword" })
                assertEquals(PROTOTYPE_SWORD_MAX_DAMAGE, sword.maxDamage, "钻石剑的耐久来自原型表，不是补丁")
                assertEquals(1, sword.maxStackSize)
                val stone = assertNotNull(slots.firstOrNull { it?.key == "minecraft:stone" })
                assertNull(stone.maxDamage, "石头不可损坏，不该拿 0 或原型值兜底")
                assertEquals(64, stone.maxStackSize)
                val pearl = assertNotNull(slots.firstOrNull { it?.key == "minecraft:ender_pearl" })
                assertNull(pearl.maxDamage)
                assertEquals(16, pearl.maxStackSize)
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `拖拽与创造模式按服务端权威对账`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, CREATIVE_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("拖拽与创造模式按服务端权威对账") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                // 门控：模式未知或不是创造时，创造写入 API 必须本地拒绝而不是发出去被忽略。
                assertFailsWith<IllegalStateException> {
                    session.setCreativeSlot(DRAG_SOURCE_SLOT, CreativeItem(STONE_ITEM_ID, 1))
                }
                assertFailsWith<IllegalStateException> { session.grabMaxStack(DRAG_SOURCE_SLOT) }

                // 切创造：Minestom 广播玩家列表的 UPDATE_GAME_MODE（本人也在内），
                // gameMode 应随之刷新——登录快照不会自己变。
                assertTrue(
                    withContext(Dispatchers.IO) { setGameMode(CREATIVE_USERNAME, "CREATIVE") },
                    "服务端应能把玩家切成创造模式",
                )
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) { session.gameMode.value == "CREATIVE" }

                // 创造写入：快捷栏第 0 格放 12 块石头；服务端事件与客户端单槽更新双侧对账。
                session.setCreativeSlot(DRAG_SOURCE_SLOT, CreativeItem(STONE_ITEM_ID, 12))
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitCreativeAction(CREATIVE_USERNAME, DRAG_SOURCE_SLOT, MILESTONE_TIMEOUT)
                    },
                    "服务端应收到对源槽位的创造写入",
                )
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slots?.getOrNull(DRAG_SOURCE_SLOT)?.count == 12
                }

                // 左键拖拽均分：拿起 12 个，拖到 3 格，每格 4 个；客户端与服务端背包对账。
                session.clickSlot(DRAG_SOURCE_SLOT, SlotClick.Pickup())
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) { session.cursorItem()?.count == 12 }
                session.drag(listOf(DRAG_TARGET_FIRST, DRAG_TARGET_FIRST + 1, DRAG_TARGET_FIRST + 2), DragButton.LEFT)
                assertTrue(
                    withContext(Dispatchers.IO) { awaitPlayerPacket(CREATIVE_USERNAME, "drag.2", MILESTONE_TIMEOUT) },
                    "服务端应收到左键拖拽的 end 包",
                )
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) {
                    val slots = session.inventory()?.slots.orEmpty()
                    (0..2).all { slots.getOrNull(DRAG_TARGET_FIRST + it)?.count == 4 }
                }
                assertNull(session.cursorItem(), "光标应被均分完")
                for (i in 0..2) {
                    val serverItem = withContext(Dispatchers.IO) {
                        serverInventorySlot(CREATIVE_USERNAME, DRAG_TARGET_FIRST + i)
                    }
                    assertEquals(4, serverItem?.amount(), "服务端第 $i 格应分到 4 个石头")
                }

                // 右键拖拽每槽 1 个：换两格干净的槽，光标剩 5 个。
                session.setCreativeSlot(DRAG_SOURCE_SLOT, CreativeItem(STONE_ITEM_ID, 7))
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slots?.getOrNull(DRAG_SOURCE_SLOT)?.count == 7
                }
                session.clickSlot(DRAG_SOURCE_SLOT, SlotClick.Pickup())
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) { session.cursorItem()?.count == 7 }
                session.drag(listOf(DRAG_TARGET_FIRST + 3, DRAG_TARGET_FIRST + 4), DragButton.RIGHT)
                assertTrue(
                    withContext(Dispatchers.IO) { awaitPlayerPacket(CREATIVE_USERNAME, "drag.6", MILESTONE_TIMEOUT) },
                    "服务端应收到右键拖拽的 end 包",
                )
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) { session.cursorItem()?.count == 5 }
                assertEquals(1, session.inventory()?.slots?.getOrNull(DRAG_TARGET_FIRST + 3)?.count)
                assertEquals(1, session.inventory()?.slots?.getOrNull(DRAG_TARGET_FIRST + 4)?.count)
                assertEquals(
                    1,
                    withContext(Dispatchers.IO) {
                        serverInventorySlot(CREATIVE_USERNAME, DRAG_TARGET_FIRST + 3)?.amount()
                    },
                    "服务端该格应各分到 1 个",
                )

                // 创造取整堆：Minestom 的 middleClick 是 TODO 空桩（两个实现都返回 false），
                // 端到端结算无法对账——动作映射由 ContainerClicksTest 钉住，门控在上面已验证，
                // 这里不再断言一个上游没实现的行为。

                // 清空：null 写入后，客户端与服务端的这一格都变回空。
                session.setCreativeSlot(DRAG_TARGET_FIRST + 6, CreativeItem(STONE_ITEM_ID, 3))
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slots?.getOrNull(DRAG_TARGET_FIRST + 6)?.count == 3
                }
                session.setCreativeSlot(DRAG_TARGET_FIRST + 6, null)
                awaitCondition("拖拽与创造模式按服务端权威对账", EVENT_TIMEOUT_MILLIS) {
                    session.inventory()?.slots?.getOrNull(DRAG_TARGET_FIRST + 6) == null
                }
                assertTrue(
                    withContext(Dispatchers.IO) {
                        serverInventorySlot(CREATIVE_USERNAME, DRAG_TARGET_FIRST + 6)?.isAir ?: false
                    },
                    "服务端该格也应被清空",
                )
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `容器数值属性可读且多个下标互不覆盖`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, PROPERTY_USERNAME)
            val log = EventLog(this, session)
            try {
                assertTrue(awaitMilestone(PROPERTY_USERNAME, PlayerMilestone.SPAWNED, MILESTONE_TIMEOUT))

                sendWindowProperty(PROPERTY_USERNAME, Container.PLAYER_WINDOW_ID, FURNACE_PROGRESS, 75)
                log.await("容器数值属性可读且多个下标互不覆盖") {
                    it is ClientEvent.ContainerPropertyChanged &&
                        it.windowId == Container.PLAYER_WINDOW_ID &&
                        it.property == FURNACE_PROGRESS && it.value == 75
                }
                assertEquals(75, session.containerProperties(Container.PLAYER_WINDOW_ID)[FURNACE_PROGRESS])

                sendWindowProperty(PROPERTY_USERNAME, Container.PLAYER_WINDOW_ID, FURNACE_TOTAL, 200)
                awaitCondition("容器数值属性可读且多个下标互不覆盖", EVENT_TIMEOUT_MILLIS) {
                    session.containerProperties(Container.PLAYER_WINDOW_ID).size == 2
                }
                assertEquals(75, session.containerProperties(Container.PLAYER_WINDOW_ID)[FURNACE_PROGRESS])
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }
}
