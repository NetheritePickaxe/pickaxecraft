# 项目结构

状态：目标结构已确定，尚未开始模块迁移  
日期：2026-11-02

## 1. 结构原则

- 初版只创建具有明确责任和真实调用方的模块，不为未来概念建立空模块。
- 客户端公共 API 与协议核心保持多平台；具体 I/O 位于适配模块。
- 应用入口只承担组合根、配置和命令，不承载协议或世界逻辑。
- 测试服务和代码生成器不参与运行时发布。
- 当前 `app`、`utils` 是 Gradle 初始化样例，不属于长期领域边界。M0 中将 `app` 改为 Desktop CLI；`utils` 若没有真实调用方则随骨架迁移移除，不保留平行工具库。

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

| Gradle 路径 | 类型 | 字节码目标 | 是否发布 |
| --- | --- | --- | --- |
| `:protocol-core` | Kotlin Multiplatform | Desktop 25 / Android 21 | 是 |
| `:client-core` | Kotlin Multiplatform | Desktop 25 / Android 21 | 是 |
| `:transport-desktop` | Kotlin JVM | Java 25 | 是 |
| `:transport-android` | Android Library | Java 21 | 是 |
| `:app` | Kotlin JVM 应用 | Java 25 | 否 |
| `:tools:protocol-generator` | Kotlin JVM 工具 | Java 25 | 否 |
| `:testing:minestom` | Kotlin JVM 测试工具 | Java 25 | 否 |

KMP 的 Desktop target 可命名为 `desktop`，从而形成 `desktopMain`/`desktopTest`。Android target 只生成 Android 兼容产物；不能将 Desktop 的 Java 25 JAR 当作 Android 输入。

## 4. 依赖方向

```text
app ───────────────► client-core ◄──────────── transport-android
  │                       ▲
  └────► transport-desktop ┘

client-core ──────► protocol-core
transport-desktop ► client-core
transport-android ► client-core

tools:protocol-generator ──► protocol-core/schema（构建期输入）
testing:minestom ──────────► client-core + transport-desktop + Minestom
```

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

## 7. 构建约定演进

M0 在保留现有 JDK 25 Daemon 配置的基础上增加两类约定：

- KMP 共享模块：Desktop JVM 25 + Android Java 21，统一协程、测试和显式 API 规则。
- Desktop JVM 模块：沿用 Java 25；Android Library 单独使用 Java 21，不套用 Desktop-only 约定。

所有目标版本、AGP、Kotlin、Minestom 和协议号的版本来源必须唯一，避免不同模块各自声明导致不可复现的联调结果。
