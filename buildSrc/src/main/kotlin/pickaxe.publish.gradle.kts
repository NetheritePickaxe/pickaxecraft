// 共享库发布约定：坐标来自根项目的 group/version，产物附源码与 Dokka Javadoc，
// POM 带完整项目元数据，jar 内含许可证，签名按属性启用（默认跳过）。
//
// 0.x 阶段不承诺二进制兼容（政策见 README「发布与兼容」）；目标仓库未定，
// 因此只提供本地 staging repo 与 mavenLocal，不在这里绑定公共仓库地址。

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    `maven-publish`
    signing
}

// java 扩展由共享库约定（kotlin-jvm）应用；这里只配置源码 jar，不重复应用插件。
configure<JavaPluginExtension> {
    withSourcesJar()
}

// 显示名从 artifactId 派生：protocol-core -> PickaxeCraft Protocol Core。
// 只在 POM 生成时读取，避免在模块声明 description 之前求值（旧实现因此退化成 artifactId）。
val pomDisplayName = "PickaxeCraft " + project.name
    .split('-')
    .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set(pomDisplayName)
                // description 由模块在 build 脚本里声明；afterEvaluate 保证读取发生在声明之后。
                url.set("https://github.com/NetheritePickaxe/pickaxecraft")
                scm {
                    connection.set("scm:git:https://github.com/NetheritePickaxe/pickaxecraft.git")
                    developerConnection.set("scm:git:ssh://git@github.com/NetheritePickaxe/pickaxecraft.git")
                    url.set("https://github.com/NetheritePickaxe/pickaxecraft")
                }
                developers {
                    developer {
                        id.set("NetheritePickaxe")
                        name.set("NetheritePickaxe")
                        url.set("https://github.com/NetheritePickaxe")
                    }
                }
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }

    // 本地 staging：所有模块写进根目录的同一个仓库，外部消费 fixture 只读这里，
    // 避免依赖 ~/.m2 或按模块拼多个仓库路径。
    repositories {
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
}

afterEvaluate {
    publishing.publications.named<MavenPublication>("maven") {
        pom.description.set(project.description ?: project.name)
    }
}

// jar 内携带许可证，便于离线分发与合规审查；不复制到 processResources，
// 避免和模块自己的资源目录重复。
tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) { into("META-INF") }
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString(),
        )
    }
}

// Dokka 2.x 的 Javadoc 格式通过独立插件提供。输出目录是 2.3 的默认值；
// 任务运行前确认 index.html 存在，避免生成空 jar 的静默降级。
plugins.withId("org.jetbrains.dokka-javadoc") {
    val javadocOutput = layout.buildDirectory.dir("dokka/javadoc")
    val javadocJar = tasks.register<Jar>("javadocJar") {
        group = "documentation"
        description = "把 Dokka 生成的 Javadoc 打包为 -javadoc.jar"
        dependsOn(tasks.named("dokkaGeneratePublicationJavadoc"))
        archiveClassifier.set("javadoc")
        from(javadocOutput)
        doFirst {
            val index = javadocOutput.get().asFile.resolve("index.html")
            check(index.isFile) { "Dokka 未生成 Javadoc 输出，缺少 $index" }
        }
    }

    publishing.publications.named<MavenPublication>("maven") {
        artifact(javadocJar)
    }
}

// 签名能力：只有提供了内存 PGP key 才启用，默认不阻塞本地发布。
// 目标仓库确定后由 CI secrets 注入；仓库内不允许出现密钥或 .asc。
val signingKey = providers.environmentVariable("SIGNING_KEY")
    .orElse(providers.gradleProperty("signingKey"))
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
    .orElse(providers.gradleProperty("signingPassword"))

signing {
    isRequired = false
    val key = signingKey.orNull
    if (!key.isNullOrBlank()) {
        useInMemoryPgpKeys(key, signingPassword.orNull)
        sign(publishing.publications.named("maven").get())
    }
}
