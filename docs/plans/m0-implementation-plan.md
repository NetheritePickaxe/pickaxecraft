# M0 实施计划：工程基线与版本锁定

状态：已确认（协议版本 26.2、精简四模块），待实施  
日期：2026-11-02  
范围：[roadmap.md](../roadmap.md) 第 2 节 M0  
依据：已用临时工程验证构建组合（见第 4 节），未在正式仓库改动任何构建逻辑

## 1. 结论

M0 的技术前提已经验证：Kotlin 2.4.21 + AGP 9.4.1 + Gradle 9.6.0 + JDK 25 可以在同一个模块内产出 Desktop Java 25 与 Android Java 21 两套字节码，并生成 AAR。M0 的主要工作因此不是探索可行性，而是**把已验证的配置固化到仓库、把版本锁成单一来源、把验收变成可自动执行的检查**。

需要先与现有文档对齐的硬事实有三条：

1. AGP 9 起，`kotlin.multiplatform` 不能与 `com.android.library` 放在同一个 Gradle 子项目，必须改用 `com.android.kotlin.multiplatform.library` 的 `kotlin { androidLibrary { } }`。
2. 锁定目标版本 26.2（协议 776）的完整 Schema 尚未进入 `minecraft-data` 主干，只存在于 `pc_26_2` 分支；必须按提交哈希锁定并自留副本。
3. 协议数据规模已经可量化：776 共 256 个数据包（handshaking 2/0、status 2/2、login 5/6、configuration 10/20、play 69/141），M1 必须按状态分批实现，不能一次性铺开。

## 2. 与现有文档不一致的现状

### 2.1 Android 目标的接入方式已变

