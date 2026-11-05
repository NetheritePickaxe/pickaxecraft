# M4x：骑乘与载具

## 目标

让“我在某个载具上”成为可读、可上行、可下行的完整事实：调用方能知道自己在骑哪个实体、
谁坐在自己车上，也能在骑乘时继续移动。不做载具物理与座位偏移——那是下游渲染与预测的
责任，本库只保证协议层的事实正确。

## 协议事实

- 协议里**没有**「上载具」「下载具」两个包。只有 `set_passengers`，按载具下发一份**完整**
  的乘客列表；空列表就是下车。成为乘客的判据只能是「自己的实体 id 出现在某条列表里」。
- `move_vehicle` 双向都**不带实体 id**：下行是服务端下发的权威坐标，上行是本端声明的
  载具坐标，两者都只可能指“当前乘坐的载具”。因此适配层必须自己维护这份映射，
  否则下行坐标无处落地。
- 载具坐标是**绝对量**（完整 double），实体移动包是 1/4096 的相对量——两者不能共用叠加逻辑。
- 原版客户端在 `isPassenger()` 时**跳过位置同步**：骑乘时不发 `move_player`。发了不会报错，
  只会被服务端忽略，客户端于是“以为自己动了”。
- Minestom 从不**下发** `move_vehicle`（`PlayerVehicleListener` 把下行那段注释掉了，只有上行），
  但会正确**应用**上行（`vehicle.refreshPosition`）。联调因此分成两条证据：上行看服务端收到的包
  与载具落点，下行由 harness 直接发一个真实形状的包。
- 服务端不承诺移除载具前补一条空乘客列表。实体消失时要顺带清掉映射与“我在骑它”。

## 设计

| 位置 | 职责 |
| --- | --- |
| `protocol/ProtocolEvent.kt` | `PassengersChanged(vehicleId, passengerIds)`、`VehicleMoved(x,y,z,yaw,pitch)` |
| `internal/McplConnection.kt` | `set_passengers` → 事件 + 维护 `selfVehicleId`；`move_vehicle` → 更新载具实体 + `VehicleMoved` |
| `ProtocolConnection.kt` | `steerVehicle(...)` 上行；不在载具上时拒绝 |
| `client/ClientEvent.kt` | `PassengersChanged`（原始事实）、`VehicleChanged(vehicleId?)`（派生结论） |
| `client/Session.kt` | `vehicle`（StateFlow）、`passengers(id)`、`steerVehicle(position)`、`checkOnFoot` |
| `app/Main.kt` | `--steer x,y,z`；`event=passengers/vehicle/ride` 输出 |
| `testing:minestom` | `/pickaxeride`（上船 / `stop` 下船 / `move` 下发权威坐标）+ 包观察与落点查询 |

三个决定：

1. **两份“我在骑谁”的映射，各管一段。** 适配层那份用于解析收包（网络线程），会话层那份
   是对外快照（`StateFlow`）。合成一份要么让会话层依赖网络线程的内部状态，要么让
   适配层替调用方维护状态机——两者都比重复四行判断更糟。
2. **骑乘时 `moveTo` / `look` 直接失败。** 静默发送的后果是“服务端不理会、本地以为动了”，
   而这类问题在现场几乎无法从日志里看出来。显式抛错把问题挡在调用点。
3. **下行坐标只推进位置，不改朝向。** 乘客可以转头看别处，载具的朝向属于载具。因此
   `world.position` 的 x/y/z 跟着载具走，yaw/pitch 保留本端视角——这也正是原版
   `handleMoveVehicle` 只 setPos 的原因。

## 测试门控

- 联调 `骑乘时乘客关系可读，载具移动走独立包`：
  - 服务端侧 `event=server.ride`，客户端 `ClientEvent.VehicleChanged(vehicleId)`；
  - `PassengersChanged.passengerIds` 与 `Session.passengers()` 是同一份列表，且只含自己；
  - 载具实体已被跟踪，类型是 `OAK_BOAT`；
  - 骑乘时 `moveTo` 抛 `IllegalStateException` 且信息里点出 `steerVehicle`；
  - `steerVehicle` 之后服务端侧 `event=player.vehicle` 的五个数值与发出的一致，
    并且 `ridePosition` 落到同一坐标（Minestom 真的应用了）；
  - harness 下发的权威坐标改写载具快照的 x/y/z/yaw，`world.position` 只跟位置；
  - `stop` 之后是空列表：`vehicle` 变 null、`passengers()` 变空、`moveTo` 恢复可用。
- 单测在协议层没有新增：这一段没有解析逻辑（解析由协议库完成），推导逻辑只有四行判断，
  由联调用例覆盖。示例模块的 `ChunkMeshBuilderTest` 与本项无关。

## 实现时发现的事实

- `selfVehicleId in packet.entityIds` 编译不过：`Int?` 不能直接用于 `IntArray.contains`。
  取出到局部变量再判空，顺带也让“先读快照再比较”的意图更明显。
- `handle` 里对 `EntityRemoved` 补了一条“载具没了等同于下车”。服务端移除载具时**不保证**
  先发空乘客列表；只清缓存不发事件的话，`vehicle` 会静默变成另一个值而调用方收不到通知。
- 下行链路的证据只能由 harness 造：Minestom 自己不发 `move_vehicle`。这不算伪造——
  发的是真实形状的包，验证的是本端的解释；与聊天用例里直接发 `SystemChatPacket` 同理。
- 载具坐标下发的朝向与本端 `steerVehicle` 的朝向是两件事：用例因此只断言
  “位置跟上了、朝向没被改”，而不是“朝向等于我发的那份”。
