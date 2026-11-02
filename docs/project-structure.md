# 项目结构

状态：M0 已落地，模块随首个真实调用方逐个创建  
日期：2026-11-02

## 1. 结构原则

- 初版只创建具有明确责任和真实调用方的模块，不为未来概念建立空模块。
- 客户端公共 API 与协议核心保持多平台；具体 I/O 位于适配模块。
- 应用入口只承担组合根、配置和命令，不承载协议或世界逻辑。
- 测试服务和代码生成器不参与运行时发布。
- 当前已存在 `:protocol-core`、`:tools:protocol-generator`、`:testing:minestom`、`:app` 四个模块；`utils` 初始化样例已随 M0 删除。`client-core` 随 M1 首个真实实现创建，`transport-desktop` 随 M1 的 TCP 读写创建，`transport-android` 随 M3 创建。

## 2. 目标目录树

```text
pickaxecraft/
├── client-core/                    # KMP：公共客户端 API 与会话语义
│   └── src/
│       ├── commonMain/kotlin/
│       │   └── .../
│       │       ├── api/            # MinecraftClient、ClientOptions、事件
│       │       ├── session/        # 会话 Actor、连接状态、协议义务
│       │       ├── transport/      # Transport SPI，仅抽象原始字节流
│       │       ├── registry/       # 会话级注册表
│       │       └── world/          # 玩家、维度、区块、实体等状态
│       ├── commonTest/kotlin/
│       ├── desktopMain/kotlin/     # 仅少量内部平台能力
│       └── androidMain/kotlin/     # 仅少量内部平台能力
│
├── protocol-core/                  # KMP：协议类型、数据包和编解码
│   ├── schema/
│   │   └── <locked-version>/       # 单一协议版本的 Schema 与元数据
│   └── src/
│       ├── commonMain/kotlin/
│       │   └── .../
│       │       ├── io/             # Reader/Writer、边界检查
│       │       ├── types/          # NBT、Slot、Position 等
│       │       ├── packet/         # 数据包模型与注册
│       │       ├── codec/          # 状态/方向感知编解码
│       │       └── pipeline/       # 分帧、压缩和加密协议状态
│       ├── commonTest/kotlin/
│       │   └── golden/             # 有意义的二进制黄金样本
│       ├── desktopMain/kotlin/     # 平台压缩/密码原语 actual
│       └── androidMain/kotlin/     # 平台压缩/密码原语 actual
│
├── transport-desktop/              # JVM 25：Netty NIO Transport 实现
│   └── src/
│       ├── main/kotlin/
│       └── test/kotlin/
│
├── transport-android/              # Android Java 21：Socket + 协程实现
│   └── src/
│       ├── main/kotlin/
│       └── test/kotlin/
│
├── app/                            # JVM 25：Desktop CLI 示例和组合根
│   └── src/
│       ├── main/kotlin/
│       └── test/kotlin/
│
├── tools/
│   └── protocol-generator/         # JVM 25：Schema 读取与 Kotlin 代码生成
│       └── src/
│           ├── main/kotlin/
│           └── test/kotlin/
│
├── testing/
│   └── minestom/                   # JVM 25：可重复启动的 Minestom 联调服务
│       └── src/
│           ├── main/kotlin/
│           └── test/kotlin/
│
├── buildSrc/                       # 只放构建约定，不放业务代码或生成器
├── docs/                           # 架构、结构和路线文档
├── gradle/                         # Wrapper、Version Catalog、Daemon JVM 配置
├── settings.gradle.kts
└── gradle.properties
```

路径中的 `...` 表示实现前统一确定的根包名，不代表需要再创建一层名为 `...` 的目录。源码包按职责划分，不按“每层一个接口”机械拆分。

## 3. Gradle 模块与发布属性

| Gradle 路径 | 类型 | 字节码目标 | 是否发布 | 状态 |
| --- | --- | --- | --- | --- |
| `:protocol-core` | Kotlin Multiplatform | Desktop 25 / Android 21 | 是 | 已创建 |
| `:client-core` | Kotlin Multiplatform | Desktop 25 / Android 21 | 是 | M1 创建 |
| `:transport-desktop` | Kotlin JVM | Java 25 | 是 | M1 创建 |
| `:transport-android` | Android-KMP Library | Java 21 | 是 | M3 创建 |
| `:app` | Kotlin JVM 应用 | Java 25 | 否 | 已创建 |
| `:tools:protocol-generator` | Kotlin JVM 工具 | Java 25 | 否 | 已创建 |
| `:testing:minestom` | Kotlin JVM 测试工具 | Java 25 | 否 | 已创建 |

### 3.1 Android 目标的接入方式（AGP 9 硬约束）

