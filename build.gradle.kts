import pickaxe.tasks.VerifyCentralBundle

// 项目坐标集中声明，模块不再各自重复。
// groupId 与 Kotlin 包名 org.pickaxe 不必一致：前者是 Maven 坐标，后者是源码命名空间。
// 发布时用 -Pversion=0.1.0 产出正式版本；默认 SNAPSHOT 只用于本地消费。
allprojects {
    group = "io.github.netheritepickaxe"
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
}

// Maven Central（Central Portal）只接受一个 zip，内容是 Maven 仓库布局，
// 且每个构件都要有 .asc 与 .md5/.sha1。这里把检查放在打包之前：
// 发布不可撤回，不能让缺签名的 bundle 走一遍 Portal 校验。
val stagingRepoDir = layout.buildDirectory.dir("staging-repo")

val cleanStagingRepo = tasks.register<Delete>("cleanStagingRepo") {
    group = "publishing"
    description = "清空本地 staging repo，确保 bundle 不含上一次发布的残留文件"
    delete(stagingRepoDir)
}

// 所有模块共用一个 staging repo；发布必须发生在清空之后，
// 否则 bundle 里会混进旧版本或旧签名的文件。
subprojects {
    tasks.matching { it.name == "publishAllPublicationsToStagingRepository" }.configureEach {
        mustRunAfter(cleanStagingRepo)
    }
}

val verifyCentralBundle = tasks.register<VerifyCentralBundle>("verifyCentralBundle") {
    group = "publishing"
    description = "校验即将上传 Central 的产物：版本、签名与校验和"
    dependsOn(cleanStagingRepo)
    dependsOn(
        ":protocol-core:publishAllPublicationsToStagingRepository",
        ":client-core:publishAllPublicationsToStagingRepository",
    )
    version.set(project.version.toString())
    stagingRepo.set(stagingRepoDir)
}

val centralBundle = tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "生成可上传 Central Portal 的签名 bundle（zip，Maven 仓库布局）"

    dependsOn(verifyCentralBundle)
    mustRunAfter(verifyCentralBundle)

    from(stagingRepoDir)
    include("io/**")
    // 仓库索引不属于发布构件；.asc 自身也不需要校验和。
    exclude("**/maven-metadata.xml*")
    exclude("**/*.asc.md5", "**/*.asc.sha1", "**/*.asc.sha256", "**/*.asc.sha512")
    destinationDirectory.set(layout.buildDirectory)
    archiveFileName.set("pickaxecraft-${project.version}.zip")
}
