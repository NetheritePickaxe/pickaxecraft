dependencyResolutionManagement {

    // 共享构建逻辑需要解析 KGP 与 AGP，两者分别来自 Maven Central 与 Google Maven。
    @Suppress("UnstableApiUsage")
    repositories {
        google()
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
