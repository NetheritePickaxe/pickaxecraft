pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // 这个探针不属于根构建：它必须像外部工程一样从 staging repo 与公开仓库解析依赖。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "staging"
            url = uri("../../build/staging-repo")
        }
        maven("https://repo.opencollab.dev/main/")
        mavenCentral()
    }
}

rootProject.name = "pickaxecraft-upstream-probe"
