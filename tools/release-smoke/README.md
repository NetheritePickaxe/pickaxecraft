# 发布冒烟

验证发布产物能否被**本仓库之外**的构建消费，而不是验证本仓库自己的模块依赖。

两个 fixture 都只依赖 `io.github.netheritepickaxe:client-core:0.1.0`：

| fixture | 构建系统 | 证明什么 |
| --- | --- | --- |
| `gradle/` | Gradle + Kotlin JVM，Java 21 toolchain | Gradle module metadata 与 POM 能解析出编译期/运行期依赖 |
| `maven/` | 纯 Maven（Wrapper，only-script） | POM 里的 `runtime` 依赖足以完成真实连接；不需要本机安装 Maven |

两者都只用公共 API 对测试 Minestom 服务做一次 `status` 查询。编译通过不算数：
协议库、Netty 与 Adventure 都是 `runtime` 依赖，缺一个就会在查询时失败。

## 运行

先准备 JDK 25（CLI 与联调服务是 Java 25 字节码；共享库仍发布 Java 21 字节码），
然后从仓库根执行：

```powershell
.\tools\release-smoke\run.ps1
```

```bash
./tools/release-smoke/run.sh
```

脚本依次：

1. `gradlew -Pversion=0.1.0 publishAllPublicationsToStagingRepository`，写入 `build/staging-repo`；
2. `gradlew :testing:minestom:installDist`，打包联调服务；
3. 启动联调服务并等待它自己打印 `event=server.started`；
4. 运行 Gradle 消费者；
5. 运行 Maven 消费者（首次会下载并校验 Maven 3.9.16，以及 Kotlin Maven 插件的依赖，
   在网络较慢的机器上可能耗时十几分钟；之后的运行走本地缓存）；
6. 回收测试服务进程。

任一消费者失败，脚本以非零码退出。默认端口 `25565`，可用参数覆盖（PowerShell：
`-Port`；sh：第一个参数）。

## 这个目录不属于根构建

`gradle/` 不在根 `settings.gradle.kts` 里：它必须像外部工程一样从仓库解析依赖，
否则测到的只是本仓库的工程依赖。`run.ps1` 用根目录的 `gradlew -p` 驱动它，
不额外下载一套 Gradle。

`maven/.mvn/wrapper/maven-wrapper.properties` 固定 Maven 版本并带 SHA-256；
升级时同步更新 `distributionUrl` 与校验和。
