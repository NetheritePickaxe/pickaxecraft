# M3：Android 运行时验证（门控中）

状态：**阻塞**——构建链已验证，运行时未验证。
日期：2026-11-05
范围：只做运行时验证，不新增客户端能力。构建链结论见 [Android 反糖探针](../android-dex-probe.md)。

## 1. 为什么单独写这份文档

「Android 支持」在这份仓库里有两个完全不同的含义：

- **构建链上可消费**：Java 21 字节码能被 `d8` 处理、依赖不冲突、`minSdk` 有明确下限。这条**已验证**。
- **在设备上跑得对**：Netty 在 ART 上能建连接、协程调度正常、关闭会话后线程与内存回收干净。这条**未验证**。

两者混为一谈，就会得到一句“理论上支持 Android”——既不能用于发布决策，也无法在出问题时定位。
所以这里把未验证的部分逐条列成可判据的断言。

## 2. 阻塞事实（2026-11-05）

| 项 | 事实 |
| --- | --- |
| 虚拟化 | `VirtualizationFirmwareEnabled=False`，WHPX/HAXM 不可用，模拟器起不来 |
| 设备 | 无已连接的 `adb` 设备 |
| 结论 | 任何“Android 运行时已通过”的说法目前都没有证据支撑 |

这条阻塞是**环境**造成的，不是代码问题：探针已经证明构建链是通的，
差的是一台能跑 ART 的机器或设备。

## 3. 解除后要跑什么（逐条判据）

### 3.1 ART 路径（原生 Android）

1. **最小宿主**：一个 Activity，把 `client-core` 的 `MinecraftClient` 包在一个 ViewModel 里；
   Android 入口不进共享模块（`app` 是 Java 25 组合根，不参与 Android 打包）。
2. **能进世界**：连局域网内的 Minestom，要求 `SessionState` 到 `PLAY`、`Session.world` 有位置、
   `loadedChunks > 0`。这条同时覆盖 Netty 建连、握手、配置阶段与区块解码。
3. **线程**：连接前后对比 `Thread.getAllStackTraces()`；预期多出客户端自持的
   `EventLoopGroup` 线程（每个连接一组，见 `docs/architecture.md` §5.3 的适配层约束）。
4. **回收**：`session.close()` 之后，上述线程消失、`loadedChunks == 0`；
   连续开关 20 次不出现线程数单调增长。
5. **MethodHandle**：`minSdk 26` 的实际表现——`netty-common` 的 `PlatformDependent`
   在 ART 上是否走到 `invokeExact` 分支且不抛 `UnsupportedOperationException`。
6. **打包排除**：`org.cloudburstmc.math:api` 与 `:immutable` 只能留一个（57 个重复类，
   见探针 §4.4）；`java.util.HexFormat` 仍需 API 34，**M5 在线认证之前必须解决**
   （换实现或提高 `minSdk`，二者都要写决策）。

### 3.2 自带 OpenJDK 路径（PojavLauncher / FCL 类宿主）

用同一份 `:app` 产物（Java 25 组合根 + `installDist`）验证：预期直接可用，
不需要额外适配。这条路径的价值是**先用桌面同构的方式证明协议层与客户端核心在移动设备上成立**，
把问题范围从“Android 平台”收窄到“ART + Netty”。

### 3.3 通过判据（DoD）

以下四条同时成立才算 M3 完成，缺一条就仍然写「未验证」：

1. ART 路径完成第 3.1 的 1–4；
2. 线程与内存没有随开关会话单调增长；
3. `HexFormat` 问题有明确结论（替代实现或 `minSdk 34` 的决策文档）；
4. 结果写入 `docs/architecture.md` 的平台支持矩阵，而不是只留在 issue 或聊天里。

## 4. 明确不做的事

- **不在 Android 内嵌 Minestom**：那是桌面单人模式的可选项，移动端没有这个需求。
- **不做 Web/HTML5**：GWT 不支持 Kotlin，Netty 也不适用于浏览器。
- **不为通过验证而改核心 API**：若验证发现必须改（例如必须暴露连接回调），先写决策文档，
  再动代码——这些改动会同时影响桌面与未来的 iOS 宿主。

## 5. 现在能做的事

在拿到设备之前，M3 唯一能推进的是**保持构建链不腐化**：

- `verifyBytecode` 断言共享产物是 major 65；
- `tools/android-probe/dex.sh` 在需要时重跑，确认依赖组合仍可 dex；
- 新增依赖时检查是否引入新的 `java.*` 高版本 API（探针的 `min-api 26` 那一步会暴露）。
