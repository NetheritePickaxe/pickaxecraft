package org.pickaxe.protocol

/**
 * 一条插件消息（`custom_payload`）。
 *
 * 插件消息是客户端与服务端之间的**裸字节通道**：协议只规定渠道名与一段不透明字节，
 * 语义完全由渠道约定决定，本库不解释任何具体渠道的内容（`minecraft:brand` 的品牌串
 * 是唯一由 [PluginMessages.parseBrand] 代读的例外）。
 *
 * [data] 在构造时做防御性拷贝，每次调用又返回一份新副本：外部持有的数组与消息内部状态互不影响。
 * 因此本类不用 `data class`——数组字段的 `equals` 是引用比较，那会给出错误的相等语义。
 *
 * @param channel 渠道名，`namespace:path` 形式的 resource location（如 `minecraft:brand`）。
 *   收到的消息不做校验（服务端是权威，坏渠道原样透传）；发送前用
 *   [PluginMessages.requireValidChannel] 校验。
 * @param data 消息体；长度不得超过 [PluginMessages.MAX_PAYLOAD_SIZE]。
 */
public class PluginMessage(
    public val channel: String,
    data: ByteArray,
) {

    /** 内部持有的一份拷贝；对外只通过 [data] 交出副本。 */
    private val bytes: ByteArray = data.copyOf()

    public constructor(channel: String, data: String) : this(
        channel,
        data.toByteArray(Charsets.UTF_8),
    )

    /**
     * 消息体的独立副本。
     *
     * 每次调用都复制一份：调用方改写返回的数组不会影响这条消息，也不会影响下一次取到的内容。
     * 需要反复读取时请自己保存一份，不要在高频路径里逐次复制。
     */
    public fun data(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PluginMessage && other.channel == channel && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = channel.hashCode() * 31 + bytes.contentHashCode()

    override fun toString(): String = "PluginMessage(channel=$channel, ${bytes.size} bytes)"
}

/** 插件消息的协议常量与解析工具。 */
public object PluginMessages {

    /** 品牌消息的渠道名；服务端在配置/游戏阶段把服务器名发到这里。 */
    public const val BRAND_CHANNEL: String = "minecraft:brand"

    /**
     * 上行 payload 的字节上限。
     *
     * 原版客户端在序列化 `custom_payload`（上行）前会检查这个值；超出直接本地拒绝，
     * 不等服务端踢人。下行没有这个限制——受整个包的大小约束。
     */
    public const val MAX_PAYLOAD_SIZE: Int = 32767

    private const val NAMESPACE_MAX_LENGTH: Int = 64

    /** resource location 的合法字符：命名空间小写字母数字与 `_.-`，路径多一个 `/`。 */
    private val VALID_KEY = Regex("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+")

    /**
     * 校验渠道名并原样返回。
     *
     * 必须是 `namespace:path` 形式：两段都非空、只含小写字母数字与合法标点、命名空间
     * 不超过 64 字符。省略命名空间的缩写（`brand`）在原版里会被补成 `minecraft:brand`，
     * 但插件消息的双方都用完整形式，缩写只会埋下“两边写法不同”的坑，因此这里不放行。
     *
     * @throws IllegalArgumentException 渠道名不是合法的 resource location
     */
    public fun requireValidChannel(channel: String): String {
        require(channel.length <= NAMESPACE_MAX_LENGTH + 1 + Short.MAX_VALUE) {
            "渠道名过长：${channel.length}"
        }
        require(VALID_KEY.matches(channel)) {
            "渠道名必须是 namespace:path 形式的 resource location（小写），实际为 \"$channel\""
        }
        val namespace = channel.substringBefore(':')
        require(namespace.length <= NAMESPACE_MAX_LENGTH) {
            "命名空间超过 $NAMESPACE_MAX_LENGTH 字符：$namespace"
        }
        return channel
    }

    /**
     * 按 `minecraft:brand` 的约定解析品牌串：varint 长度前缀 + UTF-8。
     *
     * 这是字符串编码约定，不是裸字符串——直接 `String(bytes)` 会把前缀字节读进结果。
     * 解析失败（空 payload、前缀越界、长度不符、非法 UTF-8）返回 null 而不是抛错：
     * 收到坏的品牌消息不该影响会话，调用方保留原始消息自行处置。
     */
    public fun parseBrand(data: ByteArray): String? {
        if (data.isEmpty()) return null
        var length = 0
        var shift = 0
        var offset = 0
        while (true) {
            if (offset >= data.size) return null
            val byte = data[offset].toInt() and 0xFF
            offset++
            length = length or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
            if (shift > 28) return null
        }
        if (length < 0 || offset + length > data.size) return null
        // String 构造对非法字节做替换而不是抛错，UTF-8 校验交给需要强保证的调用方。
        return String(data, offset, length, Charsets.UTF_8)
    }
}
