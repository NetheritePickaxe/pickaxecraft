plugins {
    id("pickaxe.jvm.cli")
}

dependencies {
    // 协议版本常量来自生成的 ProtocolVersion，不在此处重复声明数字。
    implementation(project(":protocol-core"))
}

application {
    mainClass = "org.pickaxe.app.MainKt"
}

// --version 需要的版本信息在构建期生成：客户端版本来自项目坐标，
// Minestom 版本来自版本目录。手写常量会与两者漂移，因此不在源码里重复。
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
    val clientVersion = project.version.toString()
    val minestomVersion = libs.versions.minestom.get()

    inputs.property("clientVersion", clientVersion)
    inputs.property("minestomVersion", minestomVersion)
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().asFile.resolve("org/pickaxe/app/BuildInfo.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// 由 :app:generateBuildInfo 生成，请勿手工编辑。
            |package org.pickaxe.app
            |
            |/** 构建期注入的版本信息。 */
            |public object BuildInfo {
            |    /** 客户端版本。 */
            |    public const val CLIENT_VERSION: String = "$clientVersion"
            |
            |    /** 本仓库联调所使用的 Minestom 版本。 */
            |    public const val MINESTOM_VERSION: String = "$minestomVersion"
            |}
            |
            """.trimMargin(),
            Charsets.UTF_8,
        )
    }
}

kotlin {
    sourceSets.getByName("main").kotlin.srcDir(generateBuildInfo)
}
