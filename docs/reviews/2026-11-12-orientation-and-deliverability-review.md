# PickaxeCraft 现状审查：代码导览、路线图评估与可交付性判定

状态：审查记录（不改变代码）
日期：2026-11-12
审查对象：`main` @ `3961060`，外加工作区未提交改动（仅作附录观察，不作为审查基线）
审查范围：架构、模块结构、实现质量、路线图、发布工程、与同类项目对比

**一句话结论：架构方向正确，工程纪律高于同类项目平均水平；但当前只能作为「受控内部使用的 `0.1.0-SNAPSHOT` headless 库」交付，尚不满足公开发布或生产依赖的门槛。阻断项是 Netty 的安全版本窗口，以及移动平台声明缺少运行时证据。**

---

## 0. 审查方法与限制

### 0.1 做了什么

| 动作 | 命令 / 方法 | 结果 |
| --- | --- | --- |
| 全量构建 | `.\gradlew.bat build --console=plain` | `BUILD SUCCESSFUL` |
| 强制重跑测试 | `.\gradlew.bat :protocol-core:test :samples:libgdx-viewer:test :testing:minestom:test --rerun-tasks --console=plain` | `BUILD SUCCESSFUL in 2m 52s` |
| 测试汇总 | 解析 23 个 `TEST-*.xml` | `tests=162 failures=0 errors=0 skipped=1` |
| 本地发布 | `.\gradlew.bat :protocol-core:publishToMavenLocal :client-core:publishToMavenLocal` | 成功，产物见 `~/.m2/repository/org/pickaxe` |
| CLI 冒烟 | `.\gradlew.bat :app:run --args="--version"` | 输出 `minecraft 26.2` / `protocol 776` / `minestom 2026.10.07-26.2` |
| 发布 POM 检查 | 读取 mavenLocal 中的 POM 与 jar 内容 | 见 §5.3 |
| 文档与代码交叉核对 | `docs/` 全量、`Session.kt`、`McplConnection.kt`、构建约定 | 见正文引用 |

唯一的跳过是 `ShutdownHookTest`：Windows 没有 SIGTERM，用例主动跳过，由 Linux CI 覆盖。这一设计是合理的，但也意味着**本机通过不等于该路径已验证**。

### 0.2 没有做什么

- 没有修改任何代码或构建脚本。
- 没有访问外网：`webfetch` 对 GitHub raw 超时，`websearch` 返回 HTTP 402。因此 §4 的同类项目对比基于审查者既有知识，不基于本次重新抓取的上游资料；对具体版本号、近期提交等易变事实不作断言。
- 没有 Android/iOS 设备或模拟器。M3 的运行时结论只能引用仓库内既有记录，不能新增证据。

### 0.3 判定用语

- **可交付**：在明确的使用边界内，下游可以依赖它完成真实工作，失败模式可观察、可恢复。
- **有条件可交付**：能跑通主路径，但存在需要下游知情并承担的限制。
- **不可交付**：存在会让下游付出不可接受代价的问题（安全、数据正确性、兼容性），或缺少发布所需的基本工程产物。

---

## 1. 结论摘要

### 1.1 三级判定

| 交付层级 | 判定 | 理由 | 前置条件 |
| --- | --- | --- | --- |
| 内部实验 / 自用脚本 | ✅ 可交付 | 主链路完整，162 项测试通过，CLI 可跑 | 锁定精确 SNAPSHOT 版本 |
| 受控下游（同组织、离线服务器、Gradle 消费） | ⚠️ 有条件可交付 | API 能覆盖登录、出生、世界读取、背包、聊天、实体与上行动作 | 接受 0.x 不兼容、仅离线、仅 Desktop JVM |
| 公开发布 / 生产依赖 | ❌ 不可交付 | Netty 处于已知 CVE 修复前窗口；移动平台未验证；发布产物缺 javadoc、签名与 POM 元数据；在线认证与加密未实现 | 关闭 §5 的 P0 与 P1 |

### 1.2 五个最重要的判断

1. **架构不需要推倒重来。** `protocol-core → client-core → 宿主` 的分层、单 Actor + 有界队列、sealed 事件、明确的错误分类，都是这类库的正确形态；与 Minosoft、Azalea 等项目的分层同构。
2. **路线图主干正确，但风险排序与项目自己的原则冲突。** 文档一方面写「先验证风险最高的链路」，另一方面把 Android 运行时验证（M3）留在最后，期间完成了 20 多个 M4 增量。
3. **唯一必须马上处理的是 Netty 版本。** 其余问题大多是「发布工程」与「文档卫生」，可以按计划补齐；Netty 4.2.1 是已经写在决策文档里的已知安全债务。
4. **发布工程是最后一公里，不是架构问题。** 没有 Dokka、POM 缺元数据、没有签名、没有依赖锁定，这些都能在数天内补齐，不影响核心设计。
5. **测试覆盖「行为正确」，但「确定性」和「分层」不足。** 所有 `client-core` 行为只在 `testing:minestom` 的 E2E 中覆盖，且 E2E 依赖轮询等待；这对 CI 稳定性与问题定位都是长期成本。

---

## 2. 代码库导览

### 2.1 模块与规模

统计口径：`src/**/*.kt` 的总行数（含空行与注释）。

