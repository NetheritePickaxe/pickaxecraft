# 决策 0001：协议后端——采用 MCProtocolLib 取代自研

状态：已采纳（2026-11-03，桌面先行；Android 待真机验证）
日期：2026-11-03
涉及：`docs/architecture.md` §2「自有 Reader/Writer」、§3.3「MCProtocolLib 仅作对照」、§4 分层图

## 1. 触发

用户指出 MCProtocolLib 的 26.2 版本已发布到 Maven 仓库，因此 §3.3 中「只能作对照、不作运行时依赖」的前提不再成立，需要重新评估。

## 2. 事实核查（2026-11-03 实测）

坐标与来源：

- `org.geysermc.mcprotocollib:protocol:26.2`，仓库 `https://repo.opencollab.dev/main/`。
- 这是 Open Collaboration 自建 Maven 仓库，**不是** Maven Central，也与 GitHub Releases 无关。`maven-metadata.xml` 的 `latest`/`release` 为 `26.3-2`，26.2 仍可取到。
- 许可：MIT（POM `<licenses>` 与仓库 `LICENSE.txt` 一致）。

产物形态：

- 796 个 class，字节码全部 major 65（Java 21），与本项目 Android 目标字节码一致；依赖的 `adventure-api:5.2.0` 同为 major 65。
- 直接依赖 11 个：cloudburst `nbt`、gson、MinecraftAuth、slf4j、adventure-text-serializer-gson、text-serializer-nbt、cloudburst math（api/immutable）、三个 fastutil 分支、`netty-all`。
- `netty-all:4.2.1.Final` 本身是聚合 POM：实测 `runtimeClasspath` 解析出 70 个构件，其中约 50 个是 Netty 各模块，含 protobuf、zstd-jni、brotli4j 以及 linux/windows 的 native 分类构件。
- jar 内附带 `networkCodec.nbt`（17.8 KB）与 `networkTags.nbt`（16.2 KB），即 776 的注册表与标签数据。

耦合层级（决定改造成本的关键）：

- 不只是传输。`Session.getChannel()` 直接返回 `io.netty.channel.Channel`，编解码基于 `ByteBuf`，客户端连接只能经 `ClientNetworkSession` + `ChannelFactory` 建立，没有可替换的传输 SPI。
- 因此「用它的协议、换掉它的网络层」不成立：Netty 会跟着进入依赖图与部分公共 API。

Android 相关：

- `netty-common:4.2.1` 显式识别 Android（`Platform: Android`、`sun.misc.Unsafe: unavailable (Android)`），会退到 no-Unsafe 路径，不会直接崩。
- MCPL 使用 `java.lang.Record`（多处）与 `java.util.HexFormat`（`UUIDUtils`），需要 D8 反糖；本地无法验证，必须在真机或模拟器上确认。

## 3. 实测证据

探针工程（独立于本仓库，JVM 21）分别对 `testing:minestom`（Minestom 2026.10.07-26.2，离线模式，端口 25599）执行：

- status：`event=status.ok version=26.2 protocol=776 players=0/1`，描述被解析为 Adventure `Component`。
- 离线登录（`new MinecraftProtocol("ProbeBot")`）收包序列：
  `ClientboundLoginCompressionPacket` → `ClientboundLoginFinishedPacket` →
  21 × `ClientboundRegistryDataPacket` → `ClientboundUpdateTagsPacket` →
  `ClientboundFinishConfigurationPacket`，共 34 个包，客户端状态进入 `GAME`，无解码错误。
- 发包序列：`ClientIntentionPacket` → `ServerboundHelloPacket` → `ServerboundLoginAcknowledgedPacket` → `ServerboundSelectKnownPacks` → `ServerboundFinishConfigurationPacket`。
- 完成上述能力的探针代码不到 120 行，其中协议相关代码为 0 行。

未覆盖：服务端日志停在 `event=player.configuring`，未出现 `player.spawned`，客户端也没有收到 `ClientboundLoginPacket`。该现象与协议实现选型无关（M0 阶段自研方案同样止步于此），属于 harness 侧尚未定位的联调缺口，需单独立项。

## 4. 取舍

收益：

1. 776 的全套数据包、注册表/标签数据、分帧、压缩、AES-CFB8、keepalive 自动应答、transfer、代理、微软认证全部现成，M1 的协议部分直接归零。
2. 该库是 Geyser 的生产依赖（服务端方向被大规模验证），对 Minestom 已实测连通。
3. MIT 许可，单版本 26.2 与本项目锁定的协议号天然一致，不需要多版本分支。
4. 注册表 NBT 这类「必须有、但无法从 `minecraft-data` 的 `protocol.json` 得到」的数据由上游随包提供，省掉一条独立的取数链路。

