// 纯 JVM 库约定：Java 25 字节码、显式 API、JUnit 5、字节码与依赖边界校验。

import org.gradle.api.artifacts.VersionCatalogsExtension
import pickaxe.tasks.VerifyBytecode
import pickaxe.tasks.VerifyForbiddenDependencies

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    explicitApi()
    jvmToolchain(25)
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
    description = "校验产物为 Java 25 字节码"
    group = "verification"
    targetName.set("main")
    expectedMajorVersion.set(69)
    // 以编译任务的输出作为输入，任务依赖由 Gradle 自动建立。
    classDirectories.from(tasks.named("compileKotlin"))
    classDirectories.from(tasks.named("compileJava"))
}

val verifyModuleBoundaries = tasks.register<VerifyForbiddenDependencies>("verifyModuleBoundaries") {
    description = "校验编译类路径不含禁止依赖"
    group = "verification"
    scope.set(project.path)
    forbiddenPackages.set(listOf("io/netty/", "net/minestom/"))
    classpath.from(configurations.named("compileClasspath"))
}

tasks.named("check") {
    dependsOn(verifyBytecode, verifyModuleBoundaries)
}
