# M4t：光照、高度图与方块实体

## 目标

把 `level_chunk_with_light` 剩下的三部分接上：一列区块的天空光/方块光、六张高度图、
以及方块实体（箱子、告示牌、刷怪笼）。覆盖两条下行链路：整列下发，以及 `update_light` /
`block_entity_data` 的增量更新。

## 协议事实

- 区块包里的 section 数据由本库自研解码（M4a）；**光照、高度图、方块实体由协议库解析好**
  交出来。因此这一增量不是“再写一个解码器”，而是“把已有数据换算成不依赖协议库的形状”。
- 光照结构：四张位图（天空光/方块光各自一张「有数据」与一张「全黑」）+ 两串 2048 字节数组。
  数组不写下标，**掩码里第 n 个为 1 的位对应第 n 段数组**；层号从世界最低 section 的
  下一层开始，一列 `sectionCount` 段区块共 `sectionCount + 2` 层。
- 每层 2048 字节是 4096 个半字节，`index = (y << 8) | (z << 4) | x`，偶数格取低四位。
- 高度图固定 9 位一格、每 long 放 7 个值、值不跨 long：256 格 = 37 个 long。存储值是
  `第一格空气的 Y - minY`，世界坐标要加回 `minY`。
- 方块实体是「枚举序号 + 可选 NBT」。枚举按名字小写还原成 `minecraft:chest` 这类键名。
- 服务端**不发**「删除方块实体」的包：换掉方块的 `block_update` 就是“旧实体不存在了”。

## 设计

| 位置 | 职责 |
| --- | --- |
| `world/ChunkLight.kt`（内部类） | 按层保存半字节数组，`skyAt/blockAt` 读取，`withPatch` 合并 |
| `world/ChunkLight.kt`（`LightPatch`） | 一次更新：`层号 → 2048 字节`，空层映射到共享零数组 |
| `world/NbtValue.kt` | 不依赖协议库的 NBT 树：数值、文本、列表、复合 |
| `world/BlockEntity.kt` | 位置 + 键名 + 可选 NBT |
| `world/HeightmapType.kt` | 六种高度图的枚举，顺序与协议一致 |
| `world/ChunkColumn.kt` | 一列区块承载四部分；`skyLightAt`/`blockLightAt`/`heightAt`/`blockEntity`，`withLight`/`withBlockEntity` |
| `internal/LightPatches.kt` | 掩码 + 数组 → `LightPatch`，数量与层号校验 |
| `internal/NbtConversion.kt` | `NbtMap` → `NbtValue`，未知类型直接失败 |

三个决定：

1. **四部分合成一列。** 它们来自同一个包，分开缓存只会让“这列区块是什么”出现多个真相。
   光照与方块实体被增量包改写时返回新列，`ChunkColumn` 仍然不可变。
2. **`null` 与 `0` 分开。** 没有光照数据是 `null`，全黑是 `0`；高度图没下发是 `null`；
   方块实体 NBT 为 `null` 表示这个包没带数据。调用方不会把“不知道”当成“是零”。
3. **方块变更清掉同格方块实体。** 服务端不会为“箱子被挖掉”单独发包；不清就会一直显示
   一个不存在的箱子。新方块自带实体时，紧跟的 `block_entity_data` 会把它放回来。

## 测试门控

- `LightPatchesTest`（4 例）：掩码第 n 个位对齐第 n 段数组、空掩码 → 共享零数组、
  掩码与数组数量不一致时失败、层号越界失败。
- `ChunkEnvironmentTest`（9 例）：半字节顺序（低四位在前）、未下发层返回 `null`、
  全黑返回 `0`、更新只替换出现的层、世界 Y 换算（含 `minY` 之下的层）、高度图 9 位解包与
  长度校验、方块实体随方块变更清除并可由新包放回、同位置整条替换。
- 联调 `区块的光照、高度图与方块实体都可读`：
  1. 出生前摆一个箱子 → 断言整列下发就能读到 `minecraft:chest`；
  2. 露天的天空光为 15、石头内部为 0、超出高度为 `null`；
  3. 出生后放箱子 → 走增量包，`BlockEntityChanged` 事件到达；
  4. 换成石头 → 服务端不发删除包，缓存里必须变回 `null`；
  5. 放火把 → `update_light` 到达，方块光从 0 变非 0，`ChunkLightUpdated` 事件到达；
  6. 高度图取一块没被别的用例动过的列，值等于 `GROUND_LEVEL`。

## 实现时发现的事实

- **Minestom 默认用 `DynamicChunk`，根本不计算光照**：区块照样发，光照全是 0
  （`emptySkyYMask`/`emptyBlockYMask` 覆盖所有 section）。联调实例必须
  `setChunkSupplier { inst, x, z -> LightingChunk(inst, x, z) }` 才会得到真实光照。
  这不是本库的 bug，但它是“为什么一开始读到的光照全 0”的正确答案。
- 即使换成 `LightingChunk`，区块也会在光照算完之前发出去（懒计算）。联调用例需要先调用
  服务端的 `getSkyLight` / `getBlockLight` 触发一次计算并 `sendLighting()`，否则读到的是
  发送时刻的快照。harness 的 `pushLight` 就是这件事，交出去的仍是服务端自己算的光。
- 方块放置后的光照重发默认等 **100 tick（5 秒）**，`ServerProperties` 在类初始化时读系统属性，
  只能在测试 JVM 的 `systemProperty` 里压到 2 tick；运行时再改无效。
- 高度图是整列下发时的快照，`withBlock` 不重算它（判断“哪些方块算地表”需要方块分类表）。
  联调用例因此不能查出生点那一列——前面的用例会在那里挖方块，高度图与方块缓存会不一致。
- 把 `LightUpdated` / `BlockEntityUpdated` 写进 `onProtocolEvent` 时先写了 `return`（像区块、
  实体那样的“只进缓存不进事件流”），结果缓存有值但客户端事件永远不来。这两类事件频率低
  且有下游意义，应当照 `BlocksChanged` 的方式继续入队，只在 `handle` 里判断“有没有落点”。
- 协议库的 `@NonNull` 是 Lombok 的类型注解，不在消费端 classpath 上：
  `packet.blockEntities.map { it.toBlockEntity(...) }` 会报
  `MISSING_DEPENDENCY_IN_INFERRED_TYPE_ANNOTATION`，给 lambda 参数显式标类型即可。
