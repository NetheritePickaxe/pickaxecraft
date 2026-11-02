// 跨平台库约定：同时产出 Desktop(Java 25) 与 Android(Java 21) 产物。
// 只放平台无关的通用配置；namespace 与模块专属依赖由模块自己声明。

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    // 公共 API 必须显式声明可见性，避免把实现细节泄漏成外部契约。
    explicitApi()
    jvmToolchain(25)

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    // AGP 9 起 KMP 的 Android 目标必须走 Android-KMP Library 插件，且为单变体架构。
    // AGP 9.4 起该配置块的名称由 `androidLibrary` 改为 `android`。
    android {
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        withHostTestBuilder {}.configure {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinxCoroutines").get().get())
        }
        // 当前所有目标都是 JVM 系（desktop + android），因此 JUnit 5 直接放在 commonTest。
        // 若将来引入非 JVM 目标，需把 JUnit 5 依赖下移到 desktopTest / androidHostTest。
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlinTestJunit5").get().get())
            implementation(libs.findLibrary("junitJupiter").get().get())
            runtimeOnly(libs.findLibrary("junitPlatformLauncher").get().get())
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}
