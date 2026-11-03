# M4a 实施记录：区块内容解码与只读世界模型

状态：已实施（2026-11-04）
范围：[roadmap.md](../roadmap.md) 第 7 节 M4 的第一个增量
结论：客户端能按坐标读出解码后的方块状态，且与服务端 `stateId` 一致；光照、高度图、
方块实体与实体同步留给同一里程碑的后续增量。

## 1. 起点：协议库不解析区块

`ClientboundLevelChunkWithLightPacket` 在 MCProtocolLib 26.2 里只把 `chunkData` 作为 `byte[]`
原样交出，`MinecraftTypes` 里虽然有 `readChunkSection`，但客户端包解码路径并没有用它——
库服务的是转发场景，不需要客户端世界模型。因此“读到某个坐标是什么方块”这件事只能由本项目完成，
而它决定了后续所有世界能力（实体位置、碰撞、渲染）是否能成立。

同时，区块包里**没有** section 数量：它由当前维度的 `height / 16` 决定，维度信息来自配置阶段的
`minecraft:dimension_type` 注册表，`ClientboundLoginPacket` 只给出注册表下标。这意味着解码顺序
必须是“注册表 → Login → 区块”，任何一步缺失都不能靠猜。

## 2. 线上格式与两处容易记错的细节

每个 section 是 `blockCount:i16, fluidCount:i16, blockStates, biomes`，两个容器共用同一套编码：

```text
bitsPerEntry: u8
bits == 0            -> singleValue: varint          （整段同一个值，不占数组）
bits <= 上限          -> palette: varint 长度 + varint 数组, storage: i64 数组
其余（直接模式）      -> storage: i64 数组
```

方块上限 8 位（下限 4），生物群系上限 3 位（下限 1）；存储按“一个 long 放 `64 / bits` 个值、
值不跨 long 边界”打包。

**细节一：长整型数组没有长度前缀。** 早期版本的区块格式在 long 数组前写 varint 长度，
实现时按这个记忆写了带前缀的版本，联调时第一列区块就解不动（尾部多出 2 字节）。
查协议库的 `MinecraftTypes.readFixedSizeLongArray` 与 Minestom 的 `Palette` 序列化器才确认：
26.2 的长度由元素数与位宽算出，线上不再携带。单测最初也是按带前缀的格式写的，
因此测试通过并不代表格式正确——这条只能靠真实服务端发现。

**细节二：维度高度必须先查注册表。** 解码器把 `sectionCount` 当参数，而不是从数据里推。
`DimensionRegistry` 按注册表条目顺序保存 `min_y` 与 `height`，下标与 Login 包里的维度 id 对齐；
顺序被重排就全错，因此解析时不做任何过滤或排序。

## 3. 改动

### 3.1 protocol-core：中立模型与解码器

| 文件 | 职责 |
| --- | --- |
| `world/ChunkColumn.kt` | 不可变的区块列与 section；按世界坐标读方块/生物群系，越界即抛错 |
| `internal/ChunkDecoder.kt` | 三种调色板形态、位宽校验、截断与尾随字节校验 |
| `internal/DimensionRegistry.kt` | 从 `dimension_type` 的 NBT 读 `min_y` / `height`，并校验 16 的整数倍 |

存储保持压缩形态（调色板 + long 数组）而不是展开成 4096 个 `Int`：一份 24 层的区块通常只有
几十 KB，361 列出生视野才能同时留在内存里。全空气层仍保留自己的生物群系容器，因为群系与方块
是两份独立数据。

新增事件 `ChunkLoaded(column)` 与 `ChunkUnloaded(x, z)`。两者都不携带 `ByteBuf`，
`McplConnection` 是唯一接触协议库类型的地方。

解码失败带坐标与维度重新抛出（`IllegalStateException`），由失败归层判成 `PROTOCOL`。
静默跳过会让世界缺一块而无人知晓，这比断开连接更难排查。

### 3.2 client-core：世界数据走独立缓存

区块事件**不进入** `inbox`。原因是两个具体数字：出生视野一次会到 300 列以上，而事件缓冲默认
只有 256；把大对象塞进与信用量绑定的队列，既会挤掉控制事件，消费者稍慢还会直接终止会话。

实际做法：

