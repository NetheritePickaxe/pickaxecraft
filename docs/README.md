# PickaxeCraft 项目规划

本目录记录当前已确定的技术方案。实现与规划发生偏离时，应先记录原因并更新文档，避免依赖未写下来的历史决策。

## 文档入口

- [架构与技术决策](architecture.md)：项目定位、技术栈、协议边界、并发模型和平台约束。
- [项目结构](project-structure.md)：Gradle 模块、源码目录、依赖方向和公共 API 归属。
- [实施路线](roadmap.md)：分阶段交付内容、验收标准和范围门控。
- [协议后端决策](decisions/0001-protocol-backend.md)：为什么从自研协议改为采用 MCProtocolLib，以及随之改变的代价。
- [M0 实施计划](plans/m0-implementation-plan.md)：第一阶段的版本锁定、任务拆分、验证命令与风险；已实施，偏差与验证证据见第 11 节。

## 核心结论

PickaxeCraft 初版是一个 **Headless Minecraft Java 版客户端框架/库**，首要联调目标是 Minestom，不是官方图形客户端的重写，也不是另一个服务端。

- 协议、分帧、压缩、加密与状态机由 MCProtocolLib 提供，本项目不重写协议。
- 项目自己负责会话 Actor、事件流、注册表与世界模型，这部分才是产品差异所在。
- `protocol-core` 是唯一的协议适配层：Netty 与 MCProtocolLib 的类型不得穿透到上层模块。
- 桌面使用 Java 25；`protocol-core` 以 Java 21 字节码产出，为将来的 Android 宿主保留可用性。
- Android 尚未验证：Netty 与协议库在 Android 上需要真机或模拟器 spike，通过前不写入支持矩阵。
- PacketEvents、Minosoft 只作为行为参照与排错资料。

M0 已完成工程基线，协议后端已在 M0 收尾时切换为 MCProtocolLib 并通过真实握手验证：`protocol-core`、`testing:minestom`、`app` 三个模块可构建，Status 查询与离线登录到 play 已由 `ClientInteropTest` 覆盖。

## 参考文档

- [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)
- [Minecraft原版协议文档](https://minecraft.wiki/w/Minecraft_Wiki:Protocol_documentation)
- [minestom](https://minestom.net/)
- [packetevents](https://github.com/retrooper/packetevents)
- [Minosoft](https://github.com/Bixilon/Minosoft)
