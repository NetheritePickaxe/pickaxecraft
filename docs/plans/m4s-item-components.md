# M4s：其余物品组件

## 目标

把 `InventoryItem` 从「只有键名与自定义名」扩展到渲染层与逻辑层真正会读的组件：
耐久、无法破坏、修复代价、物品名、说明文字、附魔。

## 协议事实

- `ItemStack` 的 `data_components` 是**补丁**，不是完整组件表。服务端只下发「与物品原型不同」
  的项：给钻石剑设 `max_damage = 1561`（恰好是默认值）时补丁里根本没有这个键，客户端读到
  `null`；改成 2000 才下发。因此本层的 `null` 含义是「补丁没提」，**不是**「物品没有耐久上限」——
  要还原完整原型需要物品默认组件表，属于另一份版本数据，本增量不建模。
- 补丁里「带键但值为 `null`」表示删除该组件，「不带键」表示没提；两者对外结果一致，
  所以用 `DataComponents#get` 即可，不需要 `contains` 区分。
- `unbreakable` 是 Unit 组件（值只是 `Unit.INSTANCE`），**存在即为真**，没有数值可读。
- `custom_name`（玩家改的显示名）与 `item_name`（物品名称组件）是两个不同字段，不能合并。
- `enchantments` / `stored_enchantments` 里的 key 是附魔注册表 wire id。附魔是**动态注册表**：
  Minestom 的数据文件 `enchantment.json` 里没有 id 字段，顺序也不是字母序；只有配置阶段下发的
  `minecraft:enchantment` 注册表条目顺序才等于 wire id。因此这里不固化一张版本表，
  而是在 `McplConnection` 收到注册表时记下键名列表，解析物品时按 id 查。服务端加自定义附魔也能工作。
  id 落在表外时降级为 `unknown:<id>`，保留数据而不是静默丢弃。

## 设计

- `ItemComponents`（`protocol-core/.../internal/`）集中做 `ItemStack → InventoryItem` 转换，
  同时持有附魔注册表常量与键名列表构造；`McplConnection` 只负责在配置阶段保存列表。
  这样组件解析是可以单测的纯逻辑，不用拉起连接。
- `InventoryItem` 新增字段全部带默认值，既有调用方无需改动：
  `itemName`、`lore`、`damage`、`maxDamage`、`unbreakable`、`repairCost`、
  `enchantments`、`storedEnchantments`。
- 附魔单独保留 `storedEnchantments`（附魔书），不与普通附魔合并：两者的语义和使用场景不同。
- CLI 摘要把非空组件列在物品键名之后，便于肉眼核对补丁是否穿过协议。

## 测试门控

- 单测 `ItemComponentsTest`（4 例）：全部字段的取值、显式删除与缺省等价、
  附魔 id 越界保留数字、没有补丁时全字段为默认。
- 联调 `物品组件可读且附魔键名来自动态注册表`：服务端给一把钻石剑设满组件，
  客户端逐字段对账；附魔断言 `minecraft:sharpness = 3`，证明动态注册表顺序确实可用。
- 服务端侧日志 `event=server.item.components` 是「物品真的进了背包」的独立证据。

## 实现时发现的事实

- 把 `max_damage` 设成物品默认值会被服务端省略（见上），联调特意用 2000 这个非默认值。
  这条不是 Minestom 的怪癖，是补丁语义的必然结果，原版客户端靠本地物品原型补齐全量组件。
- Minestom 的 `EnchantmentList` 用 `RegistryKey<Enchantment>` 作键，常量接口是包内可见的，
  harness 里直接 `RegistryKey.of<Enchantment>("sharpness")` 更合适。
- `Unit` 组件在服务端是 `net.minestom.server.utils.Unit`，在协议库是同名的另一个类；
  Kotlin 里要用 import 别名，别和 `kotlin.Unit` 混在一起。