- `Session` 持有 `ConcurrentHashMap<Long, ChunkColumn>`，`onProtocolEvent` 遇到区块事件直接按坐标
  写入/删除，不经过 Actor。写入的是不可变对象，任意线程读取都看到某一完整版本。
- 控制事件仍走原来的有界 Channel 与 Actor，溢出策略不变（已有用例继续通过）。
- `close()` 与 `finish()` 都清空缓存：世界状态只属于所属 Session，关闭后不再持有出生视野。
- 新增查询：`chunk(x, z)`、`blockStateAt(x, y, z)`、`biomeAt(x, y, z)`、`loadedChunks`。
  未加载返回 `null`，已加载但 Y 越界抛错——“没有数据”和“数据不合法”必须区分。

方块 id 是协议的全局状态 id，不在这里翻译成 `minecraft:stone`：那需要版本数据表，
属于下游客户端的资产。

### 3.3 harness：出生点原来在石头里

排查 CLI 摘要时发现玩家位置一直是 `(0, 0, 0)`。查 Minestom 源码：`Player` 构造时把重生点设为
`Pos.ZERO`，harness 只设置了 `spawningInstance`，于是玩家被传送到 y=0——石头平面内部。
这不是客户端问题，但会让“脚下方块”这类证据失去意义。harness 现在显式
`setRespawnPoint(Pos(0.5, 40, 0.5))`，玩家站在平台顶上。

### 3.4 CLI

首批区块到达且位置已同步后，打印一次摘要：

```text
chunk x=0 z=0 minY=-64 sections=24 cached=17 block=(0, 40, 0):0 below=1
```

脚下方块 id 1（石头），脚的位置 0（空气）——与 harness 的 40 格石头平面一致。

## 4. 与计划的偏差

| 计划 | 实际做法 | 原因 |
| --- | --- | --- |
| “实现最小只读区块” | 只解码方块、生物群系与 section 元数据 | 光照、高度图、方块实体各自有消费方（渲染、寻路），当前没有调用方 |
| 世界模型放进 `WorldSnapshot` | 区块独立缓存，`WorldSnapshot` 只保留进度计数 | 快照是“会话事实”，区块是高频率数据；混在一起会让每次区块到达都复制一次快照 |
| 区块作为普通事件投递 | 走独立缓存，不进有界队列 | 300+ 列出生视野会打满 256 容量的事件缓冲 |
| 处理区块卸载 | 已处理 `ClientboundForgetLevelChunkPacket` | 不做的话缓存只增不减，长时间移动会无界增长 |
| 维度切换（重生） | 未处理 | 需要 `ClientboundRespawnPacket` 与一个新的会话事件；当前没有调用方，留给后续增量 |

## 5. 验证

- `ChunkDecoderTest` 7 例：单值/间接/直接三种调色板、跨 long 边界、越界坐标、
  截断数据、尾随字节、非法位宽。全部为手工构造字节，不依赖服务端。
- `ClientSessionTest.出生后读到的方块状态与服务端一致`：
  - 服务端在 `(1, 41, 2)` 放金块，客户端读到的状态 id 与 `instance.getBlock(...).stateId()` 相等；
  - `(0, 39, 0)` 的地面方块也相等，`(1, 200, 2)` 与空气相等；
  - 未加载坐标返回 `null`；关闭后同一坐标也返回 `null`；
  - `minY=-64`、`sectionCount=24`，生物群系 id 落在 `minecraft:worldgen/biome` 的 66 项之内。
- CLI 实测（harness 端口 25607）：出生后位置 `(0.5, 40.0, 0.5)`，缓存 361 列，
  摘要行为 `chunk x=0 z=0 minY=-64 sections=24 cached=17 block=(0, 40, 0):0 below=1`。
- `./gradlew build` 通过，字节码与模块边界断言未变化。

## 6. 已知限制与下一步

- 光照、高度图、方块实体（NBT）与 `ClientboundChunksBiomesPacket` 的增量群系更新未解码。
- 重生/维度切换未处理：此时 `minY` 与 section 数量仍是旧维度的，解码会因长度不符而失败——
  这是 fail-fast，不是静默错误，但在支持重生前必须补上。
- 实体、背包、聊天与移动上行仍属于 M4 的后续增量。
- Android 运行时（M3）仍未验证；本增量的内存形态（每列几十 KB）需要在真机上复核。
