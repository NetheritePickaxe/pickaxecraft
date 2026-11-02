plugins {
    id("pickaxe.jvm.cli")
}

dependencies {
    implementation(libs.minestom)
    // 与服务端共享锁定的协议版本，启动时交叉校验。
    implementation(project(":protocol-core"))
    // Minestom 通过 slf4j 输出，没有绑定就看不到任何服务端日志。
    runtimeOnly(libs.slf4jSimple)
}

application {
    mainClass = "org.pickaxe.testing.minestom.MainKt"
}
