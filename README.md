# PickaxeCraft

[![CI](https://github.com/NetheritePickaxe/pickaxecraft/actions/workflows/ci.yml/badge.svg)](https://github.com/NetheritePickaxe/pickaxecraft/actions/workflows/ci.yml)

PickaxeCraft 是一个可连接 Minestom 的 Minecraft Java 版客户端核心库。Headless 是第一阶段交付形态；长期下游是在独立仓库中使用 libGDX 开发的轻量化图形客户端。协议后端采用 MCProtocolLib，本项目提供会话、事件与只读世界模型，不包含渲染、GUI、libGDX 或 Minestom 运行时依赖。

Minestom 在本仓库只用于 `testing:minestom` 联调。最终客户端若需要单人游戏，可在外部仓库的 Desktop/Java 25 宿主中可选集成 Minestom，并通过同一客户端协议连接本地服务端；该能力不进入共享核心，也不作为 Android/iOS 的默认承诺。

下游渲染以 libGDX GL20/GLES 2.0 + ANGLE 为基线，验证 ANGLE Vulkan 并保留回退路径，跟踪 MC 原版的 Vulkan 演进；所有图形依赖仍只存在于外部客户端仓库。

> 当前进度：M2 出生链路已完成，M4a 区块内容已可查询（`Session.blockStateAt` / `biomeAt` / `chunk`，与服务端 `stateId` 对账）；实体、背包与 Android 运行时验证（M3）尚未开始。

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

核心技术基线：Kotlin/JVM、MCProtocolLib 26.2（协议 776）、构建与 CLI 使用 Java 25、共享发布产物使用 Java 21 字节码、Gradle Daemon JDK 25。目标宿主为 libGDX Desktop、Android 与 iOS（移动平台待验证）；Web/HTML5 不在目标内。
