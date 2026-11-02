plugins {
    id("pickaxe.jvm.library")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(libs.kotlinxSerialization)
}
