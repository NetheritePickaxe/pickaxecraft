package pickaxe.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 上传 Central Portal 之前的 bundle 断言。
 *
 * Maven Central 的要求里有两类“上传后才报错”最浪费时间：版本不能是 SNAPSHOT，
 * 每个构件必须有 `.asc` 与 `.md5`/`.sha1`。发布不可撤回，所以在打包前直接失败，
 * 而不是让半成品 bundle 走一遍 Portal 校验。
 */
public abstract class VerifyCentralBundle : DefaultTask() {

    /** 将要发布的版本；`-SNAPSHOT` 由 Central 拒绝。 */
    @get:Input
    public abstract val version: Property<String>

    /** 本地 staging repo（Maven 仓库布局，根下是 `io/`）。 */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val stagingRepo: DirectoryProperty

    @TaskAction
    public fun verify() {
        val releaseVersion = version.get()
        if (releaseVersion.endsWith("-SNAPSHOT")) {
            throw GradleException("Central 不接受 SNAPSHOT 版本，请用 -Pversion=x.y.z（当前：$releaseVersion）")
        }

        val root = stagingRepo.get().asFile
        if (!root.isDirectory) {
            throw GradleException("staging repo 不存在：$root")
        }

        // maven-metadata.xml 属于仓库索引，不属于发布构件；bundle 会把它排除掉。
        val files = root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith("maven-metadata.xml") }
            .toList()
        if (files.isEmpty()) {
            throw GradleException("staging repo 为空：$root")
        }

        val artifacts = files.filter { file ->
            file.name.endsWith(".jar") || file.name.endsWith(".pom") || file.name.endsWith(".module")
        }
        if (artifacts.isEmpty()) {
            throw GradleException("staging repo 中没有可发布构件：$root")
        }

        val missing = buildList {
            for (artifact in artifacts) {
                for (suffix in REQUIRED_SUFFIXES) {
                    if (!java.io.File(artifact.path + suffix).isFile) {
                        add("${artifact.name}$suffix")
                    }
                }
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("bundle 缺少签名或校验和，Central 会拒绝这批产物：")
                    missing.sorted().forEach { append("\n  - ").append(it) }
                    append("\n确认 SIGNING_KEY / SIGNING_PASSWORD 已提供（签名默认关闭）。")
                },
            )
        }

        logger.lifecycle(
            "verifyCentralBundle[{}]: {} 个构件，版本 {}，签名与校验和齐全",
            path,
            artifacts.size,
            releaseVersion,
        )
    }

    private companion object {
        val REQUIRED_SUFFIXES: List<String> = listOf(".asc", ".md5", ".sha1")
    }
}
