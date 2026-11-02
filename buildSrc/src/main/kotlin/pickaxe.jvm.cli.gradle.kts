// JVM 可执行程序约定：在 JVM 库约定之上加入 application 插件。
// mainClass 与运行参数属于模块信息，由模块自己声明。

plugins {
    id("pickaxe.jvm.library")
    application
}