| 模块 | 类型 | 字节码 | 发布 | 主代码 | 测试代码 | 职责 |
| --- | --- | --- | --- | ---: | ---: | --- |
| `:protocol-core` | Kotlin JVM | Java 21 (65) | 是 | 48 文件 / 8,578 行 | 19 文件 / 1,925 行 | MCProtocolLib 的唯一适配层：事件转换、世界解码、资源回收 |
| `:client-core` | Kotlin JVM | Java 21 (65) | 是 | 10 文件 / 2,080 行 | 0 | 公共客户端 API：会话 Actor、事件流、只读快照 |
| `:app` | Kotlin JVM 应用 | Java 25 (69) | 否 | 1 文件 / 969 行 | 0 | Desktop CLI 与联调入口 |
| `:testing:minestom` | Kotlin JVM 测试工具 | Java 25 (69) | 否 | 3 文件 / 1,256 行 | 3 文件 / 2,645 行 | 可重复启动的 Minestom 服务端 + E2E 用例 |
| `:samples:libgdx-viewer` | Kotlin JVM 示例 | Java 21 (65) | 否 | 6 文件 / 202 行 | 1 文件 / 100 行 | headless 网格化示例，验证公共 API 足以产出渲染数据 |

主代码合计约 13,000 行，测试代码约 4,700 行。对一个从零起步、目标协议为 776 的客户端库来说，这个体量是克制且聚焦的：没有为未来概念预建空模块，也没有把渲染或服务端逻辑塞进核心库。

### 2.2 数据流

```text
MCProtocolLib / Netty EventLoop 线程
        │  解包、类型转换（不做业务判断）
        ▼
McplConnection（protocol-core）
        │  ProtocolEvent
        ▼
有界 Channel（容量默认 256）
        │  trySend，写满即终止会话
        ▼
Session Actor（client-core，单协程、顺序处理）
        │
        ├──► 可变缓存：chunks / entities / players / containers / chatLog …
        ├──► 只读快照：StateFlow、不可变 data class
        └──► ClientEvent（单消费者事件流）
```

这条链路的关键设计点都成立：

- **线程边界清楚。** 网络线程只做解包与转换，业务判断全部在 Actor 侧；`docs/architecture.md` §5 把这一点写成了显式约定。
- **背压显式。** 队列有界，写满时以 `ClientException.EventOverflow` 终止会话，而不是静默丢弃。这符合「fail fast，避免静默降级」。
- **状态与事件分工清楚。** 位置、区块、背包等高频事实走缓存或独立事件，不挤占事件队列；聊天这类「历史」有独立上限（默认 200 条）。
- **关闭幂等且释放彻底。** `Session.finish()` 清空所有缓存、完成等待中的补全请求、关闭连接，`EventLoopGroup` 随会话回收；`MinecraftClient.close()` 取消协程作用域。

### 2.3 公共 API 与错误模型

`MinecraftClient` 只有四个入口：`status`、`connect`、`followTransfer`、`close`。`Session` 暴露状态查询、世界读取、聊天、背包、实体、命令补全与上行动作。`ClientException` 是 sealed class，区分连接失败、网络错误、服务端断开、协议错误、超时与事件溢出，调用方可以按类型选择处理路径。这是正确的库 API 形态：**类型即契约，不要求调用方解析字符串**。

需要指出的是规模：`Session.kt` 1,485 行、83 个 public 成员；`ClientEvent` 有 35 个变体，`ProtocolEvent` 有 44 个。这已经接近「一个类承担整个客户端能力面」的边界。

### 2.4 构建门控

这个项目最值得肯定的工程实践，是把架构约束做成了**可执行的构建检查**，而不是只写在文档里：

| 门控 | 实现 | 作用 |
| --- | --- | --- |
| 字节码版本 | `verifyBytecode`，共享模块断言 major 65，CLI/测试断言 69 | 防止 Java 25 字节码泄漏到 Android 可消费产物 |
| 模块边界 | `verifyModuleBoundaries`，检查 `compileClasspath` 不含 `io/netty/`、`net/minestom/`、`org/geysermc/mcprotocollib/` | 防止协议实现穿透到上层 |
| 显式 API | `explicitApi()` | 公共 API 必须显式声明可见性，减少意外暴露 |
| 版本唯一来源 | `gradle/libs.versions.toml` + `ProtocolVersion` 常量对账 | 避免版本散落 |
| 测试日志 | 失败时带异常消息 | CI 上能判断断言内容 |

在同类 JVM 客户端项目里，能把这些做成 `check` 依赖的不多。这是本项目相对成熟的地方。

### 2.5 文档体系

`docs/` 下有架构、结构、路线图、协议契约、2 份决策记录、30 余份里程碑实施记录。`protocol-contract.md` 尤其有价值：它把「哪些行为由 MCProtocolLib 保证、哪些由本项目实现」逐条写清楚，并给出升级检查清单。这类文档在同类项目中通常缺失。

问题在一致性，见 §3.5。

---

## 3. 路线图审计

### 3.1 逐里程碑状态

