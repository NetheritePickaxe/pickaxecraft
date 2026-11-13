# PickaxeCraft 项目规划

本目录记录当前已确定的技术方案。实现与规划发生偏离时，应先记录原因并更新文档，避免依赖未写下来的历史决策。

## 文档入口

- [架构与技术决策](architecture.md)：项目定位、技术栈、协议边界、并发模型和平台约束。
- [项目结构](project-structure.md)：Gradle 模块、源码目录、依赖方向和公共 API 归属。
- [实施路线](roadmap.md)：分阶段交付内容、验收标准和范围门控。
- [协议后端决策](decisions/0001-protocol-backend.md)：为什么从自研协议改为采用 MCProtocolLib，以及随之改变的代价。
- [技术栈与平台路线决策](decisions/0002.md)：2026-11-03 对技术栈、平台路线与 Android 优先级的评估（历史记录）。
- [Netty 升级决策](decisions/0003-netty-upgrade.md)：为什么升级到 4.2.20.Final、如何用 BOM 对齐传递模块，以及验证与回滚条件。
- [M0 实施计划](plans/m0-implementation-plan.md)：工程基线的历史计划、实施偏差与验证证据。
- [M2 实施记录](plans/m2-implementation-plan.md)：出生链路、协议应答归属，以及“harness 从未 tick”这个根因。
- [M4a 实施记录](plans/m4a-chunk-model.md)：区块格式与维度高度、区块缓存，以及 long 数组长度前缀的踩坑。
- [M4b 实施记录](plans/m4b-entity-tracking.md)：实体跟踪的相对位移累积、事件与状态的分工。
- [M4c 实施记录](plans/m4c-respawn.md)：重生换维度时缓存、维度高度与 `player_loaded` 的复位。
- [M4d 实施记录](plans/m4d-player-identity.md)：玩家列表的分片合并、元数据下标白名单与实体→名字的查询时关联。
- [M4e 实施记录](plans/m4e-chat.md)：三类聊天包的收敛、格式注册表与确认/删除义务。
- [M4f 实施记录](plans/m4f-chat-upstream.md)：无签名上行与安全聊天的边界、last-seen 窗口、命令包与输入校验。
- [M4g 实施记录](plans/m4g-movement-upstream.md)：四个移动包的选择规则、无回执下的本地姿态、状态位为何不能乱声明。
- [M4h 实施记录](plans/m4h-inventory.md)：两套槽位编号（护甲段反序）、容器累积与快照、成功无回执的第三例。
- [M4i 实施记录](plans/m4i-player-state.md)：两个包各写一半的状态快照、装备为何能读而生物血量不能。
- [M4j 实施记录](plans/m4j-container-click.md)：点击的「动作 + 参数」、为什么不预测（以及为什么光标要如实声明）、关窗同样没有回执。
- [M4k 实施记录](plans/m4k-block-interaction.md)：交互序号是动作的回执、方块坐标为什么必须带位宽校验、写时复制的空层计数。
- [M4l 实施记录](plans/m4l-attributes.md)：协议库属性表错位的规避、按需创建的属性实例与空包。
- [M4n 实施记录](plans/m4n-entity-interaction.md)：攻击拆包与第四例「成功没有回执」。
- [M4m 实施记录](plans/m4m-item-names.md)：键名表是版本资产、空文本组件与 null 的区别。
- [M4o 实施记录](plans/m4o-movement-state.md)：按键是状态包、冲刺要带实体 id、能力由服务端权威下发。
- [M4p 实施记录](plans/m4p-mob-effects.md)：效果注册表不下发、一次只推一个效果、按键合并。
- [M4q 实施记录](plans/m4q-container-properties.md)：容器属性下标不翻译、按下标累积。
- [M4r 实施记录](plans/m4r-entity-health.md)：元数据下标依赖实体层级、分层表是版本资产、掉落物反例。
- [M4s 实施记录](plans/m4s-item-components.md)：组件是补丁而非全量、附魔注册表动态下发。
- [M4t 实施记录](plans/m4t-chunk-environment.md)：光照掩码与层偏移、高度图是快照、方块实体没有删除包。
- [M4u 实施记录](plans/m4u-command-completion.md)：命令树下标不可重排、补全的替换区间、没有回包不等于空候选。
- [M4v 实施记录](plans/m4v-cross-server-transfer.md)：转移包不结束会话、目标地址原样转达、换服是一条新连接。
- [M4w 实施记录](plans/m4w-libgdx-sample.md)：`MeshData` 而非 `Mesh`、`SolidQuery` 而非区块类型、headless 也要 natives。
- [M4x 实施记录](plans/m4x-riding.md)：上/下车只有乘客列表、载具包不带实体 id、骑乘时封住玩家位置包。
- [M4y 实施记录](plans/m4y-item-prototypes.md)：固有属性靠原型表、补丁三态、版本资产改为可重跑导出。
- [M4z 进展（见 roadmap）](roadmap.md)：插件消息是裸字节通道、品牌串按 varint 前缀代读、上行上限 32767。
- [M4aa 进展（见 roadmap）](roadmap.md)：拖拽是三段包、创造槽位就是窗口编号、gameMode 双源门控。
- [M4ab 进展（见 roadmap）](roadmap.md)：破坏进度是观察他人的能力、RESET 是删条目、换维度即作废。
- [M3 Android 运行时验证（门控中）](plans/m3-android-runtime.md)：阻塞事实、逐条判据与解除条件。
- [M1 实施计划](plans/m1-implementation-plan.md)：自有客户端 API、连接生命周期、联调迁移、CLI 与提前 Android 探针；末尾附实施记录与偏差。
- [发布到 Maven Central](publishing-central.md)：Central Portal 的 bundle、签名、secrets 与不可变版本规则。
- [错误分类与重试语义](error-handling.md)：每类 `ClientException` 的可重试性与处理建议。
- [上游探针](../tools/upstream-probe/README.md)：用最新 MCProtocolLib 消费已发布产物的每周探针。
- [协议适配契约](protocol-contract.md)：`protocol-core` 与协议库之间哪些行为由库保证、哪些由本项目实现，以及升级检查清单。
- [Android 反糖探针](android-dex-probe.md)：D8 实测结论（minSdk 26、重复类、`HexFormat`）与仍未验证的运行时项。
- [2026-11-13 测试分层与稳定性](reviews/2026-11-13-test-layering-and-stability.md)：E2E 拆分、超时诊断、终止路径断言与 10 次稳定性记录。
- [2026-11-13 平台证据](reviews/2026-11-13-platform-evidence.md)：Android/iOS 阻塞事实、Netty 4.2.20 在 ART 上的未验证项与解除条件。
- [2026-11-12 现状审查与可交付性判定](reviews/2026-11-12-orientation-and-deliverability-review.md)：代码导览、路线图审计、同类项目对比，以及发布门槛与优先行动清单。

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
见[实施路线](roadmap.md)以及 `docs/plans/` 下的条目。

`protocol-core` 负责协议事实转换、区块解码与资源回收，`client-core` 负责会话 Actor、有界缓冲、区块缓存与错误分类；协议库的隐含行为已写入[协议适配契约](protocol-contract.md)。

Android 仍属候选：构建链探针通过（D8 可产出 dex，`minSdk` 下限 26），运行时验证是 M3 门控；最新状态以 [README 的平台表](../README.md) 与 [M3 门控](plans/m3-android-runtime.md) 为准。

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