[project-structure.md](../project-structure.md) 只写了“KMP 的 Desktop target 可命名为 `desktop`”，没有指定 Android 插件。AGP 9.0 起，新版 DSL 不再支持 `org.jetbrains.kotlin.multiplatform` 与 `com.android.library` 同模块，官方替代是 Android-KMP Library 插件：

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_25) } }
    androidLibrary {
        namespace = "org.pickaxe.protocol"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        withHostTestBuilder {}.configure {}
    }
}
```

影响：

- 源集目录只有 `src/androidMain`、`src/androidHostTest`、`src/androidDeviceTest`，`src/main` 不再适用。
- 该插件是单变体架构，不支持 buildTypes / productFlavors。对库模块无影响，但未来 Android 宿主必须单独建模块。
- `protocol-core` 与 `client-core` 的字节码目标改由 `androidLibrary.compilerOptions.jvmTarget` 控制，而不是 `android { compileOptions }`。
- 文档中的“Android target”表述应改为“Android-KMP Library target”，并注明 AGP 9 的这条限制。

### 2.2 26.2 的 Schema 来源不在主干

| 分支 | 26.2 数据 | 说明 |
| --- | --- | --- |
| `master` | 无 | 只到 26.1（协议 775） |
| `pc_26_2` | 有 | 提交 `282f19b06c7cc98f055c39787acf93904b9587fa`（2026-10-29） |

因此“锁定 Schema 来源”不能只写版本号，必须记录仓库、分支、提交哈希、文件校验和与许可证。上游分支可能被重写，M1 开始时要把 `protocol.json` 复制进本仓库（MIT，保留版权与来源声明），不让构建依赖网络。

### 2.3 版本目录落后于可用版本

当前 `gradle/libs.versions.toml`：Kotlin 2.4.20、coroutines 1.10.2、serialization 1.11.0。当前稳定版为 Kotlin 2.4.21、coroutines 1.11.0、serialization 1.12.0。另外本机 `PATH` 上的 `java` 是 21，JDK 25 位于 `~/.jdks/ms-25.0.5.0.2`；Daemon JVM 条件文件已经是 25，但需要把“实际使用 25”写入验收，而不是假设。

## 3. 版本锁定矩阵

| 项 | 锁定值 | 依据 |
| --- | --- | --- |
| Minecraft | Java Edition 26.2 | Minecraft Wiki 26.2 页面：发布于 2026-06-16，最低 Java SE 25 |
| 协议号 | 776 | 26.2 版本元数据；`version.json` 中 `version: 776` |
| Minestom | `2026.10.07-26.2` | Maven Central 最新发布；jar 内 `MinecraftServer` 字节码主版本 69（Java 25） |
| Schema | `PrismarineJS/minecraft-data` @ `pc_26_2` / `282f19b0…` | `protocol.json` 338,768 字节，SHA-256 `e19d8398…305d`；`version.json` SHA-256 `e4a731ef…1d0c`；MIT |
| Kotlin | 2.4.21 | 当前稳定版；支持 JVM target 8–26；支持 Gradle 9.7 |
| AGP | 9.4.1 | 最低 Gradle 9.6.0、最高 API 37、Build Tools 36.0.0，与现有 Wrapper 匹配 |
| Gradle | 9.6.0 | 现有 `gradle-wrapper.properties` |
| JDK（构建/Desktop） | 25 | 现有 `gradle-daemon-jvm.properties`；`gradle --version` 显示 Daemon JVM “Compatible with Java 25” |
| Android 字节码 | Java 21（major 65） | AGP 9.4.1 + `androidLibrary.compilerOptions.jvmTarget` |
| Android SDK | compileSdk 36、minSdk 24、Build Tools 36.0.0 | 本机已安装 36；AGP 9.4 默认 Build Tools 36.0.0 |
| Netty | 4.2.19.Final | 4.2 线最新稳定版（2026-10-04）；4.2.3+ 支持 Java 24+ 的 MemorySegment 路径 |
| kotlinx-coroutines | 1.11.0 | Maven Central 最新稳定版 |
| kotlinx-serialization | 1.12.0 | Maven Central 最新稳定版；仅用于 Schema/配置，不用于二进制协议 |
| 测试 | kotlin-test + JUnit Jupiter 5.14.4 | Desktop 用 JUnit Platform；Android 宿主测试沿用 kotlin-test 默认 |

不选 AGP 9.5.0、Kotlin 2.5.0-Beta、Netty 5.0.0-Alpha：M0 不引入未验证的升级，升级应作为独立、由 CI 把关的变更。

## 4. 可行性探针（已完成）

在 `D:\ai\_kmp_probe`（临时工程，不进入仓库）复现了 M0 的核心构建形状：

- `buildSrc` 预编译约定插件同时应用 KMP 与 Android-KMP Library 插件，模块侧只写 `namespace`。
- `:shared:assemble` 成功，产出 `build/outputs/aar/shared.aar`；相关任务为 `bundleAndroidMainAar`、`assembleAndroidMain`。
- 字节码实测：`build/classes/kotlin/android/main` 主版本 65，`build/classes/kotlin/desktop/main` 主版本 69。
- `:shared:desktopTest`（JUnit 5）与 `:shared:testAndroidHostTest` 均通过同一份 `commonTest`。
- `--configuration-cache` 可正常存储并复用。
- `explicitApi()` 生效：缺少显式可见性会直接编译失败。

结论：构建层不再有未知项。剩余风险集中在 CI 环境（Android SDK/JDK 安装）与运行时依赖（Minestom 必须跑在 Java 25）。

## 5. 任务分解

顺序：P0 → P1 → P2 → P3 →（P4、P5 可并行）→ P6 → P7。每个任务以可执行命令作为完成判据。

### P0 仓库与版本目录收口

改动：

- `gradle/libs.versions.toml` 写入第 3 节全部版本，新增插件别名：`kotlinMultiplatform`、`androidKmpLibrary`、`kotlinSerialization`。
- `settings.gradle.kts`：`pluginManagement` 增加 `google()`、`mavenCentral()`、`gradlePluginPortal()`；`dependencyResolutionManagement` 增加 `google()`；注册 `:protocol-core`、`:tools:protocol-generator`、`:testing:minestom`、`:app`，移除 `:utils`。
- 删除 `utils/`（`Printer`、`Utilities` 及测试无真实调用方）。
- `app/build.gradle.kts` 去掉 `:utils` 依赖（CLI 内容在 P5 完成，本步保持可编译）。

完成判据：

```
./gradlew projects        # 只列出目标模块
./gradlew build           # 通过
```

### P1 buildSrc 约定插件

改动：

- `buildSrc/settings.gradle.kts` 增加 `google()` 仓库。
- `buildSrc/build.gradle.kts` 增加 `kotlinGradlePlugin` 与 `androidGradlePlugin`（`com.android.tools.build:gradle`）依赖，使预编译脚本能应用两个插件。
- 新增 `pickaxe.kmp.library`：`explicitApi()`、`jvmToolchain(25)`、`jvm("desktop")` 目标 Java 25、`androidLibrary`（compileSdk 36 / minSdk 24 / jvmTarget 21 / 宿主测试）、`commonTest` 引入 kotlin-test、`desktopTest` 引入 kotlin-test-junit5 与 Jupiter Engine、`desktopTest` 使用 JUnit Platform。
- 新增 `pickaxe.jvm.library` / `pickaxe.jvm.cli`：Java 25、`explicitApi()`、JUnit 5。
- 命名空间、模块专属配置保留在模块的 `build.gradle.kts`，约定插件不写死业务信息。

完成判据：

```
./gradlew :protocol-core:assemble
# build/outputs/aar/protocol-core.aar 存在
# build/libs/protocol-core-desktop.jar 存在
# 两个目录下 class 主版本分别为 65 / 69
```

### P2 tools:protocol-generator

改动：

- 新建 JVM 25 工具模块，读取 `protocol-core/schema/<version>/version.json`，生成 `ProtocolVersion.kt`。
- 生成内容只包含锁定元数据：`MINECRAFT_VERSION`、`PROTOCOL_VERSION`、`SCHEMA_COMMIT`、`SCHEMA_SHA256`；不写时间戳，保证同输入同输出。
- Gradle 接线：`generateProtocolVersion` 任务声明 `@InputDirectory` / `@OutputDirectory`，输出到 `build/generated/protocol/commonMain`，并加入 `protocol-core` 的 `commonMain` 源集；生成器仅作为构建期依赖，不进入运行时。
- 生成器暂不解析 `protocol.json`；数据包生成在 M1 扩展，避免在 M0 承诺尚未验证的 Schema 映射规则。

完成判据：

```
./gradlew :protocol-core:generateProtocolVersion
# 连续执行两次，输出文件 SHA-256 相同
# 修改 version.json 后任务变为非 UP-TO-DATE
```

### P3 protocol-core 与 Schema 落位

改动：

- `protocol-core` 使用 `pickaxe.kmp.library`，`namespace = "org.pickaxe.protocol"`。
- 新增 `protocol-core/schema/26.2/version.json`、`protocol.json`（来自 `pc_26_2@282f19b0`）。
- 新增 `protocol-core/schema/26.2/SOURCE.md`：上游仓库、分支、提交、抓取日期、两个文件的 SHA-256、MIT 许可证说明。
- 新增 `protocol-core/schema/LICENSE-minecraft-data`（保留上游版权声明）。
- `commonTest` 增加对生成常量的断言（协议号 776、版本 26.2），确保生成链路接通。

完成判据：

```
./gradlew :protocol-core:build :protocol-core:testAndroidHostTest
# 仓库中不出现生成的 ProtocolVersion.kt（仅存在于 build/）
```

### P4 testing:minestom

改动：

- 新建 JVM 25 模块，依赖 `net.minestom:minestom:2026.10.07-26.2`。
- 最小服务：启动 Minestom、创建 flat 实例、监听 `127.0.0.1:25565`、启动与关闭各打印一行结构化日志（含协议号 776）。
- 用 `application` 插件提供 `run` 任务；关闭流程显式调用服务端停止，不依赖进程被杀。

风险控制：Minestom 26.2 的启动/认证 API 以锁定版本为准，编码前用 IDE 核对 `MinecraftServer` 初始化与 `Auth` 枚举，不照抄旧版本示例。

完成判据：

```
./gradlew :testing:minestom:run
# 日志出现监听地址与 protocol 776；Ctrl+C 后进程正常退出
```

### P5 app CLI 与样例清理

改动：

- `app` 使用 `pickaxe.jvm.cli`，`mainClass = "org.pickaxe.app.MainKt"`。
- 命令面：`--version` 输出客户端版本、Minecraft 26.2、协议 776、Minestom 版本；`--help` 输出用法；无参数时打印用法而非静默退出。
- 删除初始化样例中的循环、`Printer`、`utils` 相关内容。

完成判据：

```
./gradlew :app:run --args="--version"
# 输出包含 26.2 / 776 / 2026.10.07-26.2
./gradlew build
```

### P6 自动质量门控

改动：

- `verifyBytecode`：扫描模块 `build/classes`，按目标断言 class 主版本（Desktop/工具 69，Android 65），超限即失败；接入 `check`。
- `verifyModuleBoundaries`：解析 `protocol-core` 的 desktop/android 编译类路径，断言不含 `io.netty`、`net.minestom`、`com.android.tools`；断言生产模块不依赖 `:testing:minestom`；断言 `:app` 不依赖 Minestom。
- 两个任务必须兼容配置缓存（不在执行阶段访问 `Project`）。

完成判据：

```
./gradlew build
# 临时给 protocol-core 加 io.netty 依赖 → verifyModuleBoundaries 失败
# 移除后构建恢复通过
```

公共代码不依赖 Netty、Android Context、虚拟线程的更强保证来自编译边界：`commonMain` 只能使用 Kotlin 公共标准库与协程，JVM-only API 在公共源集不可见。P6 检查的是“依赖声明泄漏”，不是重复实现编译器已经保证的事。

### P7 CI 与文档同步

改动：

- `.github/workflows/ci.yml`：`ubuntu-latest`、Temurin JDK 25、Android SDK Platform 36 + Build Tools 36.0.0、`gradle/actions/setup-gradle`、执行 `./gradlew build`；缓存 Gradle 与 Android SDK。
- 修正 `docs/project-structure.md`：Android-KMP Library 插件、源集目录、无变体、`transport-android` 的实际创建时机。
- 修正 `docs/roadmap.md` M0 的模块落位口径（见第 7 节决策 2）。
- 更新 `README.md`：JDK 25、Android SDK 36、`app` 用法。

完成判据：PR 上 CI 通过，且 `./gradlew --version` 的 Daemon JVM 行为 25。

## 6. 验收对照

| roadmap M0 验收项 | 证据 |
| --- | --- |
| `./gradlew build` 通过 | 本地 + CI，含 P6 两个校验任务 |
| Desktop JAR 与 Android AAR 均能生成，字节码 25/21 | `build/libs/*-desktop.jar`、`build/outputs/aar/*.aar`；`verifyBytecode` 断言 69/65 |
| 公共模块不引用 Netty、Android Context、虚拟线程、JVM-only HTTP | 编译边界 + `verifyModuleBoundaries`；`transport-desktop` 尚未创建，Netty 无引入路径 |
| 新克隆工程可重复解析同一套协议与 Minestom 依赖 | 版本全部来自 `libs.versions.toml`；Schema 自带副本 + SHA-256；Minestom 版本固定 |

## 7. 决策记录

**决策 1：协议版本锁定 26.2（776）——已确认**

- 26.2（已选）：与 Minestom 最新发布一致，避免 M2 联调时被服务端版本倒逼升级；代价是 Schema 取自上游分支，需要固定提交并自留副本。
- 26.1：Schema 已在 `minecraft-data` 主干，来源更稳；但 Minestom 对应版本已落后一个功能版本，M2 之后仍要面对同样的升级。
- 26.3：Minestom 尚未发布支持版本，不进入候选。

**决策 2：M0 模块范围——已确认按精简口径执行**

- 精简（已选）：M0 只创建有真实内容的模块（`protocol-core`、`tools:protocol-generator`、`testing:minestom`、`app`）；`client-core`/`transport-desktop` 随 M1 首个实现创建，`transport-android` 随 M3 创建。
- 由此需要在 P7 修订 `roadmap.md` M0 的表述：模块按首个真实调用方落位，M0 不建空模块。
- 全量骨架：已否决。`client-core`、`transport-desktop`、`transport-android` 在 M0 内没有真实调用方，只能放占位实现，违反 fail-fast。

**决策 3：M0 是否提前验证 Netty——默认按推荐执行（不提前）**

- 不在 M0 验证：M0 只锁定版本，Netty 首次真实解析与环回读写测试放到 M1 第一个任务；风险低，失败时影响面小。
- 若后续希望提前暴露 Netty 与 JDK 25 的兼容问题，可在 P6 之后追加 `transport-desktop` 环回测试，不影响本计划其余任务。

## 8. 明确不做

- Reader/Writer、VarInt、字符串与 NBT 编解码（M1）。
- 分帧、压缩、Handshake/Status 数据包与状态机（M1）。
- 会话 Actor、事件流、KeepAlive（M2）。
- 在线认证、加密、重连策略（M5）。
- Android 传输与宿主（M3）；GUI/渲染（不纳入初版）。
- Gradle dependency locking / verification metadata：M0 的直接依赖已由版本目录与固定 POM 决定；完整传递锁定与校验放在发布前（M5），避免 AGP 升级时产生大量噪声。

## 9. 风险与缓解

| 风险 | 证据/影响 | 缓解 |
| --- | --- | --- |
| AGP 升级破坏 KMP 接入 | AGP 9 已强制新插件；9.5/9.6 仍在演进 | 锁 9.4.1；升级单独提交，必须先过 P6 与 CI |
| Schema 分支被重写或删除 | 26.2 数据仅存在于 `pc_26_2` | M0 即复制 `protocol.json` 与 `version.json` 入仓，记录提交与 SHA-256 |
| Minestom 必须跑 Java 25 | jar 内类文件主版本 69 | 构建与运行统一 JDK 25；CI 显式安装 Temurin 25 |
| CI 缺 Android SDK 36 | AGP 9.4 需要 Build Tools 36.0.0 | workflow 中显式安装 Platform 36 / Build Tools 36.0.0 并接受许可 |
| 本地 `java` 是 21，误以为在用 25 | `PATH` 上是 21，JDK 25 在 `~/.jdks` | 把 `./gradlew --version` 的 Daemon JVM 行写入验收；CI 不依赖本机环境 |
| 配置缓存与自定义校验任务冲突 | P6 任务若在执行期访问 `Project` 会失效 | 任务只用声明的输入/输出与 `FileCollection`；用 `--configuration-cache` 跑一遍 |
| 生成器变成第二个事实来源 | 版本常量若同时手写会漂移 | 常量只由生成器产出；仓库不提交生成文件 |

## 10. 完成定义

M0 完成时，一个新克隆的仓库应满足：

1. 只装 JDK 25 与 Android SDK 36，执行 `./gradlew build` 即可通过。
2. 能指出唯一的版本事实来源（`libs.versions.toml` + `schema/26.2/SOURCE.md`），且两处一致。
3. 能拿出 AAR、Desktop JAR 与字节码 65/69 的自动证据。
4. CI 对上述内容做同样的检查，而不是只编译主源码。
