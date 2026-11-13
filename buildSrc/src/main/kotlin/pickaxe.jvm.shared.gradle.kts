// 共享库约定：产物为 Java 21 字节码，供桌面运行、并为将来的 Android 宿主保留可用性。
// 与 pickaxe.jvm.library 的差别只有字节码目标与对应的门控，其余配置保持一致。
//
// 只要一个模块会被 Android 复用，就必须走这个约定：Java 25 字节码（major 69）
// 现有 D8 无法吃下，桌面端“跑得起来”不代表移动端可消费。

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import pickaxe.tasks.VerifyBytecode
import pickaxe.tasks.VerifyForbiddenDependencies
import pickaxe.tasks.registerVerifyNettyVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    explicitApi()
    jvmToolchain(25)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// Kotlin 的 jvmTarget 与 Java 的 target 必须一致：否则 KGP 直接判定构建不一致，
// 而且两个编译任务产出的字节码版本也会不同。
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    testImplementation(libs.findLibrary("kotlinTestJunit5").get().get())
    testImplementation(libs.findLibrary("junitJupiter").get().get())
    testRuntimeOnly(libs.findLibrary("junitPlatformLauncher").get().get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        // 默认只打印类名与行号，CI 上无法据此判断断言内容，必须带上异常消息。
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

val verifyBytecode = tasks.register<VerifyBytecode>("verifyBytecode") {
    description = "校验产物为 Java 21 字节码（Android 可消费）"
    group = "verification"
    targetName.set("main")
    expectedMajorVersion.set(65)
    // 以编译任务的输出作为输入，任务依赖由 Gradle 自动建立。
    classDirectories.from(tasks.named("compileKotlin"))
    classDirectories.from(tasks.named("compileJava"))
}

// 共享模块不得依赖服务端实现；协议库与 Netty 允许出现在本模块，
// 但只能通过 implementation 引入，不得出现在上层模块的编译类路径。
val verifyModuleBoundaries = tasks.register<VerifyForbiddenDependencies>("verifyModuleBoundaries") {
    description = "校验编译类路径不含服务端依赖"
    group = "verification"
    scope.set(project.path)
    forbiddenPackages.set(listOf("net/minestom/"))
    classpath.from(configurations.named("compileClasspath"))
}

// 运行时类路径上的 Netty 版本必须与版本目录一致：传递模块的旧版本同样是安全问题。
val verifyNettyVersion = registerVerifyNettyVersion()

tasks.named("check") {
    dependsOn(verifyBytecode, verifyModuleBoundaries, verifyNettyVersion)
}
