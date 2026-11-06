# M4 收尾计划：物品原型、插件消息、拖拽与破坏进度

状态：待执行
日期：2026-11-06

M4 的功能面积已经很大（M4a–M4x），但还有四处没有闭合：一处是会读错数据的**真 bug**，
一处是 M4 验收清单里明写却完全没实现的能力，两处是交互链路上缺失的环节。本文把四项排成
增量，给出协议事实、公共 API 草案与验收标准，逐项执行。

## 0. 现状与依据

| 项 | 现状 | 依据 |
| --- | --- | --- |
| 物品组件默认值 | `InventoryItem.maxDamage` 只读补丁；钻石剑的 `max_damage` 是原型默认值，服务端不下发，因此读到 `null` | `ItemComponents.read` 只查 `dataComponentsPatch` |
| 堆叠上限 | 完全没有暴露 | `InventoryItem` 无 `maxStackSize` |
| 插件消息 | 收发都没有，`CustomPayload` 在本仓库没有任何引用；`roadmap.md` 的 M4 交付内容写明“聊天、插件消息和诊断日志” | `grep CustomPayload` 无结果 |
| 拖拽 | `SlotClick` 只有五种点击，没有拖拽 | `ContainerClicks.action` 无 `SPREAD_ITEM` |
| 创造模式 | 不能整堆拿取，也不能直接设置槽位内容 | 未使用 `CREATIVE_GRAB_MAX_STACK` / `set_creative_mode_slot` |
| 破坏进度 | 只处理自己挖掘的回执（`block_changed_ack`），其他玩家的挖掘裂纹不可见 | 未处理 `ClientboundBlockDestructionPacket` |

协议事实（已对 26.2 的协议库与 Minestom 核对）：

- `ContainerActionType` 有七项：`CLICK_ITEM`、`SHIFT_CLICK_ITEM`、`MOVE_TO_HOTBAR_SLOT`、
  `CREATIVE_GRAB_MAX_STACK`、`DROP_ITEM`、`SPREAD_ITEM`、`FILL_STACK`。前四项与后两项
  已分别由 M4j / M4j 的 `PickupAll` 覆盖，剩 `CREATIVE_GRAB_MAX_STACK` 与 `SPREAD_ITEM` 未用。
- `SpreadItemAction` 是 3 键 × 3 阶段的九项枚举（`LEFT_MOUSE_BEGIN_DRAG` / `ADD_SLOT` /
  `END_DRAG`，右键同构，中键同构）。`ServerboundContainerClickPacket` 的
  `CLICK_OUTSIDE_NOT_HOLDING_SLOT` 常量存在，begin/end 用它当槽位。
- `ServerboundSetCreativeModeSlotPacket(slot: Short, clickedItem: ItemStack)` 是创造模式
  直接设置槽位的唯一通道。
- `ClientboundBlockDestructionPacket(breakerEntityId, position, stage)`，
  `BlockBreakStage` 是 `STAGE_1..STAGE_10` 加 `RESET`。
- 双向插件消息：`ClientboundCustomPayloadPacket` / `ServerboundCustomPayloadPacket`，
  字段是 `channel: Key` 与 `data: ByteArray`。Minestom 侧对应
  `PlayerPluginMessageEvent(identifier, message)` 与 `Player#sendPluginMessage(String, byte[])`，
  harness 两条路都有。
- Minestom 的 `Material#prototype()` 返回 `DataComponentMap`，是物品原型默认组件的来源
  （与 `ItemKeys`、`AttributeTable` 同为版本资产）。

## 1. M4y 物品原型默认值（先做）

### 要解决的问题

`max_damage`、`max_stack_size` 这类**物品固有属性**在协议里是「原型默认值 + 补丁覆盖」：
服务端只在物品偏离原型时才写进 `data_components`。因此只读补丁会把「钻石剑能修 1561 次」
读成「不知道」，调用方无法区分「不可损坏」与「数据缺失」。

### 公共 API 变化

```kotlin
public data class InventoryItem(
    // …既有字段…
    /** 该物品类型的最大耐久；不可损坏的物品为 null。原型默认值与补丁覆盖合并后的结果。 */
    public val maxDamage: Int? = null,
    /** 该物品类型的堆叠上限（1/16/64 等）；未知物品 id 为 null。 */
    public val maxStackSize: Int? = null,
)
```

语义修正写进 KDoc 与 `docs/protocol-contract.md`：`maxDamage` 从「补丁里有」变成
「这个物品能承受多少耐久」，`null` 只表示**不可损坏**或**未知 id**。

