# M4b 实施记录：实体跟踪

状态：已实施（2026-11-04）
范围：[roadmap.md](../roadmap.md) 第 7 节 M4 的第二个增量
结论：客户端能看到实体的生成、移动与移除，且自己累积出来的坐标与服务端一致；
实体元数据、玩家名与重生切换留给同一里程碑的后续增量。

## 1. 起点：协议库解析了包，但没有世界状态

与区块不同，MCProtocolLib 把实体包拆成了完整的 Java 对象（`ClientboundAddEntityPacket`、
`ClientboundMoveEntityPosPacket` …），字段语义也基本正确。因此本层要做的不是“再解析一遍”，
而是三件协议层之外的事：

1. **累积相对量**：`MoveEntityPos*` 的坐标是 `short / 4096` 的增量，只有叠加到上一状态才有意义；
2. **提供中立模型**：上层不应看到 `Vector3d`、`PositionElement` 或协议库的枚举；
3. **决定状态归属**：实体是高频状态还是里程碑事件，决定了它该走缓存还是事件流。

另外，26.2 已经**没有** `ClientboundAddPlayerPacket`：玩家与生物一样走 `AddEntity`，
只是 `type` 为 `PLAYER`。按旧版本记忆去找这个包会一直找不到。

## 2. 改动

### 2.1 protocol-core

| 文件 | 职责 |
| --- | --- |
| `world/TrackedEntity.kt` | 不可变实体快照：id、UUID、类型名、坐标、yaw/pitch、头部朝向 |
| `internal/McplConnection.kt` | 处理 8 类实体包，按 id 累积坐标并发出快照 |
| `ProtocolEvent.kt` | 新增 `EntitySpawned` / `EntityUpdated` / `EntityRemoved` |

处理范围：

| 包 | 语义 |
| --- | --- |
| `ClientboundAddEntityPacket` | 生成；同 id 重发直接覆盖（视野切换时服务端会整段重发） |
| `ClientboundRemoveEntitiesPacket` | 批量移除；只报告真正被移除的 id |
| `ClientboundMoveEntityPosPacket` | 相对位移 |
| `ClientboundMoveEntityRotPacket` | 只改 yaw/pitch |
| `ClientboundMoveEntityPosRotPacket` | 相对位移 + 朝向 |
| `ClientboundRotateHeadPacket` | 只改头部朝向 |
| `ClientboundEntityPositionSyncPacket` | 绝对坐标（传送后的权威位置） |
| `ClientboundTeleportEntityPacket` | 绝对为主，但坐标/朝向可标记为相对（`relatives`），需按位累积 |

实体表只在网络线程上读写，保留在适配层——因为“上一状态”正是本层累积出来的事实。
上层拿到的事件都带**叠加后的完整快照**，不需要自己重放增量。

未知 id 的增量包被丢弃而不是报错：增量只有叠加到已知状态上才有意义，而“尚未收到过该实体”
本身是合法状态（例如实体在本端完成出生之前就已存在）。真正的解析失败仍会在解码阶段抛出。

### 2.2 client-core

实体与区块同属**世界数据**，但处理方式按频率分开：

- **位置增量**（`EntityUpdated`）只写缓存，**不进事件流**。它每秒可达上百条，而每条都带完整
  快照——投递出去只会填满消费者的缓冲，却不带来新信息。
- **生成 / 移除**（`EntitySpawned` / `EntityRemoved`）走事件流，缓存先写、事件后发，
  因此消费者收到事件时一定能查到对应实体。

新增查询：`entity(id)`、`entities()`（快照复制）、`entityCount`。关闭、溢出与正常终止都会
清空实体表，与区块一致。

本端玩家不在实体表里：服务端不会为自己发实体生成包，自己的位置仍由 `WorldSnapshot.position`
表达。

### 2.3 CLI

会话事件新增两行输出，摘要行补上实体数量，便于现场核对：

```text
event=entity.spawned id=42 type=ZOMBIE x=3.5 y=40.0 z=3.5
chunk x=0 z=0 minY=-64 sections=24 cached=17 entities=1 block=(0, 40, 0):0 below=1
```

## 3. 与计划的偏差

| 计划 | 实际做法 | 原因 |
| --- | --- | --- |
| “实体增删与位置同步”一起做完 | 只做定位所需的字段（位置、朝向） | 装备、状态效果、生命值、元数据各有消费场景，当前没有调用方 |
| 位置更新也投递事件 | 只写缓存，按需查询 | 每秒上百条完整快照，投递没有增量信息，只会拖慢消费者 |
| 实体状态并入 `WorldSnapshot` | 独立缓存 + `entityCount` | 快照是会话事实，实体是高频世界数据；混在一起会让每次移动都复制一次快照 |
| 未知 id 视为协议错误并终止 | 丢弃增量包 | “尚未收到过该实体”是合法状态；错误解析仍会在解码阶段失败 |

## 4. 验证

`ClientSessionTest.实体的生成、移动与移除对客户端可见`（真实 Minestom）：

- 服务端在出生点区块生成僵尸；客户端收到 `EntitySpawned`，`type` 为 `ZOMBIE`，
  UUID 与坐标逐项与服务端一致；
- 服务端 `teleport` 后客户端换成新坐标与新朝向（`x=6.5`、`yaw=90`、`pitch=20`）；
- 服务端以速度驱动实体移动，逐 tick 发出 `short/4096` 增量；停止后客户端累积出的 `x`
  与服务端之差小于 `0.02`——漏加或重复叠加都会超出这个量级；
- `remove()` 后收到 `EntityRemoved`，缓存与快照都不再包含该 id；
- 关闭后 `entityCount == 0`。

实测该用例一次交互产生 10 次实体更新（传送 + 约 5 个 tick 的相对位移 + 头部朝向），
说明相对增量路径确实被走到，而不是全部退化为绝对同步。

`./gradlew build` 通过；模块边界与字节码断言未变化（协议库类型仍只出现在 `protocol-core`）。

## 5. 已知限制与下一步

- 实体元数据（`ClientboundSetEntityDataPacket`）、装备、状态效果、属性、生命值未处理；
  它们决定“这个实体长什么样”，与“它在哪”是两类需求。
- 玩家名未处理：需要 `ClientboundPlayerInfoUpdatePacket` 建立 UUID → 名称的映射，
  才能把 `type=PLAYER` 的实体显示成“谁”。
- 重生与维度切换仍未处理：`ClientboundRespawnPacket` 会换维度、换高度并让服务端重发
  全部区块，当前实现会继续用旧维度的 section 数量解码——这是 fail-fast，但在支持重生前必须补。
- 实体轨迹与插值属于下游渲染关注点；本库只提供离散快照与时间无关的状态。
