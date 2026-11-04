# M4c 实施记录：重生与维度切换

状态：已实施（2026-11-04）
范围：[roadmap.md](../roadmap.md) 第 7 节 M4 的第三个增量
结论：服务端换维度后客户端按新维度重新解码区块，旧维度的区块与实体不再残留；
位置要等新的同步包，本端不沿用旧坐标。

## 1. 起点：M4a 留下的 fail-fast

[M4a 实施记录](m4a-chunk-model.md) 明确写着：区块的 section 数量来自维度，而维度只在
`ClientboundLoginPacket` 时确定一次。服务端一旦重生或换维度，`minY` 与高度就变了，
继续按旧维度解码会在第一列区块上报“长度不符”并断开——错误是对的，但没有一条路可走。

触发这个路径的场景比想象中常见：死亡重生、下界传送门、`/execute in`、插件把玩家挪到
另一个实例，都会发 `ClientboundRespawnPacket`。

## 2. 改动

`ClientboundRespawnPacket` 携带的是 `PlayerSpawnInfo`，与 Login 包里的那份是同一个结构：
新的维度类型下标、世界名、游戏模式。因此处理顺序是确定的：

| 动作 | 原因 |
| --- | --- |
| 用新的维度下标查 `dimension_type` 注册表 | 换掉 `minY` 与 section 数量；查不到即失败，不猜默认高度 |
| 清空实体表 | 旧世界的实体不再属于本端视野，服务端会按新维度重新发 |
| 区块计数归零、`player_loaded` 复位 | 服务端会重发全部区块，首批到达后要再回一次 `player_loaded` |
| 上报 `Respawned(dimension, gameMode)` | 上层据此更新世界快照 |

`client-core` 侧：

- **缓存清理在网络线程上完成**（`onProtocolEvent`），而不是在 Actor 里。若在 Actor 里清，
  新维度的区块可能先被写入缓存、再被迟到的事件抹掉，表现为“新维度少了一块”。
- 世界快照换维度时把 `position` 置空、`chunksLoaded` 归零。位置保留旧坐标等于给调用方
  一个来自另一个世界的答案；新的位置同步包紧随其后到达。
- 新增事件 `ClientEvent.Respawned`，CLI 摘要会在换维度后重新打印一次。

## 3. 与计划的偏差

| 计划（M4a 时的设想） | 实际做法 | 原因 |
| --- | --- | --- |
| 只处理 `ClientboundRespawnPacket` 的维度字段 | 同时复位区块计数、实体表与 `player_loaded` | 三者都是“新世界”的一部分，漏一个就会在新维度里表现异常 |
| 在 Actor 里清理缓存 | 在协议回调里清理 | Actor 异步，清理可能晚于新维度的区块写入 |
| 位置沿用上一次同步值 | 置空，等新的同步包 | 旧坐标属于另一个维度，静默保留就是错误数据 |

## 4. 验证

`ClientSessionTest.重生切换维度后世界状态与缓存一起更新`（真实 Minestom）：

- 出生在主世界：区块 `minY=-64`；
- 服务端生成一个僵尸（客户端实体表里出现），随后把玩家 `setInstance` 到下界实例；
- 客户端收到 `Respawned(dimension=minecraft:the_nether)`，世界快照的维度同步更新；
- 新区块的 `minY=0`、`sectionCount=16`（256 格），同一坐标的地板方块从石头变成下界岩，
  与下界实例的 `stateId` 相等、与主世界不等；
- 旧维度的僵尸从实体表中消失，位置在容差内落到新维度的出生平台；
- 服务端日志出现第二次 `event=player.spawned` 与 `event=player.loaded`——说明 `player_loaded`
  确实按新世界重发了一次，而不是只发过一次就再也不发。

`./gradlew build` 通过；模块边界与字节码断言未变化。

## 5. 已知限制与下一步

- 只在“同一个服务端内换维度”的路径上验证过；跨服转移（`ClientboundTransferPacket`）仍然
  按关闭处理（`FOLLOW_TRANSFERS=false`），不跟随。
- 重生后的实体元数据、玩家名仍未处理，与 M4b 的限制一致。
- 死亡界面、`ClientboundPlayerCombatKillPacket` 与自动重生策略属于客户端行为，不在本库范围。
