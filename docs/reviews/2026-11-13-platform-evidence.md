# 平台证据记录（2026-11-13）

状态：证据记录（不改变代码，不改变发布门槛）
日期：2026-11-13
范围：Phase 4 的 Android / iOS / 移动 JDK 路径

**结论：本次没有新增任何移动运行时证据。Android 与 iOS 继续是“候选，未验证”，
0.1.0 的平台声明与发布门槛不变。**

## 1. 环境事实

| 项 | 实际情况 | 后果 |
| --- | --- | --- |
| Android 设备 | 无 `adb` 设备；本机无虚拟化 | 无法安装最小宿主并观察 ART 运行时 |
| Android 模拟器 | 无（依赖虚拟化） | 同上 |
| macOS / Xcode | 无 | 无法构建 RoboVM 产物 |
| Termux / PojavLauncher 类环境 | 无 | 无法做“自带 JDK”的最低保障验证 |

这些是环境阻塞，不是“未尝试”。没有设备就没有 ART 的类加载、NIO 与线程回收证据，
把它写成“通过”只会把风险推给下游。

## 2. 已有的、可复用的证据

- **构建链**：`docs/android-dex-probe.md` 记录 D8 可消费全部依赖并产出 dex，
  `minSdk` 下限 26；重复类`org.cloudburstmc.math:api` 必须剔除；`HexFormat` 属于在线认证前置问题。
- **字节码契约**：共享产物是 Java 21 字节码；发布冒烟的 Gradle 消费者用 Java 21 toolchain
  编译并实际运行 `status` 查询（`tools/release-smoke`），证明标准 JVM 21 可消费。
  这不能外推到 ART：ART 的类加载与 JVM 有实质差异。
- **E2E**：联调链路全部在标准 JVM 上验证，覆盖会话、区块、实体、交互与关闭；
  这些是行为证据，不是移动平台证据。

## 3. Netty 4.2.20 在 ART 上的风险清单（未验证）

升级到 4.2.20.Final 后，移动端需要重新确认这些路径，而不是沿用旧结论：

- `PlatformDependent` 的可用性探测（`Unsafe`、直接缓冲、`sun.misc` 反射）在 ART 上的行为；
- NIO transport 的 `Selector` 实现与线程模型；
- Native transport 不可用时的回退路径（不能假设 epoll/kqueue 存在）；
- 类加载：`netty-transport-native-unix-common` 等模块在 dex 化后的初始化顺序；
- 资源回收：每个会话一个 `EventLoopGroup`，移动端线程数增长更敏感。

## 4. 解除条件

M3 门控（`docs/plans/m3-android-runtime.md`）列出的逐条判据全部满足后，才把平台表改成“已验证”：

1. 最小宿主能完成 `status`；
2. 离线登录并进入世界；
3. 收到并解码区块与光照；
4. 关闭会话后网络线程与直接缓冲被回收；
5. 有可复现的命令与日志作为证据。

在满足之前，README、POM 描述与发布说明继续写“候选 / 未验证”，不写“支持 Android/iOS”。

## 5. 复现命令（环境具备后）

```bash
# Android：构建最小宿主并安装到设备
adb devices
./gradlew :app:assembleDebug   # 或在外部宿主工程中消费 client-core
adb install -r app-debug.apk
adb logcat | grep -i pickaxe

# 自带 JDK 路径（Termux 类环境）
./gradlew :app:installDist
JAVA_HOME=<jdk25> app/build/install/pickaxecraft/bin/pickaxecraft status 127.0.0.1 25565
```

## 6. 后续：2026-11-15 真机证据（本条记录已被部分推翻）

上文的“环境阻塞”已经解除，Android 的 ART 运行时判据全部拿到证据；iOS 仍然没有环境。
本节只做事实更新，2026-11-13 的记录保留原样，便于对照。

- 设备：`PLC110` / Android 16（API 36）/ arm64-v8a；宿主与脚本见 `tools/m3-android/`。
- 结果：`status` 24–56 ms；离线登录进 `PLAY`（`x=0.5 y=40.0 z=0.5`，9–17 个区块）；
  每会话新增 1 个 `pickaxe-mcpl` 线程，关闭后线程归零、`loadedChunks == 0`；
  20 轮开关无增长（`threads` 25–26，`heap` 6–22 MB 锯齿、结束 6 MB）。
- 原始报告：[`tools/m3-android/evidence/2026-11-15-all.txt`](../../tools/m3-android/evidence/2026-11-15-all.txt)；
  逐条判据与决策：[`docs/plans/m3-android-runtime.md`](../plans/m3-android-runtime.md) §6。
- 新增的两条硬约束（都已写入 `docs/architecture.md`）：
  1. Android 宿主必须自带 `javax.naming` 最小占位与 `com.sun.jndi.dns.DnsContextFactory`，
     否则 mcprotocollib 的 `NettyHelper.<clinit>` 直接抛 `ExceptionInInitializerError`；
  2. APK 必须打包 jar 的根资源与 `META-INF/services`（`yggdrasil_session_pubkey.der`、
     `networkCodec.nbt`、`networkTags.nbt`、cloudburst `VectorProvider` 等）。
- `HexFormat` 结论：有效 `minSdk` 是 **34**，不是 26。`adventure-api` 的 `TextColorImpl`
  在静态字段里初始化 `HexFormat`，`mcprotocollib` 的 `UUIDUtils` 也直接使用它。
- 仍然未验证：渲染、在线认证、iOS。
