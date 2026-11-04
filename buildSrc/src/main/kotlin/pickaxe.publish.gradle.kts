// 共享库发布约定：坐标来自根项目的 group/version，产物附源码 jar，POM 声明 MIT。
// 0.x 阶段不承诺二进制兼容（政策见 README「发布与兼容」）；发布到公共仓库前
// 需要确定目标仓库，当前只支持 mavenLocal 消费。

import org.gradle.api.plugins.JavaPluginExtension

plugins {
    `maven-publish`
}

// java 扩展由共享库约定（kotlin-jvm）应用；这里只配置源码 jar，不重复应用插件。
configure<JavaPluginExtension> {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                // 各模块在自己的构建脚本里声明 description；缺失时退回模块名。
                description.set(project.description ?: project.name)
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
