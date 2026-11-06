import pickaxe.tasks.VerifyForbiddenDependencies

plugins {
    id("pickaxe.jvm.cli")
}

dependencies {
    implementation(libs.minestom)
    // 与服务端共享锁定的协议版本，启动时交叉校验；联调用例走客户端公共 API。
    implementation(project(":protocol-core"))
    implementation(project(":client-core"))
    // 联调用例用 runBlocking 驱动挂起 API。
    implementation(libs.kotlinxCoroutines)
    // Minestom 通过 slf4j 输出，没有绑定就看不到任何服务端日志。
    runtimeOnly(libs.slf4jSimple)
}

application {
    mainClass = "org.pickaxe.testing.minestom.MainKt"
}

// 本模块就是 Minestom 宿主：minestom 以及它携带的 netty 都是合法依赖。
tasks.named<VerifyForbiddenDependencies>("verifyModuleBoundaries") {
    forbiddenPackages.set(emptyList())
}

// Minestom 是进程级单例，一个 JVM 只能初始化一次；每个测试类都要拉起服务端，
// 因此按类分叉，代价是每个类各自启动一次 JVM。
tasks.named<Test>("test") {
    forkEvery = 1
    // 服务端默认在方块放置后等 100 tick 才重发光照（5 秒），联调只需确认“增量确实到了”，
    // 不必为这个节流等满一轮；ServerProperties 在类初始化时读取系统属性，必须在这里设。
    systemProperty("minestom.send-light-after-block-placement-delay", "2")
    // 这个模块的测试会拉起真实服务端，服务端日志就是断言依据；
    // 默认 Gradle 只把标准输出收进报告，CI 上等于看不见，因此显式放开。
    testLogging {
        showStandardStreams = true
    }
}

// 版本资产导出：只显式运行，不挂 build —— 生成文件属于源码，不能让构建静默改写它。
// 升级 mcprotocollib/minestom 后跑一次，用 diff 判断上游改了什么。
tasks.register<JavaExec>("exportItemPrototypes") {
    group = "asset"
    description = "从 Minestom 物品注册表重导出 protocol-core 的 ItemPrototypes.kt"
    mainClass.set("org.pickaxe.testing.minestom.asset.ExportAssetsKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(
        "item-prototypes",
        rootProject.file("protocol-core/src/main/kotlin/org/pickaxe/protocol/item/ItemPrototypes.kt").absolutePath,
        "Minestom ${libs.versions.minestom.get()}",
    )
}
