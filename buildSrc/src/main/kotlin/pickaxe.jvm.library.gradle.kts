// 纯 JVM 库约定：Java 25 字节码、显式 API、JUnit 5。

import org.gradle.api.artifacts.VersionCatalogsExtension

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
    }
}
