# PickaxeCraft 项目规划

本目录记录当前已确定的技术方案。实现与规划发生偏离时，应先记录原因并更新文档，避免依赖未写下来的历史决策。

## 文档入口

- [架构与技术决策](architecture.md)：项目定位、技术栈、协议边界、并发模型和平台约束。
- [项目结构](project-structure.md)：Gradle 模块、源码目录、依赖方向和公共 API 归属。
- [实施路线](roadmap.md)：分阶段交付内容、验收标准和范围门控。
- [M0 实施计划](plans/m0-implementation-plan.md)：第一阶段的版本锁定、任务拆分、验证命令与风险；已实施，偏差与验证证据见第 11 节。

## 核心结论

PickaxeCraft 初版是一个 **Headless Minecraft Java 版客户端框架/库**，首要联调目标是 Minestom，不是官方图形客户端的重写，也不是另一个服务端。

- 使用 Kotlin Multiplatform 共享协议和会话核心，只启用 Desktop JVM 与 Android 两个目标。
- 自研单版本协议核心，重复的数据包定义由版本化 Schema 生成。
- Desktop 使用 Java 25；Android 与共享产物使用 Java 21 字节码；Gradle Daemon 使用 JDK 25。
- Netty 只存在于 Desktop 传输适配中，Android 使用协程与平台 Socket。
- PacketEvents、MCProtocolLib 和 Minosoft 只作为架构或协议参考、测试参照，不进入公共核心。
- 先完成 Minestom 离线模式登录和 Android 连通性验证，再扩展世界模型、在线认证和多版本能力。

M0 已完成工程基线：`protocol-core`、`tools:protocol-generator`、`testing:minestom`、`app` 四个模块可构建，版本与协议 Schema 已锁定，字节码与依赖边界由构建任务自动校验。协议编解码与会话尚未实现，因此当前还不能连接服务器。

## 参考文档

- [Minecraft原版协议文档](https://minecraft.wiki/w/Minecraft_Wiki:Protocol_documentation)
- [minestom](https://minestom.net/)
- [packetevents](https://github.com/retrooper/packetevents)
- [Minosoft](https://github.com/Bixilon/Minosoft)
