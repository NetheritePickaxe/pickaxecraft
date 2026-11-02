plugins {
    id("pickaxe.kmp.library")
}

// 生成器只在构建期使用，不进入任何运行时类路径。
val protocolGenerator: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies.add(protocolGenerator.name, project(":tools:protocol-generator"))

val generateProtocolVersion = tasks.register<JavaExec>("generateProtocolVersion") {
    group = "protocol"
    description = "从 schema/26.2 生成 ProtocolVersion.kt"

    // 输入输出必须显式声明：既保证增量正确，也保证配置缓存可序列化。
    // 版本号是模块事实：切换协议版本时同时替换目录名与这里的路径。
    val schemaDir = layout.projectDirectory.dir("schema/26.2")
    val generatedDir = layout.buildDirectory.dir("generated/protocol/commonMain/kotlin")
    inputs.dir(schemaDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedDir)

    classpath = protocolGenerator
    mainClass.set("org.pickaxe.tools.protocol.MainKt")
    args(schemaDir.asFile.absolutePath, generatedDir.get().asFile.absolutePath)

    // 分叉出的 JVM 不继承 Daemon 的编码设置：Windows 控制台默认 GBK，
    // 不显式指定时校验失败的中文原因会变成乱码。
    defaultCharacterEncoding = "UTF-8"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

kotlin {
    android {
        namespace = "org.pickaxe.protocol"
    }

    sourceSets {
        // 把生成目录当作普通源码目录，编译任务会自动依赖生成任务。
        commonMain {
            kotlin.srcDir(generateProtocolVersion)
        }
    }
}
