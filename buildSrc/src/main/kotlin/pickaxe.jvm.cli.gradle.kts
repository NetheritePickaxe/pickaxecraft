// JVM 可执行程序约定：在 JVM 库约定之上加入 application 插件。
// mainClass 与运行参数属于模块信息，由模块自己声明。

plugins {
    id("pickaxe.jvm.library")
    application
}

application {
    // 分叉出的 JVM 不继承 Gradle Daemon 的编码设置；Windows 控制台默认 GBK，
    // 不强制 UTF-8 时中文输出会乱码。
    applicationDefaultJvmArgs = listOf(
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}
