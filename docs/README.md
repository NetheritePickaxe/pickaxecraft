# PickaxeCraft 项目规划

本目录记录当前已确定的技术方案。实现与规划发生偏离时，应先记录原因并更新文档，避免依赖未写下来的历史决策。

## 文档入口

- [架构与技术决策](architecture.md)：项目定位、技术栈、协议边界、并发模型和平台约束。
- [项目结构](project-structure.md)：Gradle 模块、源码目录、依赖方向和公共 API 归属。
- [实施路线](roadmap.md)：分阶段交付内容、验收标准和范围门控。
- [协议后端决策](decisions/0001-protocol-backend.md)：为什么从自研协议改为采用 MCProtocolLib，以及随之改变的代价。
- [M0 实施计划](plans/m0-implementation-plan.md)：工程基线的历史计划、实施偏差与验证证据。
- [M2 实施记录](plans/m2-implementation-plan.md)：出生链路、协议应答归属，以及“harness 从未 tick”这个根因。
- [M4a 实施记录](plans/m4a-chunk-model.md)：区块格式与维度高度、区块缓存，以及 long 数组长度前缀的踩坑。
- [M4b 实施记录](plans/m4b-entity-tracking.md)：实体跟踪的相对位移累积、事件与状态的分工。
- [M1 实施计划](plans/m1-implementation-plan.md)：自有客户端 API、连接生命周期、联调迁移、CLI 与提前 Android 探针；末尾附实施记录与偏差。
- [协议适配契约](protocol-contract.md)：`protocol-core` 与协议库之间哪些行为由库保证、哪些由本项目实现，以及升级检查清单。
- [Android 反糖探针](android-dex-probe.md)：D8 实测结论（minSdk 26、重复类、`HexFormat`）与仍未验证的运行时项。

## 核心结论

PickaxeCraft 是一个 **Minecraft Java 版客户端核心库**，首要联调目标是 Minestom。Headless 库是第一阶段；最终产品是在独立仓库中使用 libGDX 开发的轻量化图形客户端，本仓库不承担渲染与 UI。

- 协议、分帧、压缩、加密与状态机由 MCProtocolLib 提供，本项目不重写协议。
- 项目自己负责会话 Actor、事件流、注册表与世界模型，这部分才是产品差异所在。
- `protocol-core` 是唯一的协议适配层：Netty 与 MCProtocolLib 的类型不得穿透到上层模块。
- 构建、CLI 与测试使用 Java 25；共享发布产物使用 Java 21 字节码，供 libGDX Desktop 和移动宿主消费。
- 平台优先级为 Desktop → Android → iOS；Android 与 iOS 均需运行时 spike，Web/HTML5 明确不做。
- 图形客户端只依赖公共 API；libGDX、OpenGL 与宿主平台类型不得进入核心库。
- 下游渲染采用 GL20/GLES 2.0 + ANGLE 基线，实测 ANGLE Vulkan 并保留回退路径；跟踪 MC 26.2 的 Vulkan 演进，但不把实验性原生 Vulkan 作为初版硬要求。
- 核心库不集成 Minestom；`testing:minestom` 仅是测试宿主。可选单人游戏由外部 Desktop/Java 25 客户端集成 Minestom，不能迫使共享产物升级为 Java 25。
- PacketEvents、Minosoft 只作为行为参照与排错资料。

M0 完成工程基线与协议后端切换；M1 补齐自有客户端 API；M2 打通出生链路：调用方只依赖 `client-core` 就能查询状态、离线登录、完成出生，并按事件流观察会话。
M4a 让客户端读到区块内容：`Session.blockStateAt` / `biomeAt` / `chunk` 返回解码后的方块状态与生物群系，并与服务端 `stateId` 对账。
M4b 让客户端看到实体：`Session.entity` / `entities` 返回位置与朝向（相对位移已在适配层叠加），增删通过事件流可见。
`protocol-core` 负责协议事实转换、区块解码与资源回收，`client-core` 负责会话 Actor、有界缓冲、区块缓存与错误分类；协议库的隐含行为已写入[协议适配契约](protocol-contract.md)。

Android 只完成了构建链探针（D8 可产出 dex，`minSdk` 下限 26），运行时仍属 M3 门控；实体、背包与光照仍属 M4 的后续增量。

## 参考文档

- [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)
- [Minecraft原版协议文档](https://minecraft.wiki/w/Minecraft_Wiki:Protocol_documentation)
- [minestom](https://minestom.net/)
- [packetevents](https://github.com/retrooper/packetevents)
- [Minosoft](https://github.com/Bixilon/Minosoft)
- [libGDX 的 JVM 语言与平台兼容说明](https://libgdx.com/wiki/jvm-langs/using-libgdx-with-other-jvm-languages)
- [libGDX 开发环境与 JDK 基线](https://libgdx.com/wiki/start/setup)
- [libGDX ANGLE 接入与限制](https://libgdx.com/wiki/graphics/opengl-es-support/angle)
- [ANGLE 上游后端支持矩阵](https://chromium.googlesource.com/angle/angle/+/main/README.md)
- [MC Java 26.2 的 Vulkan 支持与回退策略](https://www.minecraft.net/zh-hans/article/minecraft-java-edition-26-2)
