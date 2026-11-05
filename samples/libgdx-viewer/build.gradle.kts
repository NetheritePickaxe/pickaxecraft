// 示例模块：下游如何消费核心库。
//
// 它**不是**核心的一部分：核心不依赖 libGDX，反过来示例依赖核心。这里也没有任何模块
// 反向依赖它，因此它不进发布产物，可以随下游需求随时重写。
//
// 用 headless 后端而不是 lwjgl3：CI 没有显示器，也没有可用的 GL 上下文；
// 网格构建这一步本来就不需要 GL，真正上屏的那一半留给下游仓库。

plugins {
    id("pickaxe.jvm.shared")
    application
}

description = "PickaxeCraft 示例：把区块网格化并交给 libGDX（headless，无 GL）"

application {
    mainClass.set("org.pickaxe.samples.viewer.MainKt")
}

dependencies {
    implementation(project(":client-core"))
    implementation(libs.libgdxCore)
    implementation(libs.libgdxHeadless)
    // headless 后端仍会加载 gdx 的本地库（分辨率、BufferUtils 等在 native 侧），
    // 只是不会创建 GL 上下文。缺了它会在 GdxNativesLoader 处抛 SharedLibraryLoadRuntimeException。
    runtimeOnly(variantOf(libs.libgdxNatives) { classifier("natives-desktop") })
}

// 示例与核心一样不得把服务端或协议实现拖进编译类路径：它演示的是“下游只看得见公共 API”。
tasks.named<pickaxe.tasks.VerifyForbiddenDependencies>("verifyModuleBoundaries") {
    forbiddenPackages.set(listOf("io/netty/", "net/minestom/", "org/geysermc/mcprotocollib/"))
}