| 里程碑 | 计划 | 实际 | 证据 | 判定 |
| --- | --- | --- | --- | --- |
| M0 工程基线与协议后端 | 建立模块、字节码纪律、协议适配 | 已完成 | `verifyBytecode` / `verifyModuleBoundaries` 在 `build` 中执行 | ✅ |
| M1 客户端核心骨架 | 自有 API、连接生命周期、CLI | 完成，Android 运行时除外 | `MinecraftClient` / `Session` / CLI；D8 反糖探针通过 | ✅（有保留） |
| M2 Minestom 离线登录 MVP | 进入 PLAY、出生、区块确认 | 已完成 | E2E 断言 `player.spawned`、`player.loaded`、方块对账 | ✅ |
| M3 移动平台门控 | Android/iOS 运行时验证 | **阻塞** | `docs/plans/m3-android-runtime.md`：无虚拟化、无设备 | ⛔ 未完成 |
| M4 可使用的客户端库 | 世界、实体、聊天、背包、动作、示例 | 完成至 M4ab | 20+ 份实施记录；37 个 E2E 用例 | ✅ |
| M5 生产化能力 | 认证、加密、模糊测试、压测、诊断、发布产物、平台矩阵 | **未开始** | `docs/roadmap.md` §8 仍为计划态 | ⛔ 未开始 |

### 3.2 路线图中正确的部分

1. **M0 → M1 → M2 的顺序是对的。** 先锁协议后端与字节码纪律，再建自有 API，最后打通出生链路。这个顺序让每一层都有可验证的输入。
2. **M4 的「广度换风险」策略对库是合理的。** 每一个增量都是只读能力或单向上行，配套 E2E，不引入物理或 AI 这类高风险子系统。
3. **门控是诚实的。** M3 没有因为「Desktop 能跑」而被标记完成；README、架构、计划都明确写了「Android 运行时未验证」。这在工程上比虚假的绿灯有价值得多。
4. **范围边界清醒。** 渲染、物理、寻路、多版本、插件生态全部明确留给下游或后续，不提前增加复杂度。

### 3.3 三个结构性问题

#### 问题一：风险排序与项目自己的原则冲突

`docs/roadmap.md` §1 写：

> 先验证风险最高的链路，再扩大功能面积：当前主要平台风险是 Android 的 Netty 与 iOS 的 RoboVM，而不是协议本身。

`docs/decisions/0002.md` §6 进一步指出，M3 排在 M2 之后是「本评估最重要的可执行建议」，建议把缩减版 Android spike 提前到 M1 并行。

实际发生的是：M1 期间完成了 D8 构建链探针（`docs/android-dex-probe.md`），但运行时验证仍阻塞；随后 M4 从 M4a 一直做到 M4ab，完成了 20 多个功能增量。**最高风险的门控没有关闭，低风险的功能面积持续扩大。**

这不是「做错了功能」，而是**风险暴露的顺序错了**。如果 Android 原生 ART 路径最终不可行，M4 中与内存、线程、区块缓存相关的设计可能需要调整；越晚发现，返工面越大。决策 0002 已经预见了这一点，但执行时没有落实。

#### 问题二：M5 是一个桶，不是一个里程碑

M5 当前包含八件互不依赖的事：

1. 在线模式加密与 Microsoft 认证
2. 令牌安全存储
3. 重连策略
4. 畸形包 / 压缩边界模糊测试
5. 多客户端压力测试与内存测量
6. 诊断信息
7. 发布产物与版本迁移说明
8. 平台支持矩阵

它们的技能、风险、验收方式完全不同。把它们捆在最后一个里程碑，结果是**没有任何中间可交付物**：要么全部完成，要么一直停在「M5 未开始」。建议拆成：

- **M5a 发布工程**：Dokka、POM 元数据、签名、依赖锁定、发布 checklist、迁移说明。
- **M5b 在线能力**：认证、加密、令牌存储。
- **M5c 健壮性与性能**：模糊测试、压测、内存测量、重连策略。
- **M5d 平台矩阵**：Android/iOS 运行时结论与支持声明。

其中 M5a 与 M3 解耦，可以立即开始；M5b 是「能否连正版服务器」的产品门槛；M5c 是「能否长期运行」的门槛。

#### 问题三：缺少「发布就绪」门控

M4 的验收标准是「调用者无需接触数据包即可完成基础机器人」，但没有一条标准回答：

- 产物能否被外部项目稳定消费？
- 公共 API 的兼容性承诺是什么？
- 发布到哪个仓库、由谁签名、如何验证？
- 支持哪些平台，证据是什么？

README 已经写了「0.x 不承诺兼容」「当前只支持 mavenLocal」，这是诚实的，但它替代不了发布门控。建议新增一个 **M4.5 / Release Readiness** 门控，验收标准见 §7。

### 3.4 验收标准的措辞问题

有两处措辞会在未来造成争议：

1. **「完成基础机器人」没有定义。** 当前库能登录、读世界、发聊天、走固定坐标，但**没有碰撞检测、没有物理、没有寻路**。如果「基础机器人」被理解为「能自己走到某处」，那 M4 并未满足；如果被理解为「能按调用方指令逐动作执行」，那它满足了。建议把验收标准改写成可操作的行为列表，例如「能登录、读到出生区块、发送一条聊天、沿 +X 走 10 格并读到服务端确认的位置」。

2. **「慢事件消费者不会破坏 KeepAlive」与实现语义不一致。** 实现选择的是「缓冲写满即终止会话」（`ClientException.EventOverflow`），而不是「丢弃事件但保持连接」。两者都能避免静默丢失，但前者会结束会话。验收标准应明确写成「慢消费者会被显式终止并报告，不允许无界增长或静默丢弃」，与代码一致。

### 3.5 文档漂移

三处进度声明互不一致：

