// 构建入口：声明仓库、插件来源与子项目。
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    // 允许子项目通过 toolchain 声明自动获取所需的 JDK。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// 模块按“首个真实调用方”落位，不为未来概念建空模块。
// 见 docs/plans/m0-implementation-plan.md 决策 2；其余模块在各自任务中与本行一并创建。
include(":app")
include(":protocol-core")
include(":tools:protocol-generator")

rootProject.name = "pickaxecraft"
