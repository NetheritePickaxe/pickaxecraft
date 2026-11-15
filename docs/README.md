# PickaxeCraft 项目规划

本目录记录当前已确定的技术方案。实现与规划发生偏离时，应先记录原因并更新文档，避免依赖未写下来的历史决策。

## 文档入口

- [架构与技术决策](architecture.md)：项目定位、技术栈、协议边界、并发模型和平台约束。
- [项目结构](project-structure.md)：Gradle 模块、源码目录、依赖方向和公共 API 归属。
- [实施路线](roadmap.md)：分阶段交付内容、验收标准和范围门控。
- [协议后端决策](decisions/0001-protocol-backend.md)：为什么从自研协议改为采用 MCProtocolLib，以及随之改变的代价。
- [技术栈与平台路线决策](decisions/0002.md)：2026-11-03 对技术栈、平台路线与 Android 优先级的评估（历史记录）。
- [Netty 升级决策](decisions/0003-netty-upgrade.md)：为什么升级到 4.2.20.Final、如何用 BOM 对齐传递模块，以及验证与回滚条件。
- [发布到 Maven Central](publishing-central.md)：Central Portal 的 bundle、签名、secrets 与不可变版本规则。
- [错误分类与重试语义](error-handling.md)：每类 `ClientException` 的可重试性与处理建议。
- [上游探针](../tools/upstream-probe/README.md)：用最新 MCProtocolLib 消费已发布产物的每周探针。
- [协议适配契约](protocol-contract.md)：`protocol-core` 与协议库之间哪些行为由库保证、哪些由本项目实现，以及升级检查清单。

## 核心结论

PickaxeCraft 是一个 **Minecraft Java 版客户端核心库**，首要联调目标是 Minestom。Headless 库是第一阶段；最终产品是独立仓库开发的轻量化图形客户端（桌面基于 LWJGL3，移动端以 libGDX 为平台壳），本仓库不承担渲染与 UI。

- 协议、分帧、压缩、加密与状态机由 MCProtocolLib 提供，本项目不重写协议。
- 项目自己负责会话 Actor、事件流、注册表与世界模型，这部分才是产品差异所在。
- `protocol-core` 是唯一的协议适配层：Netty 与 MCProtocolLib 的类型不得穿透到上层模块。
- 构建、CLI 与测试使用 Java 25；共享发布产物使用 Java 21 字节码，供桌面（LWJGL3）与移动宿主消费。
- 平台优先级为 Desktop → Android → iOS；Android 与 iOS 均需运行时 spike，Web/HTML5 明确不做。
- 图形客户端只依赖公共 API；libGDX、GL/Vulkan 与宿主平台类型不得进入核心库。
- 下游渲染完全自研，不使用 libGDX graphics API：桌面走 LWJGL3 双后端（OpenGL 默认 + 实验性 Vulkan），与 MC 26.2 同构；libGDX 仅在 Android/iOS 作平台壳（生命周期、上下文、输入、文件 IO），渲染直调平台 GLES 绑定。
- 核心库不集成 Minestom；`testing:minestom` 仅是测试宿主。可选单人游戏由外部 Desktop/Java 25 客户端集成 Minestom，不能迫使共享产物升级为 Java 25。
- PacketEvents、Minosoft 只作为行为参照与排错资料。

进度以 [README 的“当前进度”](../README.md) 为唯一事实源；各里程碑的交付内容、验收证据与实施记录
见[实施路线](roadmap.md)。

`protocol-core` 负责协议事实转换、区块解码与资源回收，`client-core` 负责会话 Actor、有界缓冲、区块缓存与错误分类；协议库的隐含行为已写入[协议适配契约](protocol-contract.md)。

Android 已在 ART 真机上验证（2026-11-15，API 36），有效 `minSdk` 为 34；iOS 仍是候选。最新状态以 [README 的平台表](../README.md) 为准。

## 参考文档

- [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)
- [Minecraft原版协议文档](https://minecraft.wiki/w/Minecraft_Wiki:Protocol_documentation)
- [minestom](https://minestom.net/)
- [packetevents](https://github.com/retrooper/packetevents)
- [Minosoft](https://github.com/Bixilon/Minosoft)
- [libGDX 的 JVM 语言与平台兼容说明](https://libgdx.com/wiki/jvm-langs/using-libgdx-with-other-jvm-languages)
- [libGDX 开发环境与 JDK 基线](https://libgdx.com/wiki/start/setup)
- [LWJGL3（桌面图形入口：GLFW + GL/Vulkan 绑定）](https://www.lwjgl.org/)
- [ANGLE 上游后端支持矩阵](https://chromium.googlesource.com/angle/angle/+/main/README.md)
- [MC Java 26.2 的 Vulkan 支持与回退策略](https://www.minecraft.net/zh-hans/article/minecraft-java-edition-26-2)