| 文档 | 当前写法 | 实际 |
| --- | --- | --- |
| `docs/roadmap.md` §2 | 「当前进度基线（2026-11-04，M2 完成）」 | 已完成至 M4ab |
| `docs/project-structure.md` 状态行 | 「M4a–M4i 已完成」 | 已完成至 M4ab |
| `docs/README.md` | 已列到 M4z/M4aa/M4ab | 与实际一致 |

项目自己的原则是「实现与规划发生偏离时，应先记录原因并更新文档」。现在 README 是事实上的进度源，roadmap 与 project-structure 的基线段落已经过期。建议：

- 把 roadmap §2 改成「进度索引」，指向 README 与各里程碑记录，不再复制具体日期与状态。
- project-structure 的状态行改成「以 README 为准」，或每次里程碑收尾时同步。
- 在 `docs/README.md` 增加一条「文档状态维护」约定，明确哪份文档是唯一事实源。

---

## 4. 与同类项目对比

### 4.1 对比对象与选择理由

| 项目 | 语言 / 生态 | 定位 | 为什么拿来比 |
| --- | --- | --- | --- |
| MCProtocolLib | Java | 低层协议库 | 本项目的上游；定义「协议层」边界 |
| Minestom | Java | 服务端框架 | 同一协议版本、同一 Netty 生态；事件与线程模型可对照 |
| Minosoft | Kotlin/JVM | 完整客户端（core/headless/ui） | 最接近的 JVM 客户端；分层方式直接可比 |
| Azalea | Rust | ECS 客户端库 | 「客户端库」形态的另一种成熟答案；能力分层清晰 |
| Mineflayer | JavaScript | bot 框架 | 事件/插件/物理的生态参考 |
| Botcraft / Minecraft Console Client | C++ / C# | headless bot | 「无渲染客户端」的产品边界参考 |
| Mindustry / Arc | Java | 游戏 + 网络框架 | `Net` 门面 + `NetProvider` SPI 的跨平台分层参考 |

再次说明：本次无法联网复核这些项目的近期状态，以下只比较**架构形态**，不比较版本号或功能清单。

### 4.2 架构横向对比

| 维度 | PickaxeCraft | Minosoft | Azalea | Mineflayer | Mindustry / Arc |
| --- | --- | --- | --- | --- | --- |
| 协议实现 | 复用 MCProtocolLib | 自研 | 自研 | 自研（prismarine-protocol） | 自研 |
| 分层 | protocol-core / client-core / 宿主 | core / headless / ui | protocol / client / world / physics | 核心 + 插件 | core + 平台后端 + NetProvider |
| 事件模型 | sealed class + 单消费者 Flow | 监听器 | ECS 事件 | EventEmitter | 监听器 |
| 世界模型 | 只读快照 + 区块缓存 | 完整世界 | ECS 世界 | 完整世界 | 游戏世界 |
| 物理 / 寻路 | **不做**，留给下游 | 部分 | 内置 | 内置 / 插件 | 游戏内 |
| 多版本 | 锁定 26.2 | 多版本适配 | 多版本 | 多版本 | 自有协议 |
| 传输可替换 | 无（Netty 固定） | 无 | 无 | 无 | **有（NetProvider）** |
| 发布形态 | 库（Maven） | 应用 + 库 | crate | npm 包 | 应用 + 框架 |

### 4.3 四个维度的分析

#### 分层与模块边界：与主流一致，且执行得更硬

Minosoft 的 `core / headless / ui`、Azalea 的 `protocol / client / world / physics`、Mindustry 的 `core + 平台后端`，都遵循同一条原则：**协议实现、客户端状态、宿主/渲染三者分离**。PickaxeCraft 的 `protocol-core / client-core / 宿主` 是同一形态，而且用 `verifyModuleBoundaries` 把「上层不得看见 Netty/协议库/Minestom」做成了构建失败，而不只是约定。这一点比多数同类项目严格。

#### 传输与平台：刻意不做 SPI，代价与收益都清楚

Mindustry 的 `Net` 门面 + `NetProvider` SPI 是为「同一份核心代码跑 Desktop 与 Android」服务的。PickaxeCraft 目前没有 SPI，因为 MCProtocolLib 的 `Session.getChannel()` 直接返回 Netty `Channel`，没有可替换传输的接缝。决策 0002 已经把这个事实写清楚，并给出条件：**如果要走原生 ART 路径，必须引入 SPI（参照 Arc 的 `NetProvider`）**。

在当前「Desktop 先行 + Netty 固定」的前提下，不做 SPI 是正确的 KISS 选择；但它把 Android 原生路径的成本推高了一个数量级。这不是实现缺陷，是**产品决策尚未做出**：ART + D8 还是自带 OpenJDK。决策 0002 的决策 4 已经把这个选择记录在案，但至今没有结论。建议在 M3 解除阻塞时，把它作为第一个要拍板的问题，而不是先写代码。

#### 能力深度：明确是「库」而不是「bot 框架」

Azalea 与 Mineflayer 提供物理、碰撞、寻路，因此下游可以直接写「走到坐标」；PickaxeCraft 只提供 `moveTo` / `look` 这类**声明**，不预测、不碰撞、不寻路。这是清醒的范围选择：物理与寻路是独立的大工程，且与渲染/宿主强相关，放进核心库会让依赖面和测试面爆炸。

代价是**对外定位必须写清楚**。如果 README 只说「Minecraft 客户端核心库」，下游很容易误以为它能像 Mineflayer 一样开箱做 bot。建议在 README 开头加一句边界声明：

