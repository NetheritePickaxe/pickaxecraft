plugins {
    // kotlin-dsl 提供预编译约定插件的开发能力。
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // 约定插件需要在预编译脚本里同时应用 KGP 与 AGP。
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.androidGradlePlugin)
}
