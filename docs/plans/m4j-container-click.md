# M4j 实施记录：容器点击上行与关闭窗口

日期：2026-11-04
前置：M4h（窗口槽位模型与 `Container.stateId`）

## 1. 目标

M4h 让背包**可读**，这一轮让它**可动**：把点击槽位（拿起、放下、Shift 转移、换到快捷栏、
丢弃、双击收拢）与关闭窗口发给服务端，并把服务端的结果收敛回快照。

## 2. 点击包的两半：动作与参数

协议里 `container_click` 一个包覆盖所有点击，字段是「动作类型 + 参数」：

| 动作（`ContainerActionType`） | 参数 | 语义 |
| --- | --- | --- |
| `CLICK_ITEM` | 0 左键 / 1 右键 | 拿起或放下整堆 / 半堆、一个 |
| `SHIFT_CLICK_ITEM` | 0 左键 / 1 右键 | 快速转移（容器↔背包） |
| `MOVE_TO_HOTBAR_SLOT` | 0–8 | 与快捷栏某一格交换（40 是副手） |
| `DROP_ITEM` | 0 一个 / 1 整堆 | 丢弃（槽位 `-999` 表示丢光标上的物品，本轮不做） |
| `FILL_STACK` | 0 | 双击把同类物品收拢到光标 |

上层不该背这张表，因此公共 API 用密封接口 `SlotClick` 表达**意图**，映射集中在适配层
的 `ContainerClicks` 并单测。`CLONE`（创造模式取整堆）与 `SPREAD_ITEM`（拖拽）不在本轮：
前者需要创造模式判定，后者是「按下—划过若干格—抬起」的多包协议。

## 3. `stateId` 从哪来

`container_click` 要带回服务端上一次下发的 `stateId`，服务端用它判断「客户端看到的是不是
最新内容」。适配层的 `containers` 是网络线程独占的可变状态，跨线程读它不安全（字段也没有
可见性保证）。

解法是**让快照携带它**：`Container.stateId` 早就在快照里（M4h 特意保留），点击时由调用方
把自己手上那份快照传下来（`clickSlot(container, slot, click)`），适配层从里面取
`windowId` 与 `stateId`。这样不需要任何跨线程读取，也不需要在公共 API 里暴露裸的 `stateId`
数字。

## 4. 不做本地预测，但光标要如实声明

真实客户端会在本地重算点击结果并填进 `changedSlots` / `carriedItem`，服务端拿这份预测做
去同步检测。本库**不预测**：重算需要物品组件（`max_stack_size`、可装备性）与快速转移的合并
规则，那是第二套注册表，不在本轮范围。因此 `changedSlots` 恒为空。

`carriedItem` 则不填空，而是填**点击前**光标上的物品（哈希只带 id 与数量，不带组件）。这一点
比看上去要紧：

- 填“点击前”= 如实说“我手上是这些”。服务端应用点击后发现与声明不符 → 重发权威内容（含光标）
  → 本端收敛。
- 填 `null` = 谎称“我手上没有”。当点击**把光标清空**（例如把手上的那堆放进背包）时，服务端的
  结果恰好也是空，于是它认为两端一致、什么也不发——本端就会永远停在点击前的那堆物品上。

代价是每次点击多一个整包，换来的是绝不漂移。

## 5. 交付内容

### 5.1 协议层

- `SlotClick`（公共）：`Pickup(rightButton)`、`QuickMove(rightButton)`、`SwapWithHotbar(hotbar)`、
  `Throw(wholeStack)`、`PickupAll`。快捷栏下标在构造期就校验。
- `ContainerClicks`（内部）：`(container, slot, click)` → `ServerboundContainerClickPacket`，
  `changedSlots` 为空、`carriedItem` 取 `container.cursor`。
- `ProtocolConnection.clickSlot(container, slot, click)` 与 `closeContainer(windowId)`。

### 5.2 客户端

- `Session.activeWindowId`：有打开的容器时是它（取最大的窗口 id，服务端递增分配），否则是
  窗口 0。
