package org.pickaxe.client

import org.pickaxe.protocol.ServerAddress

/**
 * 服务端要求本端转移到的目标。
 *
 * [host] / [port] 原样保留服务端给的值，不做补全或改写：转移地址是服务端的事实，
 * 本层只负责转达。目标是否合法由 [address] 在真正要用的时候判定——把校验放在
 * 收到包的位置，一个坏包就会结束整个会话。
 */
public data class TransferRequest(
    public val host: String,
    public val port: Int,
) {
    /** 转成可连接的地址；host 为空或端口越界时抛出 [IllegalArgumentException]。 */
    public val address: ServerAddress
        get() = ServerAddress(host, port)

    override fun toString(): String = "$host:$port"
}
