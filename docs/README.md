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
- [M3 Android 运行时验证（门控中）](plans/m3-android-runtime.md)：阻塞事实、逐条判据与解除条件。
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
M4c 让换维度可用：重生后按新维度重新解码区块，旧维度的区块与实体一并作废。
M4d 让实体有名字：`Session.players` / `nameOf` 把 UUID 关联到用户名，实体快照带上自定义名称。
M4e 让客户端能读聊天：`Session.chat` 回看最近消息，格式来自 `chat_type` 注册表，删除包按编号移除。
M4f 让客户端能发话：`Session.sendChat` / `sendCommand` 上行，服务端广播回来的消息才是回执，不做本地回显。
M4g 让客户端能动：`Session.moveTo` / `look` 只发变化的分量，服务端接受时没有回执，快照由本端推进。
M4h 让客户端能看一眼背包：`Session.inventory` / `container` / `heldItem` 读窗口槽位，`selectHotbarSlot` 切换选中格。
M4i 让客户端知道自己什么状态：`Session.status` 给生命/饥饿/经验，实体快照带 `equipment`。
M4j 让客户端能动手：`Session.clickSlot` 拿起、放下、Shift 转移、换到快捷栏、丢弃，`closeContainer` 关窗；点击不做本地预测，服务端回的内容就是结果。
M4k 让客户端能改世界：`Session.digBlock` 挖掘、`useOnBlock` 放置与交互，服务端下发的方块变更写回区块缓存（写时复制），交互序号作为回执。
M4l/M4n 让客户端认识实体：`Session.attributes(entityId)` 读属性表（按键合并，键名由本仓库注册表还原——协议库内置表会错位），`attack` / `interactWith` / `swing` 上行，伤害包转成 `EntityDamaged` 事件。
M4m 让物品可读名：`InventoryItem.key` 给出 `minecraft:stone` 这样的键名（注册表导出，协议不带这张表），`customName` 读 `custom_name` 组件。
M4o 让本端能声明运动状态：`setSneaking` / `setSprinting` / `setFlying` 走三条不同的上行路径；`Session.abilities` 是服务端权威结果，飞行请求不本地预测。
M4p 让药水效果可读：`Session.effects` 按键合并新增、替换与移除，`EffectKeys` 是版本资产。
M4q 让容器数值可读：`Session.containerProperties` 按下标累积，下标含义留给知道窗口类型的调用方。
M4r 让生物血量可读：`Session.health(entityId)` 与 `TrackedEntity.health`；下标 9 只对生物层级成立，所以先用 `LivingEntityTypes` 判定类型，掉落物等非生物留 null。
M4s 让物品组件可读：`InventoryItem` 带耐久、无法破坏、修复代价、物品名、说明文字与附魔；组件是补丁而非全量，附魔键名来自配置阶段下发的动态注册表而不是静态表。

`protocol-core` 负责协议事实转换、区块解码与资源回收，`client-core` 负责会话 Actor、有界缓冲、区块缓存与错误分类；协议库的隐含行为已写入[协议适配契约](protocol-contract.md)。

Android 只完成了构建链探针（D8 可产出 dex，`minSdk` 下限 26），运行时仍属 M3 门控；物品默认组件原型仍属 M4 的后续增量。

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
