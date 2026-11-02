# 实施路线

状态：已确定  
日期：2026-11-02

## 1. 执行原则

- 每个阶段必须形成可运行、可验证的结果，不以“类已创建”作为完成标准。
- 先验证风险最高的协议状态链路和 Android 兼容性，再扩大功能面积。
- 一个阶段只引入完成该阶段所需的新抽象和依赖。
- 初版协议版本在 M0 锁定，记录 Minecraft 版本、协议号、Minestom 版本和 Schema 来源。
- 当前阶段未通过验收时，不通过提前建设后续模块绕过问题。

## 2. M0：工程基线与版本锁定

### 交付内容

- 按[项目结构](project-structure.md)建立模块，但只建当阶段有真实内容的：`protocol-core`、`tools:protocol-generator`、`testing:minestom`、`app`；`client-core`、`transport-desktop` 随 M1 首个实现创建，`transport-android` 随 M3 创建。
- 将公共模块配置为 Desktop JVM + Android KMP，Desktop 输出 Java 25，Android 输出 Java 21。
- 固定 Kotlin、AGP（含其 D8/R8 工具链）、Netty、协程和 Minestom 版本。
- 锁定首个 Minecraft 协议版本及 Schema 来源，记录到版本元数据，并在仓库内保留 Schema 副本。
- 建立 CI：Desktop 编译测试与 Android AAR 编译。
- 将 `app` 收敛为 CLI 示例；清理无真实用途的初始化样例。

### 验收标准

- `./gradlew build` 通过。
- Desktop JAR 与 Android AAR 均能生成，字节码目标分别为 25 和 21，并由构建任务自动断言。
- 公共模块不引用 Netty、Android Context、虚拟线程或 JVM-only HTTP API，边界由构建任务自动检查。
- 新克隆工程可依据锁定的版本信息重复解析同一套协议与 Minestom 依赖。

## 3. M1：协议基础与 Status 链路

### 交付内容

- 实现有严格边界检查的 Reader/Writer 和首版基础协议类型。
- 建立 Schema、生成器和必要的手写复杂类型例外。
- 实现连接状态、方向和数据包 ID 注册。
- 实现未压缩/压缩分帧，以及 Handshake、Status 数据包。
- Desktop Transport 可以连接 Minestom 并完成 Status Ping。
- 建立畸形输入、截断输入、超长 VarInt 和长度限制测试。

### 验收标准

- 黄金样本与真实 Minestom Status 请求/响应一致。
- 同一输入重复编码结果确定，解码不会接受非法长度或越界数据。
- Status 流程正常关闭，连接和缓冲区无泄漏。
- 生成器重复执行不产生无意义差异。

## 4. M2：Minestom 离线登录 MVP

### 交付内容

- 实现 Login 与 Configuration 状态机。
- 处理 Login Success、Set Compression、Login Acknowledged。
- 处理首版所需的 Known Packs、Registry Data、Finish Configuration 等配置包。
- 进入 Play 后处理 Login、KeepAlive、Synchronize Player Position 和区块批次确认。
- 支持主动断开、异常断开和带上下文的结构化错误。
- CLI 可连接本地 Minestom 离线服务器并输出关键会话事件。

### 验收标准

- 完整通过 Handshake → Login → Configuration → Play，不只停留在 TCP 连接成功。
- 压缩切换前后的包均能正确解析，非法压缩包会明确失败。
- 核心自动回应 KeepAlive 和传送确认，不需要 CLI 手工补救。
- 服务端主动断开、网络取消和客户端关闭均能释放资源。
- 同一进程建立两个会话时，玩家、注册表和世界状态不串用。

## 5. M3：Android 早期连通性验证

### 交付内容

- 实现 Android Socket Transport，与 Desktop 共用同一协议和会话核心。
- 增加最小 Android 测试宿主，只承担生命周期和连接展示。
- 验证压缩、DEFLATE 和所需密码原语在 Android 工具链与设备环境中的行为。
- 检查后台取消、宿主销毁和网络异常时的 Session 关闭。
- 将 Android 构建与连接测试纳入持续质量门控。

### 验收标准

- Android 模拟器或真机可对同一 Minestom 服务完成 M2 的离线登录链路。
- Android 与 Desktop 对相同黄金样本产生一致结果。
- Android 产物保持 Java 21 目标，未意外引入 Java 25 JAR 或 Netty 公共依赖。
- 取消连接和销毁宿主后没有继续读写或持有会话资源。

此阶段是平台风险门控。若 Android 未通过，不继续扩展大型世界模型。

## 6. M4：可使用的客户端库

### 交付内容

- 稳定初版 `MinecraftClient`、连接状态、事件和错误 API。
- 支持基础位置/移动、聊天、插件消息和诊断日志。
- 建立会话级注册表、维度和玩家状态。
- 实现最小只读区块、实体增删与位置同步、基础背包状态。
- 提供有界队列、写背压、超时和清晰的关闭语义。
- 编写 Desktop 与 Android 接入示例，明确 API 尚处实验阶段的兼容政策。

### 验收标准

- 调用者无需接触数据包 Wrapper、Netty 或 Android Socket 即可完成基础机器人。
- 世界状态只来自所属 Session，断线后不会泄漏到下一次连接。
- 慢事件消费者不会破坏 KeepAlive 等协议义务，也不会导致无界内存增长。
- Minestom 集成测试覆盖已公开的稳定行为，Desktop/Android 共用核心测试。

## 7. M5：生产化能力

### 交付内容

- 在线模式加密、Microsoft 认证和敏感令牌的安全存储适配。
- 明确的重连策略；自动重连必须可配置，不能默认无限重试。
- 畸形包、压缩边界和编解码模糊测试。
- 多客户端压力测试、内存/分配测量和必要的局部优化。
- 完善诊断信息、发布产物和版本迁移说明。

### 验收标准

- 在线模式不会将令牌或密钥写入普通日志。
- 加密、压缩和状态切换顺序经过真实服务端验证。
- 背压与重连不会造成无界队列、重复 Session 或资源泄漏。
- 性能优化有测量依据，且行为测试保持通过。

## 8. 后续扩展，不纳入初版承诺

- 第二协议版本与版本适配目录。
- 完整世界、物理、寻路和高级机器人能力。
- GUI、渲染、资产管理和输入系统。
- 更多平台或传输实现。
- 稳定的二进制兼容承诺与插件生态。

扩展前必须重新评估公共 API 和模块边界，但不以未来可能性为由提前增加当前复杂度。
