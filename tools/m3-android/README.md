# M3 Android 证据宿主

这是 M3 的**证据工具**，不是产品代码，也不参与发布。它用一个最小 Activity 持有
`MinecraftClient`，依次跑 probe → status → join → 20 轮开关，把每一步写进报告文件，
供 `adb pull` 取回。

构建与运行都不引入 AGP：`build.ps1` 直接调 SDK 工具（`aapt2` / `d8` / `zipalign` /
`apksigner`），`package_apk.py` 负责把 dex 与 jar 的资源塞进 APK。

## 构建

要求：`ANDROID_HOME` 带 `platforms;android-36` 与 `build-tools;36.0.0`，JDK 21+，
`python` 在 `PATH`，Gradle 缓存里有 Kotlin 编译器（跑过一次 `:app:compileKotlin`）。

```powershell
.\gradlew.bat :app:installDist
powershell -File tools\m3-android\build.ps1
# 已经 installDist 过时：
powershell -File tools\m3-android\build.ps1 -SkipInstallDist
```

产物：`build/m3-android/pickaxecraft-android-host.apk`（签名用
`build/m3-android-debug.keystore`，故意放在被清理的目录之外——换钥匙就得先卸载）。

## 运行

```powershell
# 终端 A：联调服务端
$env:JAVA_HOME='<jdk25>'; .\gradlew.bat :testing:minestom:run --args="--port 25565"

# 终端 B：装包 → adb reverse → am start → 等报告 → 拉回 evidence/
powershell -File tools\m3-android\run.ps1
```

`run.ps1` 默认跑 `action=all`（probe、status、join、20 轮），报告落到
`tools/m3-android/evidence/<日期>-all.txt`。

已归档的证据：`evidence/2026-11-15-all.txt`（PLC110 / Android 16 / API 36 / arm64-v8a）。

## 宿主要自带的两处绕过（不是客户端改动）

`client-core` 与 `protocol-core` 一行没动；下面两点只发生在证据宿主的 dex 里，
但**任何 Android 宿主都会遇到同样的两件事**，所以在 `docs/architecture.md` 的
Android 约束里同样记着：

1. `javax/naming` 下的最小占位类 + `com.sun.jndi.dns.DnsContextFactory`。
   mcprotocollib 的 `NettyHelper.<clinit>` 无条件 `Class.forName("com.sun.jndi.dns.DnsContextFactory")`
   并 `new InitialDirContext(env)`，而 Android 的 `android.jar` 里**没有 `javax.naming`**
   （API 36 实测）。没有这几个占位类，第一次连接就抛 `ExceptionInInitializerError`。
   占位实现让 SRV 查询以 `NamingException` 失败，`resolveSrv` 随即回落到普通 DNS 解析——
   与 Android 的真实能力一致：不做 SRV，主机名/字面 IP 照常解析。
   上游的正确修法是让 `NettyHelper` 延迟初始化 JNDI。
2. APK 里必须带 jar 的根资源与 `META-INF/services`：
   `yggdrasil_session_pubkey.der`、`networkCodec.nbt`、`networkTags.nbt`，
   以及 cloudburst 的 `VectorProvider`、adventure 的序列化 Provider。
   缺前者，`MinecraftProtocol` 初始化失败；缺后者，`Vectors` 抛
   “Could not initialize vector provider”，区块解码直接断线。

## 已知限制

- **有效 `minSdk` 是 34，不是 26**：`adventure-api` 的 `TextColorImpl` 用
  `java.util.HexFormat` 初始化静态字段，`mcprotocollib` 的 `UUIDUtils` 也直接调它；
  `HexFormat` 是 API 34。26 只是字节码/`MethodHandle` 的下限。
- `d8` 会打印数千行 `malformed kotlin.Metadata` / `Should never be called` 警告：
  build-tools 36 自带的 R8 早于 Kotlin 2.4。它们不影响 dex 产出，构建成功的判据是
  最后一行 `APK: …`；脚本里已用 `$LASTEXITCODE` 守住真正的失败。
- 这台设备（PLC110 / ColorOS）上 `adb install` 偶尔会挂几分钟：脚本会先把屏幕点亮
  （`svc power stayon true`），必要时临时停用第三方安装器（InstallerX）。
- 报告里的中文在 Windows 控制台可能显示成乱码，文件本身是 UTF-8。