AGP 9 起，`org.jetbrains.kotlin.multiplatform` 不能与 `com.android.library` 出现在同一个 Gradle 子项目，必须改用 Android-KMP Library 插件：

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_25) } }
    android {                       // AGP 9.4 起由 androidLibrary 改名而来
        namespace = "org.pickaxe.protocol"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        withHostTestBuilder {}.configure {}
    }
}
```

由此产生四条结构约束：

1. 源集目录只有 `src/commonMain`、`src/commonTest`、`src/desktopMain`、`src/androidMain`、`src/androidHostTest`、`src/androidDeviceTest`；不存在 `src/main`。
2. 该插件是单变体架构，不支持 buildTypes 与 productFlavors。库模块不受影响；未来的 Android 宿主应用必须单独建模块。
3. Android 字节码目标由 `android.compilerOptions.jvmTarget` 决定，不使用 `android { compileOptions }`。
4. `build/outputs/aar/<module>.aar` 是 Android 产物，`build/libs/<module>-desktop.jar` 是 Desktop 产物；字节码分别为主版本 65 与 69，由 `verifyDesktopBytecode` / `verifyAndroidBytecode` 自动断言。

Android target 只生成 Android 兼容产物；不能将 Desktop 的 Java 25 JAR 当作 Android 输入。

## 4. 依赖方向

```text
app ───────────────► client-core ◄──────────── transport-android
  │                       ▲
  └────► transport-desktop ┘

client-core ──────► protocol-core
transport-desktop ► client-core
transport-android ► client-core

tools:protocol-generator ──► protocol-core/schema（构建期输入）
testing:minestom ──────────► protocol-core + Minestom
```

`testing:minestom` 当前只依赖 `protocol-core`（用于启动时交叉校验协议号）；M2 联调开始后会再依赖 `client-core` 与 `transport-desktop`。

具体规则：

1. `protocol-core` 不依赖 `client-core`、任何 Transport、Minestom 或应用模块。
2. `client-core` 只依赖 `protocol-core` 和多平台库，不依赖具体传输。
3. Transport 适配实现 `client-core` 中的端口，不反向被核心引用。
4. `app` 是 Desktop 组合根，同时依赖 `client-core` 与 `transport-desktop`。
5. 未来 Android 示例宿主同时依赖 `client-core` 与 `transport-android`，Android 生命周期代码不进入核心。
6. `testing:minestom` 可以依赖公开 API 和 Desktop 适配，但生产模块不能依赖测试服务。
7. 生成器只读取 Schema 并输出源码；协议运行时不依赖生成器。

## 5. 公共 API 归属

`client-core` 是初版唯一公共客户端入口，包含：

- `MinecraftClient`：连接、关闭、会话状态和基础操作。
- `ClientOptions`：地址之外的超时、队列、诊断等明确配置。
- `ClientEvent`：连接状态、聊天、断开和可观察世界事件。
- `Session` 的只读视图：玩家、注册表和世界状态。
- `Transport`/`TransportFactory`：供两个真实平台适配实现的 SPI。

调用方通过组合根选择平台实现：

```kotlin
val client = MinecraftClient(
    transport = desktopTransportFactory(),
    options = ClientOptions()
)

client.connect("127.0.0.1", 25565)
client.close()
```

示例只说明依赖注入方向，不作为已经冻结的函数签名。公共 API 在 M4 稳定前允许调整；开始承诺兼容后再单独进行 API/二进制兼容管理。

`protocol-core` 的模型主要服务内部会话实现。除非出现真实的高级扩展需求，不把所有数据包 Wrapper 都提升为客户端公共 API；诊断需要可通过受控的原始包事件提供。

## 6. 暂不创建的模块

| 模块 | 暂不创建的原因 | 何时再评估 |
| --- | --- | --- |
| `client-api` / `client-impl` | 当前只有一个实现，提前拆分只增加跳转 | 需要独立稳定 API 或有第二个实现时 |
| `protocol-api` / 多协议适配 | 已决定自研单版本核心 | 第二个协议实现真实出现时 |
| `auth-microsoft` | 离线 Minestom 链路优先 | M5 在线认证开始时 |
| `world` | 初版世界状态仍由会话统一拥有 | 世界模型独立复用需求出现时 |
| `rendering` / `gui` | 与 Headless 初版无关 | 明确图形客户端需求出现后 |
| `android-app` | 先验证 Android Library 本身 | M3 增加最小连接宿主时 |

## 7. 构建约定

三个预编译约定插件位于 `buildSrc/src/main/kotlin`：

| 插件 | 作用 | 使用者 |
| --- | --- | --- |
| `pickaxe.kmp.library` | KMP：Desktop 25 + Android 21、`explicitApi()`、协程、JUnit 5、字节码与边界校验 | `:protocol-core` |
| `pickaxe.jvm.library` | Kotlin/JVM 25、`explicitApi()`、JUnit 5、字节码与边界校验 | `:tools:protocol-generator`、经 CLI 传递到其余模块 |
| `pickaxe.jvm.cli` | 在上者基础上应用 `application`，并强制 UTF-8 输出 | `:app`、`:testing:minestom` |

约定插件不写死模块信息：`namespace`、`mainClass`、禁止依赖项都由模块自己声明。`buildSrc` 预编译脚本没有 `libs` 类型安全访问器，版本统一通过 `VersionCatalogsExtension` 读取 `gradle/libs.versions.toml`。

`check` 里包含两类自动门控：

- `verifyBytecode` / `verifyDesktopBytecode` / `verifyAndroidBytecode`：断言 class 文件主版本为 69 / 65。
- `verifyModuleBoundaries`：扫描编译类路径，公共模块不得出现 `io/netty/` 与 `net/minestom/`；`testing:minestom` 作为 Minestom 宿主显式放开。

所有目标版本、AGP、Kotlin、Minestom 和协议号的版本来源必须唯一：依赖版本在 `gradle/libs.versions.toml`，协议元数据在 `protocol-core/schema/26.2/`。避免不同模块各自声明导致不可复现的联调结果。
