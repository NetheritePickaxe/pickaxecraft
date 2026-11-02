plugins {
    // 共享构建逻辑位于 buildSrc/src/main/kotlin。
    id("buildsrc.convention.kotlin-jvm")

    application
}

application {
    // Kotlin 会把 App.kt 编译为 `org.pickaxe.app.AppKt`。
    mainClass = "org.pickaxe.app.AppKt"
}
