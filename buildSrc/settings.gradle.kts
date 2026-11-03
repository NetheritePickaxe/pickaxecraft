dependencyResolutionManagement {

    // 共享构建逻辑只解析 Kotlin Gradle Plugin（Maven Central）。
    // 引入 Android 目标时需要重新加回 google()。
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }

    // 复用主构建的版本目录，避免版本在两处漂移。
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"
