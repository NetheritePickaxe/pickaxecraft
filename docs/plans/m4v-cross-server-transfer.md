# M4v：跨服转移

## 目标

让“服务端要求本端换服”成为一条可处决的事实：调用方能拿到目标地址，也能选择由库完成
一次完整的换服。不追求会话状态的迁移——玩家数据、维度、背包都由新服务端重新下发，
本库只负责关旧连接、建新连接。

## 协议事实

- `ClientboundTransferPacket` 在 `packet.common`（不在 ingame 包里），字段只有 `host` 与 `port`。
  它在**配置阶段与 play 阶段都会出现**，协议库的 `ClientListener` 两个分支各写了一份相同的处理。
- **转移包不结束会话。** 服务端发完可以什么都不做（Minestom 的 `PlayerConnection#transfer`
  就只发一个包），原版客户端是收到后**自己**断开的。所以“等服务端断开再处理转移”会永远等下去。
- 协议库默认 `FOLLOW_TRANSFERS = true`：它会在回调里 `new ClientNetworkSession(...)` 并
  `connect(true)`，把 flag 复制过去，然后断开旧会话。那个新会话不归调用方管理，也关不掉。
  本项目在 M1 就显式设置成 false（见 `docs/protocol-contract.md` 2.7）。
- 库不给转移留回执：没有“已接受转移”的上行包。换服成功与否只能由新连接自己证明。

## 设计

| 位置 | 职责 |
| --- | --- |
| `protocol/ProtocolEvent.kt` | `TransferRequested(host, port)`：只上报目标，不建连接 |
| `internal/McplConnection.kt` | `ClientboundTransferPacket` → 事件；两阶段共用同一个 when 分支 |
| `client/ClientEvent.kt` | `TransferRequested(host, port)` |
| `client/TransferRequest.kt` | 目标值对象；`address` 惰性校验成 `ServerAddress` |
| `client/Session.kt` | `transferRequest` 快照（事件之外的旁路），不随会话结束清空 |
| `client/MinecraftClient.kt` | `followTransfer(session, username)`：关旧会话 → 连新地址 → 返回新会话 |
| `app/Main.kt` | `--exec` 执行命令、`--follow-transfer` 跟随一次；`collectSession` 可提前切断 |
| `testing:minestom` | `/pickaxetransfer` 命令 + 同名重连用例 |

三个决定：

1. **上报与执行分家。** 适配层只上报地址；`Session` 只提供快照；真正建连接的是
   `MinecraftClient`。理由是 `Session` 代表“一次连接”，它没有办法在自己内部长出第二条连接，
   由它自己换服会让生命周期不可判定。
2. **目标地址原样转达，收包时不校验。** `TransferRequest.address` 才做 `ServerAddress` 的校验，
   这样可以拒绝把解析错误伪装成连接失败；反过来在收包处校验，一个坏包就会结束整个会话，
   代价远大于收益。
3. **`transferRequest` 不随会话结束清空。** 会话因转移关闭之后，调用方正是靠它回答
   “接下来连哪里”。断开的文本原因不能当判据——`disconnect.transfer` 只是库给的一句翻译键，
   真实服务端给的文案各不相同。

## 测试门控

- 联调 `转移请求只上报地址，换服由调用方决定`：
  - 服务端侧 `event=server.transfer`；
  - 客户端 `ClientEvent.TransferRequested` 与 `Session.transferRequest` 两侧一致；
  - 收到后 200ms 内 `SessionState` 仍是 `PLAY`（证明库没有偷偷换服）；
  - `followTransfer` 之后新会话 `PLAY`、旧会话 `CLOSED`、旧会话的 `transferRequest` 仍在；
  - 新会话能重新收到区块；
  - 服务端侧的第二次 `player.configuring` / `player.spawned` 是“旧连接真的关掉了”的独立证据
    （同名重连成功）。
- 非法目标（端口 0）在 `TransferRequest.address` 处抛 `IllegalArgumentException`。

## 实现时发现的事实

- **`collect` 没法提前结束。** CLI 最初用 `session.events.collect { ... }`，收到转移后想换服，
  但 collect 只在流结束时返回，而转移不结束会话，于是永远等下去（实跑只看到
  `event=transfer`，没有 `event=transfer.connected`）。改用 `transformWhile`，在处理完事件后
  返回 `false` 切断收集。
- 切断收集后这个 `Session` 不能再次被消费：`events` 是 `Channel.consumeAsFlow()`，
  单消费者。用完即弃正好符合“换服后换新会话”。
- Minestom 是进程级单例，一个 JVM 里跑不了第二个服务端。用例把转移目标指向**自己**：
  同名重连既验证了换服链路，又顺带证明旧连接被关掉（否则新连接会被同名顶掉）。
- Minestom 的 `PlayerConnection#transfer` 只发 `TransferPacket` 就结束，不踢人、不断开——
  这正是“转移不结束会话”的实证。
- `ProtocolEvent.TransferRequested` 的类在 `packet.common`，与 ingame 包不在同一个包下，
  import 顺序容易插错位置（按字母序应在 `packet.common.serverbound` 之前）。
