# 项目结构

状态：M0 已落地（协议后端为 MCProtocolLib），模块随首个真实调用方逐个创建
日期：2026-11-03

## 1. 结构原则

- 只创建具有明确责任和真实调用方的模块，不为未来概念建立空模块。
- 协议实现来自 MCProtocolLib；本项目只在 `protocol-core` 里做适配，别处不得直接依赖它。
- 应用入口只承担组合根、配置和命令，不承载协议或世界逻辑。
- 测试服务不参与运行时发布。
- 当前已存在 `:protocol-core`、`:testing:minestom`、`:app` 三个模块；`client-core` 随 M1 首个真实实现创建。

## 2. 目标目录树

```text
pickaxecraft/
├── protocol-core/                  # Java 21 字节码：MCProtocolLib 的唯一适配层
│   └── src/
│       ├── main/kotlin/
│       │   └── org/pickaxe/protocol/
│       │       ├── ProtocolVersion.kt
│       │       └── ...             # M1：连接、状态、事件转换
│       └── test/kotlin/
│           └── org/pickaxe/protocol/
│               └── ProtocolVersionTest.kt
│
├── client-core/                    # M1 创建：公共客户端 API、会话、事件、世界模型
│   └── src/
│       ├── main/kotlin/
│       └── test/kotlin/
│
├── app/                            # JVM 25：Desktop CLI 与服务端联调入口
│   └── src/
│       ├── main/kotlin/
│       └── test/kotlin/
│
├── testing/
│   └── minestom/                   # JVM 25：可重复启动的 Minestom 联调服务 + 握手用例
│       └── src/
│           ├── main/kotlin/
│           └── test/kotlin/
│
├── buildSrc/                       # 只放构建约定，不放业务代码
├── docs/                           # 架构、结构、路线与决策记录
├── gradle/                         # Wrapper、Version Catalog、Daemon JVM 配置
├── settings.gradle.kts
└── gradle.properties
```

源码包按职责划分，不按“每层一个接口”机械拆分。

## 3. Gradle 模块与产物属性

| Gradle 路径 | 类型 | 字节码目标 | 是否发布 | 状态 |
| --- | --- | --- | --- | --- |
| `:protocol-core` | Kotlin JVM | Java 21（major 65） | 是 | 已创建 |
| `:client-core` | Kotlin JVM | Java 21（major 65） | 是 | M1 创建 |
| `:app` | Kotlin JVM 应用 | Java 25（major 69） | 否 | 已创建 |
| `:testing:minestom` | Kotlin JVM 测试工具 | Java 25（major 69） | 否 | 已创建 |

共享产物一律用 Java 21 字节码：Java 25 字节码（major 69）现有 D8 无法消费，桌面端“跑得起来”不代表移动端可复用。

### 3.1 为什么不再有 transport 模块

协议库自带 Netty 传输，`ClientNetworkSession` 只接受 `ChannelFactory`，没有可替换的传输 SPI。因此不存在“桌面用 Netty、Android 用 Socket”的分叉：两端跑同一套 Netty 栈，Android 的可行性由 M3 spike 判定，而不是靠另写一个传输层绕过。

### 3.2 Android 的现状

Android 尚未验证。已知风险与待验证项见 `docs/architecture.md` 第 6 节；在 spike 通过前不引入 AGP、KMP 或 Android 专属模块。

## 4. 依赖方向

```text
app ──────────────► protocol-core ──► MCProtocolLib ──► Netty
 │                        ▲
 └──► client-core（M1）────┘   client-core 不直接依赖协议库

testing:minestom ──► protocol-core（版本对账）+ Minestom
```

具体规则：

1. `protocol-core` 不依赖 `client-core`、Minestom 或应用模块。
2. `client-core` 只依赖 `protocol-core` 与语言级库，不直接依赖 MCProtocolLib 或 Netty。
3. `protocol-core` 对 MCProtocolLib 一律用 `implementation`，不向编译类路径外泄。
4. `app` 是桌面组合根，依赖 `client-core`；当前阶段先依赖 `protocol-core`。
5. `testing:minestom` 可以依赖公开 API，但生产模块不能依赖测试服务。
6. `testing:minestom` 的握手用例在 `client-core` 就绪前临时直接使用协议库，之后改为走自有 API。

## 5. 公共 API 归属

`client-core` 是初版唯一公共客户端入口，包含：

- `MinecraftClient`：连接、关闭、会话状态和基础操作。
- `ClientOptions`：地址之外的超时、队列、诊断等明确配置。
- `ClientEvent`：连接状态、聊天、断开和可观察世界事件。
- `Session` 的只读视图：玩家、注册表和世界状态。

`protocol-core` 的模型服务内部会话实现，不是公共 API：调用方不应看到数据包对象。诊断需要的原始包信息通过受控事件提供。

## 6. 暂不创建的模块

| 模块 | 暂不创建的原因 | 何时再评估 |
| --- | --- | --- |
| `client-api` / `client-impl` | 当前只有一个实现，提前拆分只增加跳转 | 需要独立稳定 API 或有第二个实现时 |
| `transport-*` | 协议库内置 Netty，没有可替换的传输 SPI | 出现真实验证过的替代传输需求时 |
| `auth-microsoft` | 离线 Minestom 链路优先 | M5 在线认证开始时 |
| `world` | 初版世界状态仍由会话统一拥有 | 世界模型独立复用需求出现时 |
| `rendering` / `gui` | 与 Headless 初版无关 | 明确图形客户端需求出现后 |
| `android-app` | 尚未验证 Android 可行性 | M3 spike 通过后 |

## 7. 构建约定

三个预编译约定插件位于 `buildSrc/src/main/kotlin`：

| 插件 | 作用 | 使用者 |
| --- | --- | --- |
| `pickaxe.jvm.shared` | Kotlin/JVM、Java 21 字节码、`explicitApi()`、JUnit 5、字节码与边界校验 | `:protocol-core` |
| `pickaxe.jvm.library` | Kotlin/JVM、Java 25 字节码、`explicitApi()`、JUnit 5、字节码与边界校验 | 经 CLI 传递到其余模块 |
| `pickaxe.jvm.cli` | 在上者基础上应用 `application`，并强制 UTF-8 输出 | `:app`、`:testing:minestom` |

约定插件不写死模块信息：`mainClass`、禁止依赖项都由模块自己声明。`buildSrc` 预编译脚本没有 `libs` 类型安全访问器，版本统一通过 `VersionCatalogsExtension` 读取 `gradle/libs.versions.toml`。

`check` 里包含两类自动门控：

- `verifyBytecode`：断言 class 文件主版本为 65（shared）或 69（library）。
- `verifyModuleBoundaries`：扫描编译类路径；`pickaxe.jvm.library` 禁止 `io/netty/`、`org/geysermc/mcprotocollib/`、`net/minestom/`，`pickaxe.jvm.shared` 只禁止 `net/minestom/`（协议库允许出现在该模块内部），`testing:minestom` 作为 Minestom 宿主显式放开。

版本来源必须唯一：依赖版本在 `gradle/libs.versions.toml`，协议号在 `protocol-core` 的 `ProtocolVersion` 并与协议库 codec 常量对账。
