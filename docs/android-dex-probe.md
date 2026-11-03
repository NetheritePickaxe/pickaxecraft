# Android 反糖探针（M1 提前执行）

状态：构建链已验证；运行时**未验证**
日期：2026-11-03
范围：M1 计划 P4；结论只覆盖“能不能产出 dex”，不覆盖“在 ART 上跑得对不对”。

## 1. 做了什么、没做什么

**做了**：把「Android 会打包的那套构件」按 `min-api 24 / 26 / 34` 各做一次真实 dex 编译
（`d8`，build-tools 36.0.0）。脚本可复现：`tools/android-probe/dex.sh`。

**没做**：真机或模拟器运行。执行环境没有硬件虚拟化（`VirtualizationFirmwareEnabled=False`），
也没有已连接设备，模拟器无法启动。因此下面所有结论都只关于**构建链**：

- Netty 在 ART 上的实际类加载与 NIO 行为：未验证
- 运行时线程、`EventLoopGroup` 回收：未验证
- no-Unsafe 降级是否真的生效：未验证

按计划约定，Android 在支持矩阵里仍是「未验证」，不得因为本文降级为「理论上支持」。

## 2. 环境

| 项 | 值 |
| --- | --- |
| 系统 | Windows（虚拟机，无嵌套虚拟化） |
| JDK | Temurin 25 |
| Android SDK | build-tools 36.0.0，platforms android-36 |
| d8 / R8 | 8.10.9-dev（build-tools 36.0.0 自带） |
| 输入 | `:app:installDist` 的 39 个运行时 jar（排除 `app` 自身与重复的 `api-2.0`） |

输入集合就是 `app/build/install/app/lib/`：`protocol-core`、`client-core`、MCProtocolLib、
Netty 4.2.1、Adventure、kotlinx-coroutines、kotlin-stdlib 等，与桌面实际运行时一致。

## 3. 结果

| min-api | d8 退出码 | dex 文件 | 产物大小 | `MethodHandle` 相关警告 |
| --- | --- | --- | --- | --- |
| 24 | 0 | 2（classes.dex / classes2.dex） | 12 MB | 15 |
| 26 | 0 | 2 | 12 MB | 0 |
| 34 | 0 | 2 | 12 MB | 0 |

## 4. 结论

### 4.1 共享产物可以 dex

`protocol-core`、`client-core`（major 65）与全部第三方依赖在 d8 下**没有错误**，
产出两个 dex。Java 21 字节码 + 现有依赖组合在构建链上是通的。

### 4.2 minSdk 下限由 MethodHandle 决定：26

`min-api 24` 的 15 条警告全部是
`MethodHandle.invoke / invokeExact are only supported starting with Android O`，
来源：

- `netty-common`：14 条（`PlatformDependent`、`PlatformDependent0`、`CleanerJava6/9`）
- MinecraftAuth 的 `httpclient`：1 条（`SingleProxySelector` 构造器）

`min-api 26`（Android 8.0）起为 0。因此 **M1 阶段可写下的下限是 26**，
`minSdk 24` 的假设不成立。

### 4.3 `java.util.HexFormat` 没有被反糖，需要 API 34

dex 中仍保留 2 处 `Ljava/util/HexFormat;` 引用，唯一来源是 MCProtocolLib 的
`org.geysermc.mcprotocollib.auth.util.UUIDUtils`（把无横线 UUID 解析成两个 long）。
d8 不会为它做 backport。

- 离线登录路径不经过该方法，M1 已验证的链路不受影响。
- M5 做在线认证时必然要用 UUID 解析，届时有三种处理：上游改用自有实现、
  自带一个解析器、或把在线认证的 minSdk 下限定到 34。**现在是已知风险，不是未知风险。**

### 4.4 打包前必须剔除 `org.cloudburstmc.math:api`

`api-2.0.jar` 的 57 个类**全部**包含在 `immutable-2.0.jar` 中。两者同时进入 d8 会直接失败：

```text
Type org.cloudburstmc.math.matrix.MatrixNd$ImmutableIdentityMatrixN is defined multiple times
```

桌面 JVM 对重复类只是“先到先得”，Android 则是硬错误。因此 Android 侧打包必须
`exclude(group = "org.cloudburstmc.math", module = "api")`（本次探针就是这么做的）。
这条要求已经写进 `docs/architecture.md` 的 Android 约束。

### 4.5 Java 25 字节码进不了 d8

把 `app`（major 69）放进输入时，d8 直接报
`Unsupported class file major version 69` 并中止。这正是 `verifyBytecode` 门控存在的理由：
共享产物必须停在 Java 21。

### 4.6 附注：R8 与 Kotlin 元数据版本不匹配

d8 输出了约 2000 条 `malformed kotlin.Metadata`（`version 2.4.0, maximum supported 2.2.0`），
因为 build-tools 36 自带的 R8 早于 Kotlin 2.4。它不影响 dex 产出，但说明 Android 侧构建时
AGP 自带的 R8 版本需要与 Kotlin 版本匹配；真正引入 AGP 时要把这条当作构建约束验证。

## 5. 复现

```bash
ANDROID_HOME=/path/to/android-sdk tools/android-probe/dex.sh        # 24 26 34
ANDROID_HOME=/path/to/android-sdk tools/android-probe/dex.sh 26     # 只跑一个
```

输出与日志在 `build/android-probe/`（`dex-<api>/` 与 `d8-<api>.log`）。

## 6. 下一步

运行时验证仍归 M3 双路径 spike：

1. ART 路径：模拟器/真机上跑一次 Status 查询，重点看 Netty 类加载、`minSdk 26` 的实际表现、
   连接关闭后线程与内存回收。
2. 自带 OpenJDK 路径（PojavLauncher/FCL 类宿主）：预期直接通过，用同一条 `:app` 产物验证。
3. 两条路径的结果分别写入架构文档后，才更新平台支持矩阵。
