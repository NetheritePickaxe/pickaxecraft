# PickaxeCraft

PickaxeCraft 的目标是成为一个可连接 Minestom 的 Headless Minecraft Java 版客户端框架/库。协议与会话核心由 Desktop JVM 和 Android 共用，初版不包含渲染与 GUI。

> 当前为 M0 基线：工程结构、版本锁定、协议 Schema、自动化质量门控与 CLI 骨架已完成，尚未实现协议编解码，因此还不能连接服务器。

## 规划文档

- [文档入口](docs/README.md)
- [架构与技术决策](docs/architecture.md)
- [项目结构](docs/project-structure.md)
- [实施路线](docs/roadmap.md)
- [M0 实施计划](docs/plans/m0-implementation-plan.md)

## 环境要求

| 项 | 版本 |
| --- | --- |
| JDK（构建与 Desktop 运行） | 25 |
| Android SDK | Platform 36 + Build Tools 36.0.0（仅 Android 目标需要，通过 `ANDROID_HOME` 或 `local.properties` 提供） |
| Gradle | 由 Wrapper 提供（9.6.0） |

`PATH` 上的 `java` 可以不是 25：Daemon JVM 由 `gradle/gradle-daemon-jvm.properties` 固定为 25，构建时会自动获取。

## 构建

```bash
./gradlew build
```

`build` 里包含测试与两类质量门控：字节码版本（Desktop 69 / Android 65）与公共模块依赖边界（不得出现 Netty、Minestom）。

## 使用

```bash
./gradlew :app:run --args="--version"    # 客户端版本、Minecraft 26.2、协议 776、Minestom 版本
./gradlew :app:run --args="--help"
```

联调用的 Minestom 服务端：

```bash
./gradlew :testing:minestom:run          # 监听 127.0.0.1:25565，离线认证
```

启动后输出 `event=server.started … protocol=776`，玩家连接时输出 `event=player.configuring`，出生后输出 `event=player.spawned`；Ctrl+C 停止时输出 `event=server.stopping` / `event=server.stopped`。该服务只绑定回环地址，不会暴露到局域网。

## 版本锁定

- 依赖版本唯一来源：`gradle/libs.versions.toml`
- 协议版本唯一来源：`protocol-core/schema/26.2/`（来源、提交与校验和见该目录的 `SOURCE.md`，常量由 `tools:protocol-generator` 在构建期生成）

核心技术基线：Kotlin Multiplatform、自研单版本协议核心、Desktop Java 25、Android Java 21、Gradle Daemon JDK 25。Netty 仅用于 Desktop 传输适配，不进入公共核心。
