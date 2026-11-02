package pickaxe.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * 断言编译类路径上不出现被禁止的包。
 *
 * 检查的是“依赖声明泄漏”，而不是重复实现编译器已经保证的事：
 * 公共源集看不见 JVM-only API 由编译器保证，但把 Netty 加进公共模块的依赖
 * 编译器不会反对，最终会把桌面传输实现绑进 Android 产物。
 */
@CacheableTask
public abstract class VerifyForbiddenDependencies : DefaultTask() {

    /** 待检查的编译类路径。 */
    @get:Classpath
    public abstract val classpath: ConfigurableFileCollection

    /** 被禁止的包前缀，形如 `io/netty/`。 */
    @get:Input
    public abstract val forbiddenPackages: ListProperty<String>

    /** 报告中显示的检查范围，例如模块名。 */
    @get:Input
    public abstract val scope: Property<String>

    @TaskAction
    public fun verify() {
        val forbidden = forbiddenPackages.get()
        if (forbidden.isEmpty()) {
            logger.info("verifyModuleBoundaries[{}]: 未配置禁止项，跳过", scope.get())
            return
        }

        val violations = mutableListOf<String>()
        classpath.files
            .filter { it.isFile && it.name.endsWith(".jar") }
            .forEach { jar ->
                ZipFile(jar).use { zip ->
                    // Enumeration 只能消费一次，先落地成列表再按前缀匹配。
                    val names = zip.entries().asSequence().map { it.name }.toList()
                    forbidden.forEach { prefix ->
                        if (names.any { it.startsWith(prefix) }) {
                            violations += "${jar.name} 含 $prefix"
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("${scope.get()} 的编译类路径出现禁止的依赖：")
                    violations.forEach { append("\n  - ").append(it) }
                    append("\n公共模块不得依赖桌面传输、服务端或 Android 框架实现。")
                },
            )
        }

        logger.info("verifyModuleBoundaries[{}]: 通过，禁止项 {}", scope.get(), forbidden.joinToString())
    }
}
