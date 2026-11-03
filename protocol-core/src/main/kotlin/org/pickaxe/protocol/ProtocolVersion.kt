package org.pickaxe.protocol

/**
 * 协议版本事实来源。
 *
 * 数字由 MCProtocolLib 26.2 决定，本仓库不再自己维护 schema：
 * [org.pickaxe.protocol.ProtocolVersionTest] 直接读取库内 codec 常量做交叉校验，
 * 写错会立刻被测试打回，而不是等到联调才发现。
 */
public object ProtocolVersion {
    /** 锁定的 Minecraft 版本。 */
    public const val MINECRAFT_VERSION: String = "26.2"

    /** 锁定的协议号，必须与服务端（Minestom）保持一致。 */
    public const val PROTOCOL_VERSION: Int = 776
}
