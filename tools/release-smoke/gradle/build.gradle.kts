// 发布冒烟：只消费 staging repo 里的 client-core，证明公开 API 足以完成一次 status 查询。
// 这个工程不属于根构建（不在 settings.gradle.kts 里），依赖必须走仓库解析。
plugins {
    kotlin("jvm") version "2.4.21"
    application
}

kotlin {
    // 与发布产物一致：消费方按 Java 21 字节码编译，证明共享库的真实下限可消费。
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("io.github.netheritepickaxe:client-core:0.1.0")
}

application {
    mainClass = "smoke.SmokeKt"
}
