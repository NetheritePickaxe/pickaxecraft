package pickaxe.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * 断言编译产物的 Java 字节码主版本。
 *
 * 目标版本是外部契约（Android 21 / Desktop 25），一旦某个模块的编译配置被改坏，
 * 编译仍然会成功，但产物在目标平台无法加载；这里把它变成构建期失败。
 */
@CacheableTask
public abstract class VerifyBytecode : DefaultTask() {

    /** 待检查的 class 输出目录。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val classDirectories: ConfigurableFileCollection

    /** 期望的 class 文件主版本号，例如 Java 21 为 65、Java 25 为 69。 */
    @get:Input
    public abstract val expectedMajorVersion: Property<Int>

    /** 报告中显示的产物名，例如 desktop。 */
    @get:Input
    public abstract val targetName: Property<String>

    /**
     * 是否允许没有任何 class 文件的模块。
     *
     * 默认为 false：输入目录一旦配错（改名、写错路径），检查会因为“没有文件”而静默通过，
     * 形成假阳性。只有确实不含代码的模块才显式放开。
     */
    @get:Input
    @get:Optional
    public abstract val allowEmpty: Property<Boolean>

    @TaskAction
    public fun verify() {
        val expected = expectedMajorVersion.get()
        val roots = classDirectories.files
        val classes = roots
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { file -> file.isFile && file.extension == "class" }.toList() }

        if (classes.isEmpty()) {
            if (allowEmpty.getOrElse(false)) {
                logger.info("verifyBytecode[{}]: 没有 class 文件，按配置跳过", targetName.get())
                return
            }
            throw GradleException(
                "${targetName.get()} 没有任何 class 文件，无法校验字节码版本。" +
                    "\n检查 classDirectories 是否指向了正确的编译输出目录；" +
                    "若模块确实不含代码，请显式设置 allowEmpty.set(true)。",
            )
        }

        val violations = classes.mapNotNull { file ->
            val actual = majorVersionOf(file)
            if (actual == expected) null else "${relativeTo(file, roots)} 为 $actual"
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("${targetName.get()} 字节码版本应为 $expected，不符合的文件 ${violations.size} 个：")
                    violations.take(10).forEach { append("\n  - ").append(it) }
                    if (violations.size > 10) append("\n  ... 其余 ${violations.size - 10} 个略")
                },
            )
        }

        logger.info("verifyBytecode[{}]: {} 个 class 文件主版本均为 {}", targetName.get(), classes.size, expected)
    }

    /** 读取 class 文件头的主版本号：magic(4) + minor(2) + major(2)，大端序。 */
    private fun majorVersionOf(file: File): Int {
        val header = ByteArray(8)
        file.inputStream().use { input ->
            if (input.read(header) != header.size) {
                throw GradleException("${file.path} 不是合法的 class 文件")
            }
        }
        return ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
    }

    private fun relativeTo(file: File, roots: Set<File>): String {
        val root = roots.firstOrNull { file.path.startsWith(it.path) } ?: return file.path
        return file.path.removePrefix(root.path).trimStart(File.separatorChar)
    }
}
