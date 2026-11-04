# PickaxeCraft

[![CI](https://github.com/NetheritePickaxe/pickaxecraft/actions/workflows/ci.yml/badge.svg)](https://github.com/NetheritePickaxe/pickaxecraft/actions/workflows/ci.yml)

PickaxeCraft 是一个可连接 Minestom 的 Minecraft Java 版客户端核心库。Headless 是第一阶段交付形态；长期下游是在独立仓库中使用 libGDX 开发的轻量化图形客户端。协议后端采用 MCProtocolLib，本项目提供会话、事件与只读世界模型，不包含渲染、GUI、libGDX 或 Minestom 运行时依赖。

Minestom 在本仓库只用于 `testing:minestom` 联调。最终客户端若需要单人游戏，可在外部仓库的 Desktop/Java 25 宿主中可选集成 Minestom，并通过同一客户端协议连接本地服务端；该能力不进入共享核心，也不作为 Android/iOS 的默认承诺。

下游渲染以 libGDX GL20/GLES 2.0 + ANGLE 为基线，验证 ANGLE Vulkan 并保留回退路径，跟踪 MC 原版的 Vulkan 演进；所有图形依赖仍只存在于外部客户端仓库。

> 当前进度：M2 出生链路已完成，M4a 区块内容已可查询（`Session.blockStateAt` / `biomeAt` / `chunk`，与服务端 `stateId` 对账），M4b 实体已可跟踪（`Session.entity` / `entities`，坐标与服务端对账），M4c 重生换维度可用，M4d 玩家列表与实体名字可解析（`Session.players` / `nameOf`），M4e 聊天可读（`Session.chat`，含格式与删除），M4f 聊天可发（`Session.sendChat` / `sendCommand`），M4g 移动可发（`Session.moveTo` / `look`，只发变化的分量），M4h 背包可读（`Session.inventory` / `container` / `heldItem`，`selectHotbarSlot` 切换选中格），M4i 自身状态与装备可读（`Session.status`、`TrackedEntity.equipment`），M4j 容器可操作（`Session.clickSlot` / `closeContainer` / `cursorItem`，不做本地预测），M4k 方块可交互（`Session.digBlock` / `useOnBlock`，服务端方块变更写回缓存，交互序号作回执），M4l 属性可读（`Session.attributes(entityId)`），M4m 物品可读名（`InventoryItem.key` / `customName`），M4n 实体可交互（`Session.attack` / `interactWith` / `interactAt` / `swing`，伤害包转 `EntityDamaged` 事件）；其余物品组件、状态效果与 Android 运行时验证（M3）尚未开始。

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
| JDK（构建与 Desktop 运行） | 25 |
| Gradle | 由 Wrapper 提供（9.6.0） |

`PATH` 上的 `java` 可以不是 25：Daemon JVM 由 `gradle/gradle-daemon-jvm.properties` 固定为 25，构建时会自动获取。

## 构建

```bash
./gradlew build
```

`build` 里包含测试与两类质量门控：字节码版本（`protocol-core` 必须为 65，桌面模块为 69）与模块依赖边界（业务模块的编译类路径不得出现 Netty、MCProtocolLib、Minestom）。

依赖解析需要访问两个仓库：Maven Central，以及 MCProtocolLib 所在的 `https://repo.opencollab.dev/main/`（仓库配置里按 group 限定，不影响其他依赖）。

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
```

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

- 坐标：`org.pickaxe:protocol-core` 与 `org.pickaxe:client-core`，当前版本 `0.1.0-SNAPSHOT`。
- 本地安装：`./gradlew publishToMavenLocal`（`protocol-core`、`client-core` 及源码 jar）。发布目标仓库在首个外部消费者出现后再定。
- 发布产物已用独立最小工程验证：mavenLocal 解析、协议库作为传递依赖进入运行时类路径、下游编译通过。
- **0.x 阶段不承诺源码或二进制兼容**：公共 API 可能随版本变更且不提前通告，请锁定精确版本。首个下游消费者出现后冻结 API 基线并接入二进制兼容校验，1.0 起遵循语义化版本。
- 通过 Gradle 消费时，`protocol-core` 的运行时依赖（协议库等）经模块元数据（`.module`）传递；纯 Maven 消费者的 POM 只含 `api` 依赖，当前仅支持 Gradle 消费。
- 本项目以 [MIT](LICENSE) 开源，发布 POM 中的许可证字段同步声明；第三方依赖的许可证清单见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)，升级或新增依赖时必须同步更新。

核心技术基线：Kotlin/JVM、MCProtocolLib 26.2（协议 776）、构建与 CLI 使用 Java 25、共享发布产物使用 Java 21 字节码、Gradle Daemon JDK 25。目标宿主为 libGDX Desktop、Android 与 iOS（移动平台待验证）；Web/HTML5 不在目标内。
