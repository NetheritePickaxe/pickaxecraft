# M3：Android 运行时验证（已完成）

状态：**已完成**（2026-11-15，ART 真机）；iOS 仍未验证，不在本文范围。
日期：2026-11-05（计划）/ 2026-11-15（结果，见 §6）
范围：只做运行时验证，不新增客户端能力。构建链结论见 [Android 反糖探针](../android-dex-probe.md)。

## 1. 为什么单独写这份文档

「Android 支持」在这份仓库里有两个完全不同的含义：

- **构建链上可消费**：Java 21 字节码能被 `d8` 处理、依赖不冲突、`minSdk` 有明确下限。这条**已验证**。
- **在设备上跑得对**：Netty 在 ART 上能建连接、协程调度正常、关闭会话后线程与内存回收干净。这条**未验证**。

两者混为一谈，就会得到一句“理论上支持 Android”——既不能用于发布决策，也无法在出问题时定位。
所以这里把未验证的部分逐条列成可判据的断言。

## 2. 阻塞事实（2026-11-05，已解除）

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

## 6. 结果（2026-11-15）

设备：`PLC110` / Android 16（`sdk=36 release=16`）/ arm64-v8a；服务端：`:testing:minestom`
（Minestom 2026.10.07-26.2，协议 776），经 `adb reverse tcp:25565` 回环连接。
宿主与脚本见 `tools/m3-android/`，原始报告见
[`evidence/2026-11-15-all.txt`](../../tools/m3-android/evidence/2026-11-15-all.txt)。

### 6.1 逐条判据

| 判据 | 结果 |
| --- | --- |
| 最小宿主跑 `status` | ✅ `version=26.2 protocol=776 latency=24–56ms motd=Minestom Server` |
| 离线登录进 `PLAY` | ✅ `state=PLAY x=0.5 y=40.0 z=0.5 chunks=9–17 brand=Minestom` |
| 区块与光照解码 | ✅ 上述 `chunks` 即 `Session.loadedChunks`，来自真实 `chunk_data`/光照包 |
| 每会话新增线程 | ✅ `added=1 names=[pickaxe-mcpl-3-1]`（NIO EventLoopGroup 每连接一组） |
| 关闭后回收 | ✅ `join.closed state=CLOSED chunks=0`、`threads.after client=0 leaked=[]` |
| 20 轮开关 | ✅ 20/20 进 PLAY 且 `chunks` 9–17，`chunksAfterClose=0`，`leaked=[]`，`threads` 25–26，`heap` 6–22 MB 锯齿、结束时 6 MB（ART 在 `System.gc()` 后回落） |
| MethodHandle | ✅ `probe.methodhandle ok=true result=5`（签名多态 `invokeExact`） |
| Netty 探测 | `isAndroid=true hasUnsafe=false directPreferred=false`；`directBufferAddress` 抛 `No address access method`，但 NIO 建连/登录/区块解码全部正常 |
| `HexFormat` | ✅ 设备 API 36 有，`roundtrip=00ff7f`；结论见 6.3 |

### 6.2 宿主要自带的两处绕过

这两处只发生在证据宿主里，但任何 Android 宿主都会遇到：

1. **JNDI 占位**：`NettyHelper.<clinit>` 无条件加载 `com.sun.jndi.dns.DnsContextFactory` 并建
   `InitialDirContext`，而 `android.jar` 没有 `javax.naming`。宿主补齐最小占位后 SRV 查询
   以 `NamingException` 失败、回落到普通 DNS——与 Android 的真实能力一致。上游修法是
   延迟初始化 JNDI。
2. **jar 资源与 `META-INF/services` 必须进 APK**：`yggdrasil_session_pubkey.der`、
   `networkCodec.nbt`、`networkTags.nbt`、cloudburst `VectorProvider`、adventure Provider。
   缺前者 `MinecraftProtocol` 初始化失败，缺后者 `Vectors` 抛
   “Could not initialize vector provider” 并断线。

### 6.3 `HexFormat` 决策：有效 `minSdk` 34

`java.util.HexFormat` 是 API 34。使用它的不只是在线认证路径：

- `adventure-api` 的 `TextColorImpl` 在**静态字段**里初始化 `HexFormat.of()`，类一加载就需要；
- `mcprotocollib` 的 `UUIDUtils` 直接用 `HexFormat.fromHexDigitsToLong`。

因此 `minSdk 26` 只是字节码与 `MethodHandle` 的下限，**Android 宿主的下限实际是 34**。
回到 26 需要替换这两处依赖或引入反糖方案，属于独立改动，不在 M3 范围内；
0.1.0 的发布声明不受影响（Android 一直是“候选”）。

### 6.4 这次没有覆盖的

- 渲染：本仓库不含渲染代码，GLES 路径由外部图形客户端验证；
- 在线认证与账户流程：M5，且受 6.3 的 `minSdk` 约束；
- iOS：需 macOS/Xcode + RoboVM，不能由本次结果推断；
- 直接缓冲的零拷贝优化：ART 上 `directBufferAddress` 不可用，相关优化不能假设存在。

### 6.5 复现

```powershell
# 终端 A
.\gradlew.bat :testing:minestom:run --args="--port 25565"
# 终端 B
powershell -File tools\m3-android\build.ps1
powershell -File tools\m3-android\run.ps1
```
