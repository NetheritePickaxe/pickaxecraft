// 项目坐标集中声明，模块不再各自重复。
// groupId 与 Kotlin 包名 org.pickaxe 不必一致：前者是 Maven 坐标，后者是源码命名空间。
// 发布时用 -Pversion=0.1.0 产出正式版本；默认 SNAPSHOT 只用于本地消费。
allprojects {
    group = "io.github.netheritepickaxe"
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
}