代价：

1. 协议不再是 KMP `commonMain` 能力。MCPL 是 Java + Netty，公共核心一旦依赖它就只能跑 JVM——Android 从「KMP 共用代码」退化为「同一份 JVM 依赖在两端跑」，可行性取决于 Netty 在 Android 上的实际表现。
2. Netty 进入公共 API 边界（`Session.getChannel()`），与 `architecture.md` §4「公共 API 不暴露 Netty `ByteBuf`」冲突，必须加适配层隔离。
3. 依赖体积失控：默认 70 个构件，需要排除 `netty-all`、改依赖细粒度模块（buffer/common/transport/handler/codec-base/codec-compression）。
4. 供应链多一个源：不在 Maven Central，CI 需要访问 `repo.opencollab.dev`，建议用 `content { includeGroup(...) }` 限定，避免该仓库参与其他依赖的元数据解析。
5. 版本漂移：上游已到 26.3-2，我们只能锁 26.2 跟随 Minestom，升级节奏受上游与 Minestom 双重约束。

## 5. 建议

采用 MCProtocolLib。

自研的真正价值是「拥有协议实现」，而项目要交付的是「可编程的 Minecraft 客户端」。776 的数据包布局与注册表数据是纯机械劳动，重写不产生产品价值，却要持续承担字段错误、类型边界、压缩/加密状态机等风险；这部分恰恰是 MCPL 已经做对并被生产验证的部分。项目真正的差异化在会话模型、事件流、世界模型与跨平台交付，这些仍然要自己写。

回到自研的条件（任一成立）：

- Android 真机验证表明 Netty 不可用，且 Android 必须支持；
- 出现 MCPL 结构上无法表达的协议定制需求。

## 6. 落地步骤（M1 重定义）

1. 依赖：`gradle/libs.versions.toml` 增加 `mcprotocollib = "26.2"`；`settings.gradle.kts` 增加 opencollab 仓库并按 group 限定；排除 `netty-all`，改用细粒度 Netty 模块。
2. 模块：删除 `:tools:protocol-generator` 与 `protocol-core/schema/**`；`:protocol-core` 改为 Kotlin/JVM 适配层，把 MCPL 的数据包与事件转成项目自己的模型，禁止 `ByteBuf`/`Channel`/MCPL `Packet` 穿透到 `client-core` 以上。
3. 门控：`verifyModuleBoundaries` 由「禁止 netty」改为「只有 `protocol-core` 可依赖 netty 与 MCPL，且公共 API 不得出现其类型」。
4. 里程碑：M1 = 探针能力产品化（status、离线登录到 GAME、事件流、keepalive 归属、优雅关闭）；M2 = 世界模型与区块。
5. Android：单独立项 spike（模拟器跑 Netty + MCPL 的 status/login），通过后才写入支持矩阵。

## 7. 待确认

- 是否接受「Android 从 KMP 共用降级为同一 JVM 依赖 + 待真机验证」。——已确认接受。
- 是否现在执行迁移。——已执行，见下节。

## 8. 实施结果（2026-11-03）

与提案阶段的实际差异：

- 仓库依赖：只声明 4 个 Netty 模块（buffer/common/transport/handler），其余靠传递；`runtimeClasspath` 从 70 个构件降到 39 个，`netty-all` 已排除。
- 仓库白名单不止一个 group：Open Collaboration 还托管 `org.cloudburstmc.math` 与 `com.nukkitx.fastutil`，只放行 `org.geysermc.mcprotocollib` 会导致解析失败（第一次构建即暴露）。
- 删除的内容：`tools:protocol-generator`、`protocol-core/schema/**`、KMP 与 AGP 构建链、CI 里的 Android SDK 安装步骤。`buildSrc` 的 `pickaxe.kmp.library` 由 `pickaxe.jvm.shared` 取代：Java 21 字节码 + `verifyBytecode`（major 65）保留，Android 可消费性仍被断言。
- `gradle/libs.versions.toml`：`agp`、`kotlinMultiplatform`、`androidKmpLibrary`、`nettyBom` 一并移除；Netty 版本对齐协议库编译时的 `4.2.1.Final`。
- `protocol-core` 现在只含 `ProtocolVersion` 与两个测试，产物字节码实测 major 65。
- 联调证据：`ClientInteropTest` 在 `./gradlew build` 中通过——Status 返回 776、离线登录进入 `GAME`、收到注册表数据。`ClientListener` 在本监听器之后才切换状态，因此断言点选在「收尾确认包发出时」，而不是收包回调内。
- 仍未解决：harness 侧 `event=player.spawned` 不触发（与协议选型无关），列入 M2。
