# PickaxeCraft

[![CI](https://github.com/NetheritePickaxe/pickaxecraft/actions/workflows/ci.yml/badge.svg)](https://github.com/NetheritePickaxe/pickaxecraft/actions/workflows/ci.yml)

PickaxeCraft 是一个可连接 Minestom 的 Minecraft Java 版客户端核心库。Headless 是第一阶段交付形态；长期下游是在独立仓库开发的轻量化图形客户端（桌面基于 LWJGL3，移动端以 libGDX 为平台壳）。协议后端采用 MCProtocolLib，本项目提供会话、事件与只读世界模型，不包含渲染、GUI、libGDX 或 Minestom 运行时依赖。

Minestom 在本仓库只用于 `testing:minestom` 联调。最终客户端若需要单人游戏，可在外部仓库的 Desktop/Java 25 宿主中可选集成 Minestom，并通过同一客户端协议连接本地服务端；该能力不进入共享核心，也不作为 Android/iOS 的默认承诺。

下游渲染完全自研、不使用 libGDX graphics API：桌面（LWJGL3）走与 MC 原版同构的双后端——OpenGL 默认 + 实验性 Vulkan；libGDX 仅在 Android/iOS 作为平台壳；所有图形依赖仍只存在于外部客户端仓库。

> 当前进度：M2 出生链路已完成，M4a 区块内容已可查询（`Session.blockStateAt` / `biomeAt` / `chunk`，与服务端 `stateId` 对账），M4b 实体已可跟踪（`Session.entity` / `entities`，坐标与服务端对账），M4c 重生换维度可用，M4d 玩家列表与实体名字可解析（`Session.players` / `nameOf`），M4e 聊天可读（`Session.chat`，含格式与删除），M4f 聊天可发（`Session.sendChat` / `sendCommand`），M4g 移动可发（`Session.moveTo` / `look`，只发变化的分量），M4h 背包可读（`Session.inventory` / `container` / `heldItem`，`selectHotbarSlot` 切换选中格），M4i 自身状态与装备可读（`Session.status`、`TrackedEntity.equipment`），M4j 容器可操作（`Session.clickSlot` / `closeContainer` / `cursorItem`，不做本地预测），M4k 方块可交互（`Session.digBlock` / `useOnBlock`，服务端方块变更写回缓存，交互序号作回执），M4l 属性可读（`Session.attributes(entityId)`），M4m 物品可读名（`InventoryItem.key` / `customName`），M4n 实体可交互（`Session.attack` / `interactWith` / `interactAt` / `swing`，伤害包转 `EntityDamaged` 事件），M4o 运动状态可声明（`Session.setSneaking` / `setSprinting` / `setFlying`，`Session.abilities` 为服务端权威能力），M4p 状态效果可读（`Session.effects`，`EffectKeys` 是版本资产），M4q 容器属性可读（`Session.containerProperties`），M4r 生物血量可读（`Session.health(entityId)` / `TrackedEntity.health`，按实体层级读元数据、非生物不误读），M4s 物品组件可读（`InventoryItem.damage` / `enchantments` / `lore` 等，附魔键名来自下发的动态注册表），M4t 区块环境可读（`ChunkColumn.skyLightAt` / `blockLightAt` / `heightAt`、`Session.blockEntity`，光照增量走 `LightPatch`），M4u 命令树与补全可用（`Session.commands` / `complete`，候选由服务端算），M4v 跨服转移可跟随（`Session.transferRequest` / `MinecraftClient.followTransfer`，转移包只上报地址、不自动建连接），M4w 提供 libGDX 消费示例（`samples:libgdx-viewer`，headless、不发布，把区块网格化成顶点数组），M4x 骑乘可读可动（`Session.vehicle` / `passengers` / `steerVehicle`，上/下车由乘客列表推出，骑乘时封住玩家位置包）；物品默认组件原型尚未开始，Android 运行时验证（M3）仍受环境阻塞（门控见 `docs/plans/m3-android-runtime.md`）。

## 规划文档

- [文档入口](docs/README.md)
- [架构与技术决策](docs/architecture.md)
- [项目结构](docs/project-structure.md)
- [实施路线](docs/roadmap.md)
- [协议后端决策](docs/decisions/0001-protocol-backend.md)
- [M0 实施计划](docs/plans/m0-implementation-plan.md)

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK（构建、CLI 与联调服务运行） | 25 |
| JDK（共享产物字节码与消费下限） | 21 |
| Gradle | 由 Wrapper 提供（9.6.0） |

`PATH` 上的 `java` 可以不是 25：Daemon JVM 由 `gradle/gradle-daemon-jvm.properties` 固定为 25，构建时会自动获取。

`protocol-core` 与 `client-core` 发布的是 **Java 21 字节码**，因此下游宿主按 Java 21 编译即可；
CLI 分发包（`:app` 的 `distZip` / `distTar`）和联调服务是 Java 25 字节码，运行它们需要 JDK 25。
设置 `JAVA_HOME` 指向 JDK 25 后再执行 `bin/pickaxecraft` 或 `bin/minestom`，否则会得到
`UnsupportedClassVersionError`。

## 构建

```bash
./gradlew build
```

`build` 里包含测试与两类质量门控：字节码版本（`protocol-core` 必须为 65，桌面模块为 69）与模块依赖边界（业务模块的编译类路径不得出现 Netty、MCProtocolLib、Minestom）。

依赖解析需要访问两个仓库：Maven Central，以及 MCProtocolLib 所在的 `https://repo.opencollab.dev/main/`（仓库配置里按 group 限定，不影响其他依赖）。

依赖校验：`gradle/verification-metadata.xml` 固定了全部外部依赖的 SHA-256，解析到未记录的构件会直接失败。
新增或升级依赖后执行一次 `./gradlew --write-verification-metadata sha256 build`，再跑 `build` 确认严格校验通过；
CI 报校验失败时先确认变更来源，不要直接放宽校验。

CI 在 Ubuntu 上以同样的命令执行（`.github/workflows/ci.yml`）。

## 使用

```bash
./gradlew :app:run --args="--version"    # 客户端版本、Minecraft 26.2、协议 776、Minestom 版本
./gradlew :app:run --args="--help"
./gradlew :app:run --args="connect 127.0.0.1 25565 --say 你好"   # 出生后发一条聊天，服务端会广播回来
./gradlew :app:run --args="connect 127.0.0.1 25565 --walk 3"     # 出生后沿 +X 走 3 格（每步 0.5）
./gradlew :app:run --args="connect 127.0.0.1 25565 --hold 2"     # 切到第 3 格快捷栏并打印该格物品
./gradlew :app:run --args="connect 127.0.0.1 25565 --click 9 --click 36:swap3 --close-window"
./gradlew :app:run --args="connect 127.0.0.1 25565 --dig"       # 挖掉脚下那格石头，等服务端方块变更回包
./gradlew :app:run --args="connect 127.0.0.1 25565 --place"     # 把手中物品放到脚旁东侧一格的上面
./gradlew :app:run --args="status 127.0.0.1 25565 --json"       # 单行 JSON 结果对象
./gradlew :app:run --args="connect 127.0.0.1 25565 --json"      # 每行一个事件的 JSONL
```

### `--json`（0.x 实验契约）

默认输出仍是人类可读的 `key=value` 行；`--json` 只改变呈现，不改变行为。每行一个 JSON 对象，
公共字段为 `type` / `event` / `session` / `data` / `ok` / `error` / `exitCode`：

- `status` 输出一个 `type=status` 对象，`data` 是版本、人数、延迟与 MOTD；
- `connect` 流式输出 `type=event` 行，结束时输出一个 `type=result` 对象；
- 事件里没有值的字段输出为 `null`（键保持稳定），错误对象含 `kind` 与 `message`；
- 动作旁白（“已发送”“已执行”）只出现在人类可读模式，不会混进 JSONL；
- 终止事件为失败时 `ok=false` 且进程退出码非零，脚本可以直接信任退出码。

`--json` 是 0.x 实验契约：字段可能随小版本调整，调用方应按 `type` 分支处理并对未知字段保持宽容。
本库不提供 MCC 式的交互控制台。

### 分发包

```bash
./gradlew :app:distZip :app:distTar      # 产物名 pickaxecraft-<version>.zip/tar
```

解压后 `bin/pickaxecraft` 与 `bin/pickaxecraft.bat` 即可运行，需要 JDK 25（见“环境要求”）。

联调用的 Minestom 服务端：

```bash
./gradlew :testing:minestom:run          # 监听 127.0.0.1:25565，离线认证
```

启动后输出 `event=server.started … protocol=776`，玩家连接时依次输出 `event=player.configuring` / `event=player.spawned` / `event=player.loaded`；Ctrl+C 停止时输出 `event=server.stopping` / `event=server.stopped`。该服务只绑定回环地址，不会暴露到局域网。服务端支持 `--port` 指定端口。

关闭链路由 `ShutdownHookTest` 在独立 JVM 中真实发送 SIGTERM 验证；Windows 没有 SIGTERM，该用例会自动跳过，由 Linux CI 覆盖。

## 版本锁定

- 依赖版本唯一来源：`gradle/libs.versions.toml`
- 协议版本唯一来源：`protocol-core` 的 `ProtocolVersion` 常量，由 `ProtocolVersionTest` 与 MCProtocolLib 内的 codec 常量对账

## 发布与兼容

- 坐标：`io.github.netheritepickaxe:protocol-core` 与 `io.github.netheritepickaxe:client-core`，当前版本 `0.1.0-SNAPSHOT`。
- 本地产物：`./gradlew publishToMavenLocal` 写入 `~/.m2`；
  `./gradlew publishAllPublicationsToStagingRepository` 写入根目录 `build/staging-repo`
  （两个模块共用一个仓库，外部消费者只读这里）。发布时用 `-Pversion=0.1.0` 产出非 SNAPSHOT 版本。
- 发布物包含：主 jar、源码 jar、Dokka 生成的 javadoc jar；POM 带 `name` / `description` / `url` /
  `scm` / `developers` / `licenses`；jar 内 `META-INF/LICENSE` 与 `META-INF/THIRD-PARTY-NOTICES.md`，
  manifest 带 `Implementation-Title` / `Implementation-Version`。
- 签名：默认关闭（`signing.isRequired = false`）。提供内存 PGP 密钥后自动为全部产物生成 `.asc`：
  环境变量 `SIGNING_KEY` / `SIGNING_PASSWORD`，或 Gradle 属性 `-PsigningKey` / `-PsigningPassword`。
  密钥、口令与 `.asc` 都不入库；本地已用一次性 key 完成 dry-run 验证。
- 消费验证：`tools/release-smoke` 的 Gradle 与纯 Maven 两个最小工程只读 staging repo，
  各自完成一次真实的 `status` 查询（见该目录 README）。Gradle 消费方以 Java 21 toolchain 编译并运行，
  因此“共享产物可在 Java 21 上运行”有实测依据。
- **0.x 阶段不承诺源码或二进制兼容**：公共 API 可能随版本变更且不提前通告，请锁定精确版本。首个下游消费者出现后冻结 API 基线并接入二进制兼容校验，1.0 起遵循语义化版本。
- 两种消费方式都能拿到完整传递依赖：Gradle 走模块元数据（`.module`），Maven 走 POM 的
  `runtime` 作用域（`maven-publish` 会把 `implementation` 映射为 `runtime`）。
  纯 Maven 消费方必须自行声明 `https://repo.opencollab.dev/main/`，因为 MCProtocolLib 不在 Maven Central。
- 本项目以 [MIT](LICENSE) 开源，发布 POM 中的许可证字段同步声明；第三方依赖的许可证清单见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)，升级或新增依赖时必须同步更新。

## 平台支持

| 平台 | 状态 | 证据与边界 |
| --- | --- | --- |
| 标准 JVM（Windows / Linux / macOS） | 已验证 | 共享产物为 Java 21 字节码；发布冒烟用 Java 21 toolchain 编译并运行 `status` 查询。构建、CLI 与联调服务使用 JDK 25。 |
| Android | 候选，未验证 | 构建链已探针通过（D8 可消费、`minSdk` 26）；ART + Netty NIO + 线程/资源回收仍待 M3。不阻断 0.1.0。 |
| iOS | 候选，未验证 | 需要 macOS/Xcode + RoboVM 环境验证 Java 21、Kotlin、协程与 Netty；未验证前不作支持声明。 |
| Web / HTML5 | 不做 | Kotlin/JVM + Netty 与浏览器运行时不匹配。 |

本库不包含物理、碰撞、寻路或任务调度；这些属于下游图形客户端。

核心技术基线：Kotlin/JVM、MCProtocolLib 26.2（协议 776）、构建与 CLI 使用 Java 25、共享发布产物使用 Java 21 字节码、Gradle Daemon JDK 25。目标是可嵌入标准 JVM 宿主的客户端核心；Android 与 iOS 为候选平台，移动运行时验证不进入 0.1.0 门槛。
