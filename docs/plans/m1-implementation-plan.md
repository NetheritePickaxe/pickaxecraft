# M1 实施计划：自有客户端 API 与连接生命周期

状态：已实施（2026-11-03），偏差与验证证据见第 6 节
日期：2026-11-03

## 1. 当前基线

本计划以提交 `273927e` 为实现基线。当前不是“客户端库已基本可用”，而是“协议依赖组合和测试宿主已经验证”。

| 已有能力 | 当前证据 | 不能据此推断的结论 |
| --- | --- | --- |
| 工程可重复构建 | 本地 `clean build` 成功；GitHub Actions 运行 `41555518332` 成功 | Android/iOS 可运行 |
| 协议版本一致 | MCProtocolLib 26.2 / 协议 776 与 Minestom `2026.10.07-26.2` 对账 | 后续版本升级无成本 |
| Status 查询 | `ClientInteropTest` 真实查询并断言版本与协议号 | 调用方已能通过自有 API 查询 |
| 离线登录进入 GAME | 测试断言 `ProtocolState.GAME`、注册表包非空且连接保持 | 玩家已经出生、世界状态已经同步 |
| 测试宿主可启停 | 生命周期测试通过；SIGTERM 用例由 Linux CI 执行 | 自有客户端的 Netty 资源已经正确释放 |
| CLI 帮助与版本输出 | `app` 已实现 | CLI 已能连接服务端 |

当前缺口：

- 没有 `client-core` 模块和稳定的调用方入口。
- `protocol-core` 只有版本常量，没有适配层。
- 联调测试直接驱动 MCProtocolLib，尚未验证本项目的抽象和生命周期。
- `player.spawned` 尚无客户端侧断言；本轮日志只出现 `player.configuring`，出生链路属于 M2。
- Android/iOS 均无运行时证据；libGDX、渲染和单人游戏尚未开始，也不属于本轮。

## 2. 本轮目标与边界

本轮只完成 M1：让 Desktop 调用方通过自有 API 查询状态、离线登录进入 GAME、观察明确的生命周期事件并可靠关闭。

必须同时完成一次提前的 Android Status 探针，目的是尽早暴露 ART/D8/Netty 风险。探针失败不否定 Desktop M1 成果，但在形成处置决策前，不进入 M2 的大规模世界状态实现。

本轮不做：

- 玩家出生、传送确认、区块批次确认和世界模型；这些属于 M2。
- 聊天、移动、背包和实体同步。
- 在线认证、自动重连和多协议版本。
- iOS 完整验证、libGDX 工程、ANGLE/Vulkan 或内嵌 Minestom 单人模式。
- 为假想需求增加 `client-api`、`transport-*` 或独立世界模块。

## 3. 实施顺序

### P0：把协议库行为变成明确契约

先基于现有联调用例核对并记录：

- 连接成功、连接失败、服务端断开和主动 `disconnect` 的回调顺序。
- 默认 `ClientListener` 负责的 Login/Configuration 状态切换与 KeepAlive 自动应答范围。
- 包接收、包发送和异常回调所在线程。
- `ClientNetworkSession` 的 executor、`EventLoopGroup`、channel 和关闭方法的归属。
- Status 一次性连接与 Login 长连接分别需要释放哪些资源。

产出一份简短的适配契约和针对性测试，不另建空模块。契约必须指出哪些行为由协议库保证、哪些由本项目实现；不能靠阅读一次源码后假定所有版本都保持不变。

完成判据：连接/断开/失败路径都有可重复测试；文档能回答“谁应答、谁关闭、回调在哪个线程”。

### P1：在 `protocol-core` 建立最小适配层

实现仅够 M1 使用的内部协议能力：

- Status 查询与离线登录。
- 自有协议状态、连接结果、断开原因和错误上下文。
- 协议库回调到自有事件的转换。
- 显式关闭句柄；默认每个连接独占其网络资源并由适配层释放，不建立进程级全局 `EventLoopGroup`。
- 所有 MCProtocolLib/Netty 类型保持在该模块内部，公共签名不得暴露它们。

回调不得直接执行调用方业务代码。需要跨线程的信息进入自有有序通道；协议库已经同步处理的 KeepAlive 不再重复 enqueue 或应答。

完成判据：现有 Status/Login 行为可通过适配层复现；`verifyModuleBoundaries` 和 Java 21 字节码检查继续通过。

