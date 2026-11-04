# M4m：物品键名与自定义名

## 目标

把 `InventoryItem(itemId=1)` 变成 `InventoryItem(key="minecraft:stone")`；物品上显式设置的
显示名（`custom_name` 组件）也一并读出来。

## 协议事实

- 协议库的 `ItemStack` 只有数字 id、数量与组件补丁表，**不带任何注册表**——id → 键名
  必须本仓库自己维护。
- 键名表由 Minestom 2026.10.07-26.2 的物品注册表导出（1537 项，id 连续 0..1536，因此
  平行数组按下标查表即可）。升级协议库 / Minestom 时必须重新导出整表替换。
- `custom_name` 是组件补丁里的一项，值是文本组件；补丁语义允许「显式删除该键」，
  取不到就是 null——与「没设置过」不可区分，对外含义一致（都没有显示名）。
- 空字符串的名字是**真实存在的组件值**（服务端设置了空文本），不是 null；
  harness 的 `giveNamedItem` 用 `name = null` 表示不设组件，两者在测试里都要能区分。

## 测试门控

- 单测：键名表的锚点（stone=1、diamond=926、diamond_sword=964）与越界（-1、1537 → null）。
- 联调：服务端塞一块名为「试金石」的石头与一颗无名钻石，客户端按键名找槽位，
  断言 `customName` 一个是设置值、另一个是 null（不是空串）。
- CLI：背包摘要列出 `items=minecraft:stone` 这样的键名。