明确不补齐的字段：`damage`、`repair_cost`、`custom_name`、`lore`、`enchantments`。
它们是**状态**而不是固有属性，「没提」等于「没有」，拿原型默认值（0 / 空）兜底反而会
把「全新物品」和「服务端没说」混成一件事。

### 版本资产与可重跑导出

新增 `protocol-core/.../item/ItemPrototypes.kt`（生成文件，头注释标注来源与条目数）：

```kotlin
internal object ItemPrototypes {
    fun maxDamage(itemId: Int): Int?      // 稀疏：只有原型定义了 max_damage 的物品才在表里
    fun maxStackSize(itemId: Int): Int?   // 稀疏：只存非 64 的项，64 是协议默认
}
```

导出器放在 **`testing:minestom`**（唯一允许依赖 Minestom 的模块），提供 Gradle 任务
`exportItemPrototypes`，遍历 `Material.values()`，读 `prototype()` 的 `MAX_DAMAGE` 与
`MAX_STACK_SIZE`，写出确定性的 Kotlin 源文件。约束：

- 任务**不参与 `build`**，只在显式调用时运行，避免 CI 悄悄改源码；
- 输出按 id 升序、无时间戳，二次运行零 diff；
- 读组件前先用 `has` 判断存在性，不能用 `get` 的 0 值代替「没有这个组件」；
- 文件头写明「不要手改：升级协议库/Minestom 后重新导出」，与 `ItemKeys` 同一约定。

### 验收标准

- 联调：服务端给一把**只改了附魔**的钻石剑，客户端读到 `maxDamage = 1561`；同一背包里的
  石头 `maxDamage` 为 `null`（不可损坏），两者不再混淆。
- 联调：`maxStackSize` 对账三类物品（64 的方块、16 的末影珍珠、1 的 Diamond Sword）。
- 单测：`ItemPrototypesTest` 锚点（木棍 `maxDamage` 为 null、钻石剑 1561、末影珍珠 16）
  与越界 id 返回 null。
- 二次运行导出任务产生零 diff（可重复性）。

### 风险

- 原型表里可能存在 `max_damage = 0` 的条目（表示可损坏但无耐久？），导出时按「有组件才写」
  处理，不做 0 与缺失的合并。
- 该表是版本资产：升级协议库时必须重跑，`docs/protocol-contract.md` 的升级清单要加上它。

## 2. M4z 插件消息（独立，成本最低）

### 公共 API

```kotlin
public data class PluginMessage(
    public val channel: String,   // 已校验的 resource location，如 "minecraft:brand"
    public val data: ByteArray,   // 不可变副本，调用方可以自由持有
)

// Session
public val serverBrand: StateFlow<String?>                  // 解析 minecraft:brand 的结果
public fun sendPluginMessage(channel: String, data: ByteArray)
```

- 下行：`ClientboundCustomPayloadPacket` → 复制字节 → `ClientEvent.PluginMessageReceived`，
  同时 `minecraft:brand` 额外解析到 `serverBrand`（payload 是 varint 长度前缀 + UTF-8，
  不是裸字符串；解析失败不改 `serverBrand`，只保留原始消息）。
- 上行：`ServerboundCustomPayloadPacket`，仅 play 阶段；channel 必须是
  `namespace:path` 形式的合法 resource location，payload 超过协议上限时本地拒绝。
- 只做「透传 + brand 解析」，不内置任何具体 channel 的语义（`minecraft:brand` 之外，
  如 `minecraft:register`、`bungeecord:main` 都交给下游判断）。

### 验收标准

- 联调：harness 向客户端发自定义 channel 的 payload，客户端收到原文（逐字节相同）；
  客户端发回后服务端 `PlayerPluginMessageEvent` 收到同一份 channel 与数据。
- 联调：`serverBrand` 读到 harness 设置的品牌串（Minestom 默认是 `Minestom`）。
- 单测：brand payload 的 varint 前缀解析、非法 channel 拒绝、超长 payload 拒绝、
  data 字节副本不可被外部数组改写。
- CLI：新增 `--plugin <channel>`，摘要打印收到的插件消息与品牌。

## 3. M4aa 拖拽与创造模式（依赖 M4y 的堆叠语义）

### 拖拽

拖拽不是一次点击，而是 begin → 若干 add → end 三段。把它做成**一次调用**：调用方给出
目标槽位与按键，本层连发全部包，符合「服务端只在 end 结算」的事实。