> 本库提供协议会话、世界状态与动作原语，不包含物理、碰撞、寻路或任务调度；这些属于下游客户端或 bot 框架。

#### 版本策略与发布：最弱的一环

Minosoft、Azalea、Mineflayer 都有多版本适配层；PickaxeCraft 锁定 26.2，并在 roadmap §9 明确把多版本列为后续。这个选择与「跟随 Minestom 联调」的当前目标一致，合理。

但与它们相比，PickaxeCraft 的**发布工程**明显更薄：没有 Dokka、没有 POM 的 `url/scm/developers`、没有签名、没有依赖锁定、没有发布仓库目标。同类项目在开始对外发布时，这些是基本配置。这不是架构差距，是「还没走到那一步」。

### 4.4 对比结论

1. **分层形态属于同类项目的主流做法**，没有需要推倒的结构性问题。
2. **工程门控（字节码、模块边界）比多数同类项目更严格**，这是优势。
3. **能力深度是刻意的取舍**，不是缺陷；但必须在对外文档中定义清楚，否则会被误判为「功能不全的 bot 框架」。
4. **传输 SPI 的缺失是唯一的结构性分叉点**，其必要性完全取决于 Android 路径的产品决策。
5. **发布工程是相对最弱的一环**，但也是最容易补的。

---

## 5. 可交付性缺口清单

严重度定义：

- **P0**：阻断公开发布或会带来已知安全风险，必须在下一次对外产物前解决。
- **P1**：阻断「生产可用」，或会在 3–6 个月内变成维护债务。
- **P2**：影响可维护性、可诊断性或团队效率，不阻断当前使用。
- **P3**：改进项。

### 5.1 清单总表

| ID | 严重度 | 问题 | 证据 | 影响 |
| --- | --- | --- | --- | --- |
| P0-1 | P0 | Netty 锁定在 4.2.1.Final，处于已知 CVE 修复前的窗口 | `gradle/libs.versions.toml`；`docs/decisions/0002.md` §2 | 公开发布会把已知漏洞带给下游 |
| P0-2 | P0 | Android/iOS 运行时未验证，无法生成平台支持矩阵 | `docs/plans/m3-android-runtime.md` | 不能声称移动平台支持；发布说明必须降级 |
| P1-1 | P1 | 仅支持离线模式，无 Microsoft 认证/加密 | `docs/roadmap.md` §8；代码中无 auth 路径 | 无法连接正版服务器，生产场景受限 |
| P1-2 | P1 | 发布产物不完整：无 javadoc、POM 元数据缺失、无签名 | mavenLocal POM 实测；`buildSrc/.../pickaxe.publish.gradle.kts` | 不满足 Maven Central 等仓库的基本要求 |
| P1-3 | P1 | POM `description` 回退为模块名 | 实测 `client-core-0.1.0-SNAPSHOT.pom` 为 `<description>client-core</description>` | 发布元数据不准确，暴露构建脚本时序 bug |
| P1-4 | P1 | `PluginMessage.data` 暴露内部数组 | `protocol-core/.../PluginMessage.kt:24` | 调用方可改写缓存数据，违反文档承诺 |
| P1-5 | P1 | `LightPatch.sky/block` 暴露可变 `ByteArray` | `protocol-core/.../world/ChunkLight.kt:16-17` | 与内部 `ChunkLight` 共享数组，误改会污染光照缓存 |
| P1-6 | P1 | 版本资产只有 `ItemPrototypes` 有可执行导出任务 | `testing/minestom/build.gradle.kts`；四个数据文件头注释 | 协议升级时无法复现其余四张表 |
| P1-7 | P1 | E2E 使用轮询 `delay` 等待 | `ClientSessionTest.kt:2261-2267` | 与项目「确定性协调」原则冲突，CI flake 风险 |
| P1-8 | P1 | `client-core` 与 `app` 无任何测试 | `:client-core:test NO-SOURCE`、`:app:test NO-SOURCE` | 会话逻辑只能靠 E2E 间接覆盖，定位成本高 |
| P1-9 | P1 | `Session` 是 1,485 行 / 83 个 public 成员的 God Object | `Session.kt` 统计 | API 冻结后演进成本高 |
| P2-1 | P2 | 两套 public 连接 API：`ProtocolConnection` 与 `Session` | `ProtocolConnection.kt:29` | 下游可能绕过会话背压与生命周期 |
| P2-2 | P2 | 每个会话自持 `EventLoopGroup` | `docs/architecture.md` §5.1 | 多会话场景线程数线性增长 |
| P2-3 | P2 | 文档进度基线漂移 | roadmap §2、project-structure 状态行 | 新人无法从文档判断真实进度 |
| P2-4 | P2 | CI 仅 Ubuntu；无定时上游探测 | `.github/workflows/ci.yml` | 平台回归与上游 breaking change 无预警 |
| P2-5 | P2 | 无依赖锁定 / 校验元数据 | 仓库内无 `gradle.lockfile`、`verification-metadata.xml` | 传递依赖可能静默漂移 |
| P3-1 | P3 | `ClientException` 缺少 retryable 语义 | `ClientException.kt` | 调用方需自己判断可否重试 |
| P3-2 | P3 | 验收标准「基础机器人」「慢消费者不破坏 KeepAlive」措辞含糊 | `docs/roadmap.md` M4 验收 | 未来争议 |

