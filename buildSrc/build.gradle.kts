plugins {
    // kotlin-dsl 提供预编译约定插件的开发能力。
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // 约定插件需要把 KGP 放进 buildSrc 的类路径才能 apply 它。
    implementation(libs.kotlinGradlePlugin)
}
