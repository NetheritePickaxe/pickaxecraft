# PickaxeCraft

PickaxeCraft 的目标是成为一个可连接 Minestom 的 Headless Minecraft Java 版客户端框架/库。协议与会话核心计划由 Desktop JVM 和 Android 共用，初版不包含渲染与 GUI。

> 当前处于架构规划和 Gradle 初始化骨架阶段，尚不能连接 Minecraft 服务器。现有 `app`、`utils` 是初始化样例，将按规划逐步迁移。

## 规划文档

- [文档入口](docs/README.md)
- [架构与技术决策](docs/architecture.md)
- [项目结构](docs/project-structure.md)
- [实施路线](docs/roadmap.md)

核心技术基线：Kotlin Multiplatform、自研单版本协议核心、Desktop Java 25、Android Java 21、Gradle Daemon JDK 25。Netty 仅用于 Desktop 传输适配，不进入公共核心。

## 构建

使用仓库内的 Gradle Wrapper：

```bash
./gradlew build
./gradlew check
```

当前骨架仍可通过 `./gradlew run` 运行初始化 `app`。该入口会在实施 M0 后改为 Desktop CLI 示例。
