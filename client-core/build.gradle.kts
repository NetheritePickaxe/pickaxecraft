// 客户端核心：只依赖 protocol-core 的语义事件，看不见协议库与 Netty。
// 产物为 Java 21 字节码，供桌面与后续移动宿主共用。

plugins {
    id("pickaxe.jvm.shared")
}

dependencies {
    // 上层需要 ServerAddress / ServerStatus 这类项目自有类型，因此走 api。
    // 协议库本身在 protocol-core 里是 implementation，不会跟随到本模块的编译类路径。
    api(project(":protocol-core"))
    // StateFlow / Flow 出现在公共 API 上，必须用 api 暴露。
    api(libs.kotlinxCoroutines)
}

// 共享约定默认只禁 Minestom（protocol-core 需要 Netty 与协议库）；
// 本模块是第一个真正的“上层”，必须证明协议实现没有穿透过来。
tasks.named<pickaxe.tasks.VerifyForbiddenDependencies>("verifyModuleBoundaries") {
    forbiddenPackages.set(listOf("io/netty/", "net/minestom/", "org/geysermc/mcprotocollib/"))
}