```kotlin
public enum class DragButton { LEFT, RIGHT, MIDDLE }

// Session
public fun drag(slots: List<Int>, button: DragButton = DragButton.LEFT)
```

- `LEFT` = 均分到各槽；`RIGHT` = 每槽 1 个；`MIDDLE` = 创造模式每槽放满（语义由服务端定，
  本层只选枚举）。
- 三包共用**拖拽开始前**那份快照的 `stateId` 与光标：拖拽期间服务端不结算，光标也不会变；
  begin/end 的槽位用 `CLICK_OUTSIDE_NOT_HOLDING_SLOT`。
- 槽位校验复用 `ContainerClicks.requireSlot`；空列表直接拒绝（发 begin+end 什么也不做的
  空拖拽是调用方的错误）。
- 与既有取舍一致：仍然 `changedSlots` 恒空、`carriedItem` 填点击前光标，服务端重发权威内容。

### 创造模式

```kotlin
// Session
public val gameMode: StateFlow<GameMode?>              // 自身条目，来自玩家列表
public fun grabMaxStack(slot: Int)                     // 创造模式整堆拿取
public fun setCreativeSlot(slot: Int, item: CreativeItem?)  // null = 清空
```

- `gameMode` 从玩家列表里自己那条解析（`RemotePlayer.gameMode` 已有），`null` 表示还没收到。
- `grabMaxStack` = `CREATIVE_GRAB_MAX_STACK`，非创造模式调用直接失败（fail fast，
  不把包发出去让服务端静默忽略）。
- `setCreativeSlot` 走 `set_creative_mode_slot`。本层只支持**无组件**物品
  （`CreativeItem(itemId, count)`），组件写入需要反向序列化组件补丁，明确列为后续能力，
  文档写清楚而不是发一个丢组件的包。
- 槽位语义：创造模式包里是**窗口槽位**还是玩家背包槽位需实测钉住（原版该包用
  1–45 的背包编号，与 M4h 的窗口编号不同）；不一致就在适配层换算，并由单测固定。

### 验收标准

- 联调：拖拽把 12 个石头均分到 3 个槽（每槽 4 个），右键拖拽每槽 1 个，两侧槽位对账。
- 联调：创造模式下 `grabMaxStack` 拿到 64 个，`setCreativeSlot` 放入指定物品后
  `inventory` 读到同一 id 与数量，`setCreativeSlot(slot, null)` 清空。
- 单测：`ContainerClicksTest` 覆盖拖拽三阶段的动作映射与槽位常量；`gameMode` 门控
  （生存模式调用创造 API 抛错）。

## 4. M4ab 破坏进度（独立，小）

别人挖方块时客户端应当看到裂纹阶段，而不是等方块消失才知道发生了事。

```kotlin
public data class BlockDestruction(
    public val breakerEntityId: Int,
    public val position: BlockPosition,
    public val stage: Int,        // 1..10，越大越接近破坏
)

// Session
public fun destruction(position: BlockPosition): BlockDestruction?
public fun destructions(): Map<BlockPosition, BlockDestruction>
```

- `ClientboundBlockDestructionPacket` → 按坐标存快照，`RESET` 删除该坐标条目；
  事件 `ClientEvent.BlockDestructionChanged(position, destruction)`。
- 重生换维度与会话结束时清空（属于旧世界）。
- 自己挖掘时服务端通常不回这个包（原版不发给自己），所以它是**观察他人**的能力；
  自己的进度仍以 `digBlock` + `block_changed_ack` 为准，文档写明这条不对称。

### 验收标准

- 联调：harness 第二个玩家挖方块，客户端依次收到 stage 递增与 `RESET`，坐标与服务端一致。
- 单测：`RESET` 删除条目、换维度清空。

## 5. 顺序与理由

1. **M4y**：先修正在读的全部物品字段（真 bug），并把版本资产导出变成可重跑任务——后续升级
   协议库时这项基建会反复用到。M4aa 的堆叠语义依赖它。
2. **M4z**：M4 验收清单里明写、当前完全缺失的一项，与其余三项无耦合，成本最低。
3. **M4aa**：拖拽与创造模式同属容器交互，一次把 `ContainerActionType` 剩余两项用完，
   不必再为同一个包开一轮。
4. **M4ab**：最后补破坏进度，纯下行只读，风险最低。

四项完成后，`docs/roadmap.md` 里「仍未交付」那段只剩 M3 的运行时验证（环境阻塞），
M4 的功能面即可宣告收尾。
