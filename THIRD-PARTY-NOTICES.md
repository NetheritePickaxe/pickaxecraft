# 第三方依赖许可证

本项目（PickaxeCraft）以 [MIT](LICENSE) 开源。本文件列出依赖的许可证，供下游合规参考。

事实来源：各构件发布在 Maven 仓库的 POM 声明，POM 未声明时取上游仓库与构件内嵌文件。
**升级依赖或新增依赖时必须同步更新本文件**（`./gradlew :client-core:dependencies --configuration runtimeClasspath`
可列出实际解析的完整清单）。

本项目不打包（shade）任何第三方构件，依赖由使用方在构建时自行解析；
本库产物只包含本项目代码。

## 运行时依赖

### MIT

| 构件 | 许可证证据 |
| --- | --- |
| `org.geysermc.mcprotocollib:protocol` | POM |
| `org.geysermc.mcprotocollib:text-serializer-nbt` | POM |
| `net.kyori:adventure-*`（api、key、option、text-serializer-plain/gson/json/commons） | POM |
| `net.lenni0451.commons:httpclient`、`net.lenni0451.commons:gson` | POM |
| `org.cloudburstmc.math:api`、`org.cloudburstmc.math:immutable` | 上游仓库（CloudburstMC/math） |
| `org.slf4j:slf4j-api` | 构件内 LICENSE.txt |
| `org.checkerframework:checker-qual` | POM |

### Apache-2.0

| 构件 | 许可证证据 |
| --- | --- |
| `io.netty:netty-*`（common、buffer、transport、handler、resolver、codec-base、transport-native-unix-common） | 官方声明 |
| `org.jetbrains.kotlin:kotlin-stdlib` | POM |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core(-jvm)` | POM |
| `org.cloudburstmc:nbt` | POM |
| `com.nukkitx.fastutil:fastutil-*` | 上游仓库（CloudburstMC/fastutil） |
| `com.google.code.gson:gson` | POM |
| `com.google.errorprone:error_prone_annotations` | POM |
| `org.jetbrains:annotations` | POM |
| `org.jspecify:jspecify` | 官方声明 |

### 需要单独说明：MinecraftAuth（LGPL-3.0）

`net.raphimc:MinecraftAuth` 是协议库的传递依赖（在线认证入口，M5 前本库不直接使用），
其许可证为 **LGPL-3.0**，是运行时依赖中唯一的著佐权组件：

- 本库以「依赖」方式引用、不复制其源码、不打包其字节码，LGPL 义务由**分发该构件的一方**
  承担（Maven 仓库已随构件提供许可证与源码）；
- 下游若把本库与 MinecraftAuth **打包进同一分发物**（如 shade、自定义 JRE 镜像），
  需自行遵守 LGPL-3.0 对该组件的义务（保留版权声明、提供源码获取途径、允许替换）；
- 不接受该义务的下游可在自己的依赖声明中按需处理——本库核心链路（离线登录）不依赖它。

## 仅测试与联调使用（不进入发布产物）

| 构件 | 许可证 |
| --- | --- |
| `net.minestom:minestom`（`testing:minestom` 联调宿主） | Apache-2.0 |
| `org.junit.jupiter:*` | EPL-2.0 |
| `org.jetbrains.kotlin:kotlin-test-junit5` | Apache-2.0 |
| `org.slf4j:slf4j-simple` | MIT |
