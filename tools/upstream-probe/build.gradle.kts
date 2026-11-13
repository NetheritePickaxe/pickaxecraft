import org.gradle.api.initialization.resolve.RepositoriesMode

// 上游探针：用 MCProtocolLib 的指定版本消费已发布的 client-core，回答一个问题——
// “上游发新版后，适配层还能不能完成状态查询与一次离线登录？”
//
// 依赖由命令行注入：-PmcplVersion=<版本>（默认与版本目录一致）。
// 版本不同即会覆盖 client-core 的传递依赖，因此探针失败通常意味着上游破坏性变更，
// 而不是本仓库代码有问题。

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

val mcplVersion = providers.gradleProperty("mcplVersion").orElse("26.2").get()
val clientVersion = providers.gradleProperty("clientVersion").orElse("0.1.0").get()
val probeHost = providers.gradleProperty("probeHost").orElse("127.0.0.1").get()
val probePort = providers.gradleProperty("probePort").orElse("25565").get()

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation("io.github.netheritepickaxe:client-core:$clientVersion")
}

// 只覆盖 MCProtocolLib 的版本：这是探针唯一想改变的变量。
configurations.all {
    resolutionStrategy.force("org.geysermc.mcprotocollib:protocol:$mcplVersion")
}

application {
    mainClass = "upstream.ProbeKt"
}

tasks.named<JavaExec>("run") {
    // 探针把实际使用的 MCPL 版本打印出来，报告里不需要再猜。
    args(mcplVersion, probeHost, probePort)
}