### P2：新建 `client-core` 并实现会话骨架

`client-core` 只依赖 `protocol-core`，提供最小公共 API：

- `MinecraftClient`、`ClientOptions`、`Session` 只读视图。
- 明确的 `ClientState`/`SessionState` 与结构化 `ClientEvent`。
- `connect`、Status 查询和幂等 `close`；挂起 API 不阻塞 Netty 线程。
- 每个会话独立的有序 Actor、协程作用域和有界事件缓冲。
- 连接失败、超时、服务端断开、协议异常和本地关闭使用可区分的错误类型。
- 事件溢出不得静默丢弃；M1 采用终止受影响会话并报告错误的保守策略，后续再按事件类别细化。

先写生命周期测试，再补实现。至少覆盖：

- 连接成功后进入 GAME，关闭后进入终态。
- 无效端口、连接超时和服务端主动断开。
- 重复关闭、连接中取消和关闭时仍在处理事件。
- 两个客户端同时连接时，状态、用户名和事件流互不串用；关闭其中一个不影响另一个。
- 慢事件消费者不阻塞协议库回调，也不会形成无界队列。

注册表与世界模型的隔离在 M2 有实际模型后验证，不在 M1 创建占位状态。

### P3：迁移联调测试与 CLI

- `ClientInteropTest` 改为只调用 `client-core` 公共 API，删除测试中的 MCProtocolLib 会话装配。
- 保留 Status 776/26.2、GAME、注册表接收和连接保持断言。
- 增加两个客户端隔离与服务端主动断开的真实联调。
- CLI 增加 `status` 与 `connect` 最小命令，支持 host、port、username 和有限超时。
- CLI 只在组合根使用 `runBlocking`；Ctrl+C 先关闭客户端，再结束进程。

完成判据：生产代码和联调调用方都不需要直接接触数据包、Netty 或 MCProtocolLib；测试失败时能指出失败阶段和原因。

### P4：提前执行 Android Status 探针

在 P1 能查询 Status 后即可开始，不等待整个世界模型：

- 使用与发布产物一致的 Java 21 字节码和依赖集合。
- 在模拟器或真机的 ART 路径执行一次真实 Minestom Status 查询。
- 记录 AGP/D8 或等效构建工具版本、设备/API 级别、Netty 实际运行时和完整异常。
- 验证连接成功与失败两种路径均能退出并释放线程。
- 探针代码保持最小且可复现，不据此创建正式 Android 应用或宣称完整登录支持。

结果门控：

- 成功：只记录“Status 已验证”，完整登录仍留到 M3。
- 失败：记录具体不兼容点，评审自带 JVM、上游修复、受控 fork/传输适配等路径；在决策完成前不扩大 M2 的世界状态投入。
- 未执行：不得写成“等待真机但理论上支持”，路线中保持未知。

### P5：收尾验证与文档同步

- 运行 `./gradlew clean build` 并确认 CI 对同一提交成功。
- 检查 `client-core` 的编译类路径不包含 MCProtocolLib、Netty、Minestom 或 UI 类型。
- 确认 `protocol-core` 与 `client-core` 都是 Java 21 字节码，`app` 和测试宿主仍为 Java 25。
- 更新架构、项目结构、路线和协议库适配契约；记录 Android 探针的实际结果。
- 单独提交本轮变更，不把未验证的平台或出生能力写进完成声明。

## 4. 依赖关系与执行切分

```text
P0 协议行为契约
  └─► P1 协议适配层
        ├─► P2 客户端核心 ──► P3 联调迁移与 CLI ─┐
        └─► P4 Android Status 探针 ──────────────┴─► P5 收尾
```

每个阶段保持构建可用并独立验证。P4 可以提前暴露平台风险，但不以并行开发为由同时扩张多个功能面；当前执行顺序仍为 P0 → P1 → P2/P4 → P3 → P5。

## 5. M1 完成定义

只有同时满足以下条件才标记 M1 完成：

1. 外部调用方只依赖 `client-core` 即可完成 Status 和离线登录到 GAME。
2. 联调测试不再直接装配 MCProtocolLib，两个会话隔离和资源关闭有自动测试。
3. CLI 能执行真实 Status/Login，并具有有限超时和明确退出行为。
4. 所有共享产物保持 Java 21，协议库类型不穿透公共 API。
5. Android Status 探针已有真实成功或失败证据，并已记录下一步处置。
6. 本地干净构建与 Linux CI 均通过。

