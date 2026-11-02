plugins {
    id("pickaxe.jvm.cli")
}

application {
    // Kotlin 会把 App.kt 编译为 `org.pickaxe.app.AppKt`。
    mainClass = "org.pickaxe.app.AppKt"
}
