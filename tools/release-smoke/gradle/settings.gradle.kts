import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // fixture 只允许从 staging repo 与公开仓库取依赖：如果产物缺了什么，
    // 这里会直接失败，而不是悄悄用本仓库的工程依赖兜底。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "staging"
            url = uri("../../../build/staging-repo")
        }
        // MCProtocolLib 的发布仓库；消费方必须显式声明，Maven Central 没有它。
        maven("https://repo.opencollab.dev/main/")
        mavenCentral()
    }
}

rootProject.name = "pickaxecraft-release-smoke-gradle"