- `Session.clickSlot(slot, click)`（作用于活动窗口）与 `Session.clickSlot(windowId, slot, click)`。
  窗口内容还没到时报 `IllegalStateException`——没有 `stateId` 可用，发出去只会被拒。
- `Session.closeContainer(windowId)` 与 `Session.cursorItem()`。
- 不新增事件：点击的结果就是服务端随后下发的 `InventoryChanged`。

### 5.3 光标只存一份，记在活动窗口上

`set_cursor_item` 不带窗口 id。原版客户端把光标存在当前打开的 menu 上，本层照做：写到活动
容器（没开容器时是窗口 0）。这不只是模仿——**点击这个窗口时声明的光标，与这个窗口快照里的
光标必须是同一份**，否则上面 §4 的“如实声明”就无从谈起：点箱子时若从窗口 0 取光标，
就会在“箱子的快照说没有、玩家其实拿着”之间来回错位。

### 5.4 槽位校验的边界

窗口 0 的槽数是固定的 46，越界直接拒绝。**其余窗口不校验上限**：Minestom 的容器整包只带
容器自己的槽位（原版会连背包段一起发），27 格的箱子后面那 36 格背包槽位在快照里并不存在，
但它们是完全合法的点击目标。宁可让服务端去拒绝，也不要按快照长度挡住真实可点的格子。

## 6. 关闭窗口：第三处“成功没有回执”

Minestom 的 `Player.closeInventory(fromClient, windowId)` 只在**服务端发起**关闭时
（`didCloseInventory` 为真）才下发 `container_close`；客户端主动关窗时它什么都不发——
主动关的那一端本来就知道。原版也是这个语义。

因此关闭在发出时就本地生效：`forgetContainer` 移除窗口并上报 `ContainerClosed`。服务端随后
若因为插件而真的关窗，会再发一个关闭包，`forgetContainer` 找不到窗口，不会重复上报。

## 7. 验证

`ClientSessionTest.容器点击能把物品搬到背包`（3 行箱子）：

1. 左键点容器第 0 格 → 服务端光标拿到整堆，容器该格变空，客户端 `cursor` 也对上；
2. 左键点窗口里的快捷栏第一格（`27 + 27 = 54`）→ 物品落进背包，客户端窗口 36 与服务端
   `inventory` 第 0 格同时对上，光标回到空；
3. Shift 点击 → 整堆进背包、数量不变、容器该格清空；
4. 丢弃整堆 → 服务端与客户端两侧该格都变空；
5. `closeContainer` → 客户端事件到达、服务端 `getOpenInventory()` 变 null、背包仍在、
   活动窗口回到 0；
6. 越界槽位、负槽位、已关闭的窗口在本地被拒，会话仍在 play。

两个在实现里才暴露的坑，都写进了用例注释：

- **一次点击会改两个容器**：服务端先发背包整包、再发容器整包。只等前一半就去断言容器内容，
  会读到旧值（第一次跑就是这样失败的）。
- **本地事件的时序早于服务端处理**：`closeContainer` 的 `ContainerClosed` 在发包时就投递，
  断言服务端 `getOpenInventory()` 必须用 `awaitCondition` 等一个来回。

`ContainerClicksTest`（9 例）钉住动作与参数映射、快捷栏下标（协议参数是快捷栏下标，不是窗口
槽位）、光标声明、槽位校验，以及动作类型在包里的编号（包体写的是枚举序号）。

## 8. 未覆盖

- 拖拽（`SPREAD_ITEM` 的三个阶段）与创造模式取整堆（`CLONE`）。
- 丢光标上的物品（槽位 `-999`）。
- 副手交换（协议里是 `MOVE_TO_HOTBAR_SLOT` 参数 40）。
- 容器属性（熔炉进度、附魔台等级）、村民交易。
- 关窗时光标上还剩物品的去处：由服务端决定（丢到地上或塞回背包），本端只跟进背包更新，
  不推测掉落了什么。