### 5.2 P0 详述

#### P0-1：Netty 4.2.1 的安全窗口

事实链：

- `gradle/libs.versions.toml` 将 `netty = "4.2.1.Final"` 固定，并显式排除了 `netty-all`，只引入 buffer/common/transport/handler 四个模块。
- `docs/decisions/0002.md` §2 明确记录：**Netty 4.2.16.Final 一次性包含 11 个 CVE 修复**（内存耗尽、zip bomb、CR/LF 注入等），而 4.2.1 处于修复前窗口。
- 同一文档还记录：4.2.4+ 在 Android 8–12 的 ART 路径上有 `UnpooledDirectByteBuf` 的 `VerifyError` 实测报告，仅在 Android 13+ 正常。

这意味着升级不是一个纯粹的「改版本号」动作：

- 对 Desktop：升级到 4.2.16+ 是明确的安全收益，风险主要是与 MCProtocolLib 26.2 的兼容性，需要实测。
- 对 Android ART：升级后必须重新验证 VerifyError 是否仍然存在；如果存在，说明「ART + D8」路径对该版本不可行，会直接影响 M3 的产品决策。

建议动作：

1. 在独立分支升级 Netty 到 4.2.16+，重跑 `build` 与完整 E2E。
2. 记录 MCProtocolLib 26.2 在新 Netty 上的兼容性结论。
3. 如果 M3 仍未解除，至少在决策文档中写明「Desktop 用新版、Android 待验证」的临时策略，而不是让整个项目停在旧版。
4. 加一条 CI 检查或注释，避免下一次依赖更新时又把版本带回旧版。

在没有完成上述验证前，这个库不应发布到公共仓库。

#### P0-2：移动平台声明缺少运行时证据

当前事实是诚实的：README、架构、M3 计划都写了「未验证」。问题不在于「说了假话」，而在于**发布级产物需要一个明确的平台矩阵，而矩阵目前无法生成**。

两个可选路径：

- **路径 A：先发 Desktop-only 0.1.0。** 在 README、发布说明、POM 描述中明确「仅验证 Desktop/JVM，Android/iOS 为候选平台」。这是最小成本、最快交付的选择。
- **路径 B：先解 M3 再发。** 需要设备或模拟器，以及「ART + D8 vs 自带 OpenJDK」的产品决策。成本高，但能兑现「跨平台库」的定位。

无论选哪条，**都不应该在发布说明中留下模糊表述**。当前文档已经做得不错，需要在发布 checklist 中固化。

### 5.3 P1 详述

#### P1-2 / P1-3：发布产物与 POM

实测 `client-core-0.1.0-SNAPSHOT.pom`：

- 有 `groupId` / `artifactId` / `version` / `name` / `licenses`。
- **没有** `url`、`scm`、`developers`。
- `description` 是 `client-core`，而不是构建脚本里写的中文描述。

最后一点是一个可复现的构建时序 bug：`pickaxe.publish.gradle.kts` 在应用插件时执行 `pom { description.set(project.description ?: project.name) }`，而模块的 `description = "..."` 赋值发生在 `plugins {}` 之后的脚本主体里，此时 `project.description` 仍为 `null`，于是回退到模块名。修复方式是用 `provider { project.description ?: project.name }` 延迟求值，或在 `afterEvaluate` 中配置。

此外：

- 没有 Dokka / javadoc jar，IDE 外看不到 KDoc。
- 没有签名配置，无法发布到要求 GPG 的仓库。
- jar 内没有 `LICENSE`，只有 `META-INF/MANIFEST.MF` 与 class 文件。
- 没有发布仓库目标（README 已说明「首个外部消费者出现后再定」）。

这些都不影响内部消费，但都是公开发布的前置条件。

#### P1-4 / P1-5：可变数组暴露

`PluginMessage` 的文档写「[data] 在构造时做防御性拷贝……内容不可通过本类修改」，实现也确实在构造时 `copyOf()`。但字段本身是 `public val data: ByteArray`，getter 返回的是同一个数组引用。调用方执行 `message.data[0] = 0` 就能改写这个对象持有的字节。构造时的拷贝只防住了「外部数组后续被改」，没有防住「通过 getter 改内部数组」。

`LightPatch.sky` / `block` 更直接：它们是 `Map<Int, ByteArray>`，文档说明「数组直接引用服务端下发的那一段，不复制」，目的是省内存。但同一段数组也被 `ChunkLight` 持有并服务后续查询。调用方拿到 `LightPatch` 后改写数组，会污染整个区块的光照缓存。

建议：

- `PluginMessage`：把 `data` 改成方法 `data(): ByteArray = data.copyOf()`，或提供一个只读的 `ByteReadOnlyBuffer` 视图；同时更新 KDoc 与调用点。
- `LightPatch`：保留不复制（内存权衡合理），但在 KDoc 中明确「数组是共享只读视图，调用方不得修改」，并在类型上尽量表达（Kotlin 没有真正的只读数组，至少用命名与文档约束）。如果未来有下游误用迹象，再考虑复制或包装。

#### P1-6：版本资产的可复现性

四张表在文件头都声称「由 Minestom 注册表自动导出」：

- `ItemKeys.kt`：1537 项
- `ItemPrototypes.kt`：1537 项
- `LivingEntityTypes.kt`：158 项中 93 项为生物
- `AttributeTable.kt`：注释指向 `docs/plans/m4l-attributes.md` 的 tools 脚本

