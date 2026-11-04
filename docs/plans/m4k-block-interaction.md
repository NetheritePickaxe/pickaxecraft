# M4k 实施记录：方块交互（挖掘与使用）

日期：2026-11-04
前置：M4a（区块解码与 `Session.blockStateAt`）、M4g（移动上行）、M4h（背包只读）

## 1. 目标

到 M4j 为止，客户端能读世界、读背包、动背包，但**动不了世界**：区块解码后是只读的，
服务端下发的方块变更也没有人接。这一轮补上三件事：

1. `player_action` 的挖掘链路（开始 / 取消 / 完成）；
2. `use_item_on`（放置方块、与方块交互）与 `use_item`（对着空气用物品）；
3. 服务端的方块变更回到本地：`block_update` / `section_blocks_update` 落到区块缓存。

第三件是前两件的**回声**。缺了它，挖掉一块方块之后 `blockStateAt` 永远返回旧值——客户端
会一直以为自己站在一块已经不存在的石头上。

## 2. 三个新概念

### 2.1 `BlockPosition`

方块坐标是整数三元组，而协议里的坐标是打包成一个 long 的：x、z 各 26 位有符号，y 12 位。
超出这个范围的值会被静默截断（`& 掩码`），于是“挖 (0, 5000, 0)”会变成“挖 (0, 904, 0)”——
不报错、没反应、查不出原因。因此坐标必须是一个**自带范围校验**的值类型，而不是三个散装 int。

`BlockPosition` 同时给出 `offset(face)`：放置方块的目标位置就是“点中的方块 + 面的方向”，
这是交互里唯一一处必须做对的算术。

### 2.2 `BlockFace`

协议里的方向枚举是 `DOWN, UP, NORTH, SOUTH, WEST, EAST`，序号即编码。公共 API 不能直接复用
协议库的 `Direction`（那会把依赖暴露到上层），因此本库自带同名枚举，映射集中在适配层，
由单测钉住序号——顺序写错了不会编译失败，只会“点了东面却朝西面放”。

### 2.3 `Hand`

`MAIN_HAND` / `OFF_HAND`。几乎所有交互包都带这个字段，缺省是主手。

## 3. 挖掘是三段，不是一个包

| 动作 | 何时发 | 服务端做什么 |
| --- | --- | --- |
| `START_DIGGING` | 按下左键 | 计算挖掘耗时；耗时 0 的方块（草、火把、创造模式）当场打碎 |
| `CANCEL_DIGGING` | 中途松开 | 中止，并**回滚客户端预测** |
| `FINISH_DIGGING` | 进度满 | 打碎方块、掉落物品、广播 `block_update` |

服务端对 START 与 CANCEL 也会回 `block_changed_ack`，因此“发了包”与“方块没了”之间隔着
一到两个往返。本库暴露三段原语，方便带渲染的客户端按材质硬度自己控制 FINISH 的时机；
另外提供 `digBlock` = START + FINISH，一次调用完成一次挖掘，供无头客户端与联调使用。

**为什么不等待**：真客户端要等挖掘进度条走满才发 FINISH，而进度取决于手持工具的附魔、
状态效果与方块硬度——那是一整套游戏规则，本库不做。`digBlock` 的行为是“立刻完成”，
服务端若校验严格会拒绝（无回执），这一点写在 KDoc 里，而不是假装它总是成功。

## 4. 序号（sequence）是这一轮唯一的新机制

`player_action`、`use_item_on`、`use_item` 三个包都带一个**客户端自增**的序号。服务端：
- 用它在 `block_changed_ack` 里告诉客户端“你的第 N 个动作处理完了”；
- 它也是服务端回滚的依据：客户端预测的方块变更带序号，服务端发现不符时下发权威方块。

本库**不做方块预测**（与 M4j 不做点击预测同一个理由：需要方块硬度、工具与掉落规则），
所以序号只有一个用途——**回执**。它仍是必需的：

1. 它是判断“服务端有没有收到这个动作”的唯一信号（方块被打碎不算：可能被别的玩家打碎）；
2. 服务端处理后才会发 `block_changed_ack`，序号错乱会让服务端把后续方块更新当成过期预测丢掉。

序号在适配层用一个 `AtomicInteger` 递增，调用方看不到也不需要看到。

## 5. 方块变更怎么落回缓存

`ChunkColumn` 到 M4a 为止是**不可变**的：解码后整列替换，多线程读取不需要锁。这个性质不能丢，
否则 `blockStateAt` 会在渲染线程上读到半更新的 section。

做法是**写时复制**：`withBlock` 复制受影响的那个 section 的方块存储（展开成 4096 个 int），
改动一格，再生成新的 `ChunkColumn`。一次方块更新复制 16 KB，换来的是读取路径完全不变。

两个细节：

- **只有被改过的 section 才展开**。未改动的 section 继续用调色板压缩形态，出生点视野的内存
  占用不会因为挖了一块方块就翻倍。
