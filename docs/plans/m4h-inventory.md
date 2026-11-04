# M4h 实施记录：背包与容器（只读）与快捷栏选中格

日期：2026-11-04
前置：M2（出生链路）、M4a（区块解码，同样是“压缩数据还原成可查询模型”）

## 1. 目标

让调用方能回答三个问题，且不必接触包类型与槽位编号：

1. 我背包里有什么（含空槽位置）？
2. 我手上拿的是哪一格、里面是什么？
3. 我打开了什么容器、里面有什么？

按“先读后写”的顺序推进：本增量只读容器内容，另加一个最小的上行——切换快捷栏格。

## 2. 交付内容

### 2.1 协议模型（`protocol-core/inventory`）

- `InventoryItem(itemId, count)`：物品堆只保留协议 id 与数量。组件（附魔、名称、耐久）不在本层
  建模——那是一张“组件 id → 值”的补丁表，需要另一套注册表与类型系统。
- `Container(windowId, type, title, stateId, slots, cursor)`：不可变窗口快照。`slots` 按**窗口槽位**
  排列，空槽为 null；`stateId` 是将来做点击时必须原样带回的状态号。
- `PlayerInventoryLayout`：窗口 0 的布局常数（主背包 9–35、快捷栏 36–44、副手 45、总数 46）。

### 2.2 适配层

处理 8 类包：

| 包 | 处理 |
| --- | --- |
| `container_set_content` | 整包替换槽位与鼠标物品 |
| `container_set_slot` | 单槽更新，按需补齐短列表 |
| `open_screen` | 建空容器（类型 + 标题），随后内容才到 |
| `container_close` | 移除窗口；窗口 0 的关闭包被忽略（背包不能“被关闭”） |
| `set_player_inventory` | 槽位编号换算是窗口槽位后写入窗口 0 |
| `set_cursor_item` | 更新窗口 0 的鼠标物品 |
| `set_held_slot` | 服务端纠正选中格 |
| `set_carried_item`（上行） | 切换选中格 |

**两套槽位编号**是这一段最容易出错的地方：`set_player_inventory` 用“背包槽位”（0–8 快捷栏、
9–35 主背包、36–39 靴/腿/胸/头、40 副手），其余包用“窗口槽位”。护甲段在两套里**反序**，
照抄下标会把头盔放到靴子的位置。换算独立成 `PlayerSlotMapping.toWindowSlot` 并单测。

### 2.3 公共 API（`client-core`）

- `Session.inventory()`：窗口 0 快照（出生时服务端就下发整包，不是“打开才有”）。
- `Session.container()` / `container(windowId)` / `containers()`：当前打开的容器与全部窗口。
- `Session.heldSlot` / `heldItem()`：选中格与手持物品。
- `Session.selectHotbarSlot(slot)`：上行切换，`0..8` 之外抛 `IllegalArgumentException`。
- 事件：`InventoryChanged(windowId)`（内容变了，去缓存读）、`ContainerOpened`、
  `ContainerClosed`、`HeldSlotChanged`。

内容变化只报“哪个窗口变了”而不逐槽发事件：箱子 27 格、背包 46 格，一次打开会连着几包，
逐槽投递会把有界缓冲打满——那在本项目里等于终止会话。

### 2.4 一次真实的取舍：成功没有回执

`set_carried_item` 与移动包一样，**服务端接受时不回包**，只有拒绝或改写时才下发 `set_held_slot`。
最初按“等服务端确认”实现，用例立刻卡在等待事件上；改成发送后立即推进本地值、服务端纠正时覆盖，
与 `moveTo` 的处理保持一致。这条差异现在写在 `ProtocolEvent.HeldSlotChanged` 的注释里。

### 2.5 验证

- `PlayerSlotMappingTest`（6 例）：快捷栏/主背包同序、护甲段反序、副手与越界、单槽补齐、
  整包替换清掉旧槽位、快捷栏下标越界。
- `ClientSessionTest.背包与容器内容可从会话读到`：
  - 服务端在 Minestom 槽 0（快捷栏第一格）放钻石，客户端从窗口 36 读到同一个 id 与数量；
  - 打开 3 行箱子：`ContainerOpened` 的类型 `GENERIC_9X3` 与标题都来自服务端，箱内物品可读；
  - 箱内单槽更新不影响其他格子；关箱后 `container()` 为空、`inventory()` 仍在；
  - `selectHotbarSlot(3)` 后服务端 `heldSlot` 变为 3，`heldItem()` 读到快捷栏该格的物品；
  - 非法下标被拒且会话仍在 play；关闭后查询返回 null、上行被拒。
- CLI 现场：`event=inventory window=0`（出生即下发整包）、`event=held.sent slot=2 item=null`
  后紧跟 `event=held slot=2`。

## 3. Minestom 与真实服务端的差异

打开容器时，Minestom 的整包内容**只带容器自己的 27 格**；原版会把后面的玩家背包段一起发。
最初按原版预期断言“容器窗口里能看到自己的快捷栏”，用例失败。结论写在用例注释里：
窗口 0 才是玩家背包的权威来源，不要把背包当成容器窗口的一部分。

## 4. 未覆盖

- 点击、拖拽、丢出（需要 `container_click` 与状态号校验、点击类型语义）。
- 物品组件与可读名字（需要 `minecraft:item` 注册表 + 组件编解码）。
- 容器属性（熔炉进度、附魔台等级：`container_set_data`）。
- 商人交易、告示牌编辑、铁砧命名等窗口专属交互。
- 盔甲与副手的装备语义（读得到槽位，但没有“装备了什么”的类型判断）。
