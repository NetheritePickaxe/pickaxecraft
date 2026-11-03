// 构建入口：声明仓库、插件来源与子项目。
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // MCProtocolLib 只发布在 Open Collaboration 仓库，不在 Maven Central。
        // 它依赖的两组坐标（Cloudburst math、nukkitx fastutil）同样只在这里。
        // 用 content 限定组，避免这个仓库参与其他依赖的元数据解析。
        maven("https://repo.opencollab.dev/main/") {
            name = "opencollab"
            content {
                includeGroup("org.geysermc.mcprotocollib")
                includeGroup("org.cloudburstmc.math")
                includeGroup("com.nukkitx.fastutil")
            }
        }
    }
}

plugins {
    // 允许子项目通过 toolchain 声明自动获取所需的 JDK。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// 模块按“首个真实调用方”落位，不为未来概念建空模块。
// 见 docs/decisions/0001-protocol-backend.md。
include(":app")
include(":client-core")
include(":protocol-core")
include(":testing:minestom")

rootProject.name = "pickaxecraft"