- **`blockCount` 要跟着改**。它决定 `isEmpty`，而渲染层靠 `isEmpty` 跳过整层；挖空一层之后
  仍然报告“非空”只是多画一点，但**把方块放进空层却仍报空**会让它整层不画。

`blockCount` 的增量按 `stateId == 0` 判断空气。原版的全局方块状态注册表从 `minecraft:air`
开始，0 恒为空气——这是一条跨版本稳定的不变量，写进 KDoc 而不是埋在代码里。

## 6. 交付内容

### 6.1 公共类型

- `world/BlockPosition`：方块坐标 + `offset(face)` + 协议范围校验。
- `world/BlockFace`：六向枚举 + `opposite`。
- `world/BlockChange`：一次方块变更（坐标 + 新状态 id），供事件批量携带。
- `inventory/Hand`：主手 / 副手。

### 6.2 协议层

- `ProtocolConnection.startDigging / cancelDigging / finishDigging / digBlock`；
- `ProtocolConnection.useOnBlock(position, face, cursor…, hand)`；
- `ProtocolConnection.useItem(hand)`；
- `ProtocolEvent.BlocksChanged(changes)`：一个包一个事件（爆炸一次几百格，逐格发事件会打满缓冲）；
- `ProtocolEvent.BlockChangeAcknowledged(sequence)`。

### 6.3 客户端层

- `Session` 的同名方法，外加 `useOnBlock` 的默认光标（方块中心）；
- 方块变更在**网络线程**写入 `chunks`（与 `ChunkLoaded` 同一处、同一顺序），再进入事件流；
- `ClientEvent.BlocksChanged(changes)`、`ClientEvent.BlockAcknowledged(sequence)`；
- `lastAcknowledgedSequence`：最近一次被服务端确认的动作序号。

### 6.4 联调

- harness 记录 `PlayerStartDiggingEvent` / `PlayerFinishDiggingEvent` / `PlayerBlockPlaceEvent`；
- 用例断言两侧：服务端 `instance.getBlock` 变成空气 / 石头，客户端 `blockStateAt` 与事件一致，
  且 `lastAcknowledgedSequence` 前进。

## 7. 不做的事

- **方块预测**：不上报 `changedSlots` 式的预测，服务端下发什么就是什么。
- **挖掘进度**：不计算硬度与工具，`digBlock` 立即完成；需要动画的调用方自己控制三段。
- **多方块区域操作**（`section_blocks_update` 全量重建）：照单全收每一格，不做局部重解码。
- **方块实体（NBT）**：箱子内容仍只来自窗口包，不从方块实体读。
- `player_action` 的其余动作（丢弃物品、交换副手、蓄力释放）不在本轮。

## 8. 实现时发现的事实

### 8.1 放不进玩家自己占据的格子

CLI 第一版把 `--place` 做成“放到脚下方块的上表面”，现场必失败：脚下方块的上面正是玩家
自己的碰撞盒。Minestom（与原版一致）在放置前做碰撞检查（`CollisionUtils.canPlaceBlockAt`），
目标是玩家自己时**只回确认、不下发方块变更**——不是错误，是正确的原版行为。

CLI 因此把放置点偏东一格。这也顺带解释了一个容易误判的现象：放置被拒时服务端会发一条
“目标格还是原样”的回滚方块变更（`rollback`），客户端收到后缓存不变、但事件照发——
把回滚变更当成放置成功就会得出错误结论，必须核对**状态值**而不是只看有没有事件。

### 8.2 协议库方向枚举的包名是 Kotlin 关键字

`Direction` 在 `org.geysermc.mcprotocollib.protocol.data.game.entity.object` 包里，
`object` 是 Kotlin 关键字，import 必须写成 ``entity.`object`.Direction``，否则报
“Qualified name must be a '.'-separated identifier list”。反引号转义。

### 8.3 现场证据

`./gradlew clean build` 通过；新增 `BlockInteractionTest`（8 例）、`ChunkColumnWriteTest`
（5 例），联调 `ClientSessionTest` 增至 19 例。

挖掘（CLI `--dig`）：

```
event=dig.sent pos=(0, 39, 0) before=1
event=block.ack sequence=1          ← 开始挖掘的回执
event=block.changed pos=(0, 39, 0) state=0
event=block.ack sequence=2          ← 完成挖掘的回执
event=dig.done pos=(0, 39, 0) state=0 ack=2 confirmed=true
服务端：event=player.dig.finish name=DigDemo x=0 y=39 z=0
```

放置（CLI `--place`，harness 演示模式给进服玩家 16 块石头）：

```
event=place.sent pos=(1, 39, 0) face=UP placingAt=(1, 40, 0) held=InventoryItem(itemId=1, count=16)
event=block.changed pos=(1, 40, 0) state=1
event=block.ack sequence=1
event=place.done pos=(1, 40, 0) state=1 ack=1 confirmed=true
服务端：event=player.place name=PlaceDemo2 block=minecraft:stone x=1 y=40 z=0
```

`before=1 → state=0` 与 `state=1` 的两侧一致，就是「改了世界并且缓存跟上了」的直接证据。
