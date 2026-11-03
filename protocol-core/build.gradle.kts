plugins {
    id("pickaxe.jvm.shared")
}

dependencies {
    // MCProtocolLib 一律走 implementation：它的 Netty 类型与数据包模型
    // 不允许出现在上层模块的编译类路径上，否则适配层就白做了。
    implementation(libs.mcprotocollib) {
        // netty-all 是聚合 POM，会连带 protobuf、zstd-jni、brotli4j 与本地传输构件；
        // 客户端只需要下面声明的四个模块。
        exclude(group = "io.netty", module = "netty-all")
    }
    implementation(libs.bundles.nettyClient)
}