M1 完成不代表玩家出生、世界同步、移动端完整支持或图形客户端可用。上述能力分别由后续里程碑验收。

## 6. 实施记录（2026-11-03）

### 6.1 与计划的偏差

| 计划 | 实际做法 | 原因 |
| --- | --- | --- |
| P0 用针对性测试记录协议库行为 | 以源码核对 + 现有联调证据形成 `docs/protocol-contract.md`，契约改由适配层用例守住 | P3 要求联调用例不再直接装配协议库；若为 P0 保留一个直接驱动协议库的用例，会与该要求冲突。契约条款逐条对应到 `ClientSessionTest` 的用例 |
| P1 通过 `addListener` 观察包 | 在 `McplConnection` 覆写 `callPacketReceived`/`callEvent`，在 `super` 之后观察 | 协议库的 `ClientListener` 在 `newClientSession` 时加入，任何调用方监听器都排在它**前面**，在 `packetReceived` 里读到的仍是切换前的状态 |
| P1 未指定包处理线程 | 传入直接执行器（`Runnable::run`），并覆盖 `getEventLoopGroup()` 让每个连接自持单线程组 | 协议库默认会引入进程级共享的包处理线程池与 `EventLoopGroup`；那会让同一会话的回调出现在两个线程上，也让“关闭后线程释放”无法断言 |
| P2 事件溢出时终止会话并用事件报告 | 溢出时由回调线程直接收敛状态、取消 Actor 并关闭事件流 | 消费者卡住时 Actor 正挂在向事件流写入上，读不到 inbox 已关闭，也就发不出终止事件；此时“报告”只能是异常而不是事件 |
| P4 在模拟器/真机跑 Status | 只做 D8 反糖（`tools/android-probe/dex.sh`） | 执行环境无硬件虚拟化、无可用设备，模拟器无法启动。按“未执行不得写成理论上支持”的规则，运行时仍是未知 |

### 6.2 关键实现决定

- `protocol-core` 不引入协程：纯回调适配层，调度器选择留给宿主。
- `ProtocolEvent` 的四个事实（Connected / PhaseChanged / RegistryData / StatusReceived / Closed）足以支撑 M1；
  原始数据包不上浮，注册表只暴露 id 与条目数，实体内容留给 M2 的世界模型。
- 失败在适配层归层（`CONNECT`/`NETWORK`/`PROTOCOL`），上层映射为 `ClientException` 的三种类型，
  调用方不需要识别协议库或 Netty 的异常类。
- `client-core` 的事件流是单消费者；`Session.close()` 阻塞到网络资源回收（有界，约一次
  `shutdownGracefully` 的时间），因此“关闭后线程已释放”可以直接断言。
- CLI 的 `runBlocking` 只出现在 `main` 调用的两个命令函数里；Ctrl+C 由 shutdown hook 关闭客户端。

### 6.3 M1 完成定义核对

| 条件 | 结果 |
| --- | --- |
| 1. 只依赖 `client-core` 完成 Status 与离线登录到 GAME | 满足：`ClientSessionTest` 全部经由公共 API，CLI 也只用公共 API |
| 2. 联调不再直接装配协议库，两会话隔离与资源关闭有自动测试 | 满足：旧 `ClientInteropTest` 已删除 |
| 3. CLI 可执行真实 Status/Login，有有限超时与明确退出 | 满足：`status` / `connect`，`--timeout` 默认 15 秒，失败退出码 1、参数错误 2 |
| 4. 共享产物 Java 21，协议库类型不穿透 | 满足：`verifyBytecode`（65）与 `verifyModuleBoundaries`（三项禁止）通过；`client-core` 编译类路径只有 project + stdlib + coroutines |
| 5. Android Status 探针有真实成功或失败证据 | **部分满足**：构建链证据已取得（D8 无错误、minSdk 26、重复类与 HexFormat 结论）；运行时证据缺失，留给 M3 |
| 6. 本地干净构建与 Linux CI 通过 | 本地 `./gradlew clean build` 通过；CI 见推送后的运行记录 |

结论：M1 的客户端能力已交付并可用；Android 只完成构建链探针，因此路线图中 Android 仍标为未验证。