但仓库里只有 `:testing:minestom:exportItemPrototypes` 一个可执行任务（`ExportAssets.kt` 只实现 `item-prototypes` 子命令）。其余三张表的导出脚本不在仓库内，或者只存在于历史文档描述中。结果是：**协议升级时，只有一张表能「二次运行零 diff」地复现，其余三张只能手工维护**。

这与 roadmap「版本资产必须可重跑」的原则不一致，也是一个只在升级时才会咬人的隐性债务。建议把四个导出器统一成一个 `exportRegistries` 任务（或四个子命令），输出到固定路径，并在 CI 中做「重跑后 git diff 为空」的检查（可以只在手动触发时跑，避免每次构建都启动 Minestom）。

#### P1-7 / P1-8：测试的确定性与分层

`ClientSessionTest` 2,492 行、37 个用例，覆盖了出生、区块、实体、重生、玩家列表、聊天、命令、背包、拖拽、挖掘、放置、骑乘、转移、插件消息、背压等几乎所有能力。作为 E2E，它写得相当扎实：用真实 Minestom 服务端，断言服务端状态与客户端状态一致，而不是 mock。

但有两个问题：

1. **轮询等待。** `awaitCondition` 用 `withTimeout { while (!condition()) delay(POLL_INTERVAL_MILLIS) }`。项目自己的工程实践要求「并发测试使用确定性协调，不依赖 sleep」。轮询在多数时候没问题，但在 CI 负载高时会变成 flake 源；而且失败时只能看到「超时」，看不到「事件到底走到哪一步」。
2. **分层缺失。** `client-core` 没有单元测试。`Session` 的状态迁移、缓存更新、关闭语义只能通过 E2E 间接验证。E2E 慢（本次全量约 3 分钟），反馈周期长，定位成本高。

建议：

- 短期：把轮询集中到一个带说明的 helper，并在失败信息中附带已收集的事件列表，降低排查成本。
- 中期：为 `Session` 的关键不变式补少量单元测试（例如「`finish()` 后所有缓存为空且状态为 CLOSED」「溢出时事件流以 `EventOverflow` 结束」），不需要覆盖每个 getter。
- 按能力拆分 `ClientSessionTest`（world / entity / inventory / chat / session-lifecycle），单文件控制在 500 行以内。

#### P1-9：`Session` 的规模

83 个 public 成员意味着调用方面对的是一个「什么都能做」的对象。对 0.x 库来说这不是错误，但会在两个时间点变成成本：

- 首个外部消费者出现、需要冻结 API 基线时；
- 需要为某个能力做独立演进（例如把背包逻辑抽到独立模块）时。

建议的方向不是「现在就拆模块」，而是**按能力提供只读视图**：`Session.world`、`Session.inventory`、`Session.chat`、`Session.entities` 各返回一个小接口，内部仍共享同一个 Actor 与缓存。这样既不改并发模型，又能让调用方按能力依赖，未来拆分时有接缝。

### 5.4 P2 详述

- **P2-1 双连接 API。** `ProtocolConnection` 是 public，`MinecraftClient` / `Session` 也是 public。前者没有会话级背压、事件缓冲与生命周期管理。`docs/project-structure.md` 把 `ProtocolConnection` 描述为「对上层暴露的唯一入口」，这是 protocol-core 层的入口；但 protocol-core 也会发布，下游可以直接用它。建议在 KDoc 中明确「直接使用 `ProtocolConnection` 需自行承担背压与关闭语义；常规使用请走 `client-core`」，或加 `@RequiresOptIn`。
- **P2-2 每会话一个 `EventLoopGroup`。** 这保证了会话隔离与「关闭即回收」，但 100 个会话就会有数百个线程。决策 0002 的决策 2 已经建议「调用方注入 EventLoopGroup」，但未落实。建议提供可选的共享组（默认仍自持），并在 M5 压测中测量两种模式的线程数与吞吐。
- **P2-3 文档漂移。** 见 §3.5。
- **P2-4 CI 覆盖面。** 仅 Ubuntu。建议加 Windows job（至少 `build`，`ShutdownHookTest` 会自动跳过）与 macOS job（为 iOS/RoboVM 做准备）；再加每周定时任务，用 MCProtocolLib 最新版跑一个最小探针，提前感知上游 breaking change（决策 0002 决策 5 已建议）。
- **P2-5 依赖锁定。** 没有 `gradle.lockfile` 或 `verification-metadata.xml`。作为库，传递依赖的漂移会直接影响下游。建议至少对 `protocol-core` / `client-core` 的 `runtimeClasspath` 启用依赖锁定，并生成校验元数据。

---

## 6. 优先行动建议

### 6.1 发布前必须完成（P0）

| 顺序 | 动作 | 完成判据 |
| --- | --- | --- |
| 1 | 升级 Netty 到 4.2.16+，重跑构建与 E2E | `build` 通过；162 项测试仍通过；记录 MCPL 兼容性结论 |
| 2 | 决定 0.1.0 的平台承诺 | README / POM / 发布说明三处口径一致：Desktop-only 或补 M3 证据 |
| 3 | 写一份「发布说明」草案 | 明确离线模式、0.x 兼容政策、平台矩阵、已知限制 |

### 6.2 0.1.0 前建议完成（P1）

