package org.pickaxe.protocol

/**
 * 服务端地址。
 *
 * M1 只支持直接给出 host 与 port：SRV 记录解析会引入一次额定的 DNS 查询，
 * 且对本地联调没有价值，因此在适配层显式关闭（见 [ProtocolConnection] 的实现）。
 */
public data class ServerAddress(
    public val host: String,
    public val port: Int = DEFAULT_PORT,
) {
    init {
        require(host.isNotBlank()) { "host 不能为空" }
        require(port in 1..65535) { "port 必须在 1..65535 之间，实际为 $port" }
    }

    public companion object {
        /** Minecraft Java 版默认端口。 */
        public const val DEFAULT_PORT: Int = 25565
    }
}
