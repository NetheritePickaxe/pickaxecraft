package pickaxe.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * 断言运行时类路径上的全部 `io.netty` 模块解析到版本目录里的同一版本。
 *
 * 安全公告按模块发布：只升级直接依赖的四个模块，`netty-resolver`、`netty-codec-base`、
 * `netty-transport-native-unix-common` 仍会停在旧版本，修复并不完整。因此这里检查
 * 依赖图的解析结果，而不是构建脚本里写了什么。
 */
@CacheableTask
public abstract class VerifyNettyVersion : DefaultTask() {

    /** 版本目录中的期望版本，例如 `4.2.20.Final`。 */
    @get:Input
    public abstract val expectedVersion: Property<String>

    /** 运行时类路径解析结果：`group:name -> version`。 */
    @get:Input
    public abstract val resolvedVersions: MapProperty<String, String>

    @TaskAction
    public fun verify() {
        val expected = expectedVersion.get()
        val resolved = resolvedVersions.get()
        if (resolved.isEmpty()) {
            logger.info("verifyNettyVersion[{}]: 运行时类路径没有 io.netty 依赖，跳过", path)
            return
        }

        val mismatched = resolved.filterValues { it != expected }
        if (mismatched.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("io.netty 解析版本应为 $expected，不符合的模块 ${mismatched.size} 个：")
                    mismatched.toSortedMap().forEach { (module, version) ->
                        append("\n  - ").append(module).append(": ").append(version)
                    }
                    append("\n检查 netty-bom 是否覆盖了全部消费方（见 docs/decisions/0003-netty-upgrade.md）。")
                },
            )
        }

        logger.info("verifyNettyVersion[{}]: {} 个 io.netty 模块均为 {}", path, resolved.size, expected)
    }
}

/**
 * 注册 [VerifyNettyVersion]，期望版本取自版本目录。
 *
 * 各模块只在 `check` 里依赖它；解析结果通过 `Provider` 传给任务输入，
 * 既避免在配置阶段强制解析，也让配置缓存能保存快照。
 */
public fun Project.registerVerifyNettyVersion(): TaskProvider<VerifyNettyVersion> {
    val expectedVersion = extensions.getByType<VersionCatalogsExtension>().named("libs")
        .findVersion("netty")
        .get()
        .requiredVersion

    val resolvedVersions = configurations.named("runtimeClasspath").map { configuration ->
        configuration.incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                component.moduleVersion
                    ?.takeIf { it.group == "io.netty" }
                    ?.let { "${it.group}:${it.name}" to it.version }
            }
            .toMap()
    }

    return tasks.register<VerifyNettyVersion>("verifyNettyVersion") {
        description = "校验 io.netty 解析版本与版本目录一致"
        group = "verification"
        this.expectedVersion.set(expectedVersion)
        this.resolvedVersions.set(resolvedVersions)
    }
}