1. 补发布工程：Dokka、POM `url/scm/developers`、修复 `description` 时序 bug、签名配置、jar 内 `LICENSE`。
2. 修 `PluginMessage.data` 与 `LightPatch` 的可变数组暴露。
3. 统一资产导出任务，至少让四张表都能「重跑零 diff」。
4. 测试确定性改造：轮询 helper 带事件快照；拆分 `ClientSessionTest`；为 `Session` 补 3–5 个关键不变式单测。
5. `Session` 能力视图化（可放 0.1.x，不阻断 0.1.0）。
6. 明确 `ProtocolConnection` 的使用边界。

### 6.3 0.2 及以后（P2）

1. 文档基线同步（roadmap / project-structure）。
2. CI 加 Windows/macOS job 与每周上游探测。
3. 依赖锁定与校验元数据。
4. 可注入的共享 `EventLoopGroup` + 多会话压测。
5. `ClientException` 增加 `retryable` 或建议动作。

### 6.4 明确不做（避免范围蔓延）

- 不在本仓库实现渲染、物理、碰撞、寻路、任务调度。
- 不在 M3 结论出来前引入 AGP、KMP 或 Android 专属模块。
- 不为「可能的多版本」提前建适配层。
- 不把 Minestom 变成运行时依赖。

---

## 7. 建议的「发布就绪」验收清单

把下面这份清单作为 M4.5 / Release Readiness 的门控，每条都要有可复查的证据：

**产物**

- [ ] `publishToMavenLocal` 与一次真实的外部最小工程消费验证（Gradle + 纯 Maven 各一次）。
- [ ] POM 含 `url` / `scm` / `developers` / 正确的 `description`。
- [ ] 有 Dokka 或 javadoc jar；jar 内含 `LICENSE`。
- [ ] 若发布到公共仓库：签名可验证，`groupId` 与域名匹配或已获授权。

**安全**

- [ ] Netty 版本不在已知 CVE 窗口；升级结论记录在决策文档。
- [ ] 令牌/密钥不进入日志（M5b 完成后）。
- [ ] 依赖有锁定或校验元数据。

**平台**

- [ ] README / 发布说明 / 平台矩阵三处一致。
- [ ] 每个声称支持的平台都有对应测试或运行时证据。
- [ ] 未验证平台明确标为「候选」，不出现「理论支持」。

**质量**

- [ ] `build` 在 CI 通过；E2E 连续 10 次无 flake（或在受控环境中证明稳定）。
- [ ] 关键错误路径有测试：连接失败、服务端断开、超时、事件溢出、重复关闭。
- [ ] 关闭后无残留线程与端口（已有测试，需在 CI 上确认）。

**文档**

- [ ] 0.x 兼容政策写明；升级/迁移说明存在。
- [ ] `protocol-contract.md` 的升级检查清单可执行。
- [ ] 进度基线只有一个事实源。

---

## 附录 A：本次验证命令与结果

```text
> .\gradlew.bat build --console=plain
BUILD SUCCESSFUL in 24s
47 actionable tasks: 19 executed, 28 up-to-date

> .\gradlew.bat :protocol-core:test :samples:libgdx-viewer:test :testing:minestom:test --rerun-tasks --console=plain
BUILD SUCCESSFUL in 2m 52s
23 actionable tasks: 23 executed

测试汇总（解析 23 个 TEST-*.xml）：
total=162 failures=0 errors=0 skipped=1

> .\gradlew.bat :protocol-core:publishToMavenLocal :client-core:publishToMavenLocal
BUILD SUCCESSFUL

> .\gradlew.bat :app:run --args="--version"
minecraft 26.2
protocol 776
minestom 2026.10.07-26.2
```

mavenLocal 产物：

```text
client-core-0.1.0-SNAPSHOT.jar
client-core-0.1.0-SNAPSHOT-sources.jar
client-core-0.1.0-SNAPSHOT.module
client-core-0.1.0-SNAPSHOT.pom
（protocol-core 同构；两者均无 javadoc jar）
```

---

## 附录 B：工作区未提交改动的观察

审查时工作区有两处未提交改动，**它们不属于本次审查基线**，但其中一处包含一个值得在下一次提交前确认的问题：

1. `client-core/src/main/kotlin/org/pickaxe/client/Session.kt`（已修改）：新增 `chunkRevision` / `chunkCoordinates()` / `chunkRevisionCounter`，用于让渲染方感知区块集合变化。
2. `protocol-core/src/main/kotlin/org/pickaxe/protocol/world/ChunkPos.kt`（未跟踪）：新的区块坐标值类型。

观察到的问题：`close()` 与 `abortBecauseOfOverflow()` 会递增 `chunkRevisionCounter`，但 `finish()` **没有**递增。如果渲染方依赖 revision 变化来清理视图，正常服务端断开时不会收到通知。建议在合并前确认三条终止路径的语义一致，并补一条对应的 E2E 断言。

以上仅作为交接提示，本次审查没有修改这些文件。

---

## 附录 C：审查结论的适用范围

本审查判断的是 `0.1.0-SNAPSHOT` 这一时点的状态。以下变化会改变结论：

- Netty 升级并验证 → P0-1 关闭，可进入公开发布准备。
- M3 解除或明确 Desktop-only → P0-2 关闭。
- 发布工程补齐 → P1-2/P1-3 关闭。
- 加入在线认证/加密 → 「仅离线」的限制解除。

建议每次发布前重新跑一遍 §7 的清单，而不是复用本文的结论。
