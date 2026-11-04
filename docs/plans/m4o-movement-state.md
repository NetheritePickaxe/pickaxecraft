# M4o 实施记录：运动状态上行与服务端能力

日期：2026-11-04

## 目标

把「冲刺 / 潜行 / 飞行」三条上行链路做成公共 API，并把服务端权威的 `player_abilities`
读回来；三者都不得靠本地猜结果。

## 协议事实

- 26.2 的按键在 `player_input` 包里：一个字节带 forward / backward / left / right / jump /
  shift / sprint 七位。这是**状态包**，服务端按最后一次收到的字节为准，所以每次改动都要
  整份重发，不能只发变化的那一位。
- 冲刺还有独立的 `player_command`（`START_SPRINTING` / `STOP_SPRINTING`），包里带本端
  **实体 id**（Login 包给出，适配层记在 `selfEntityId`）。只发 input 位不改服务端移动速度。
- 飞行请求走 `player_abilities` 上行包（只有一个 flying 位）；服务端**不回执**，
  允许与否由它随后下发的权威 `player_abilities` 快照表达（flags：1 无敌 / 2 飞行中 /
  4 可飞 / 8 创造，外加两个速度倍率）。
- 原版客户端冲刺与潜行互斥：开启冲刺时清掉 shift 位，关闭时保留当前潜行状态。

## 实现

- `MovementInput`（七位不可变快照，互斥方向在本地校验）、`PlayerAbilities`（服务端快照）。
- `McplConnection`：`movementInput` 用 `AtomicReference` 整体替换（调用线程与网络线程都会
  读），`setMovementInput` / `setSneaking` / `setSprinting` / `setFlying` 统一走 `sendInPlay`。
- `Session`：`movementInput` 与 `abilities` 两个 StateFlow。前者是本端**声明**，后者是服务端
  **权威**；`setFlying` 不改任何本地状态，等 `ClientEvent.AbilitiesChanged`。
- 新生/重生/关闭/溢出时把两份状态复位，避免把上一条命的按键带到下一次连接。
- CLI：`--sneak` / `--sprint` / `--fly` 与 `event=abilities` 输出。

## 验证

- `MovementInputTest`（3 例）：默认全空、互斥方向被拒、copy 保留其它位。
- 联调 `冲刺潜行与飞行的上行包服务端可见且能力由服务端权威下发`：harness 用
  `PlayerPacketEvent` 登记三类上行包（这是「成功没有回执」时唯一的服务端证据），
  客户端断言 shift 位、sprint 位 + 互斥、`START/STOP_SPRINTING`、飞行请求；
  harness 再下发权威能力包，断言 `abilities.flying` 只在之后变 true。
- 踩坑：`awaitMilestone(SPAWNED)` 是服务端事实，客户端的 `JoinedWorld` 可能还在 inbox 里
  排队，`selfEntityId` 要等待式取值（与 M4k 的缓存/事件竞态同类）。

## 边界

不做本地物理与速度预测，不声明鞘翅飞行（`START_ELYTRA_FLYING`）与马匹跳跃；
能力里的两个速度是倍率，不是格/秒。
