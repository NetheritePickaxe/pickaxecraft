package org.pickaxe.client

/** 一次会话对外可见的状态。 */
public enum class SessionState {
    /** 已发出连接，尚未进入协议登录阶段。 */
    CONNECTING,

    /** 已进入登录阶段（握手与加密协商）。 */
    LOGIN,

    /** 配置阶段：接收注册表与标签数据。 */
    CONFIGURATION,

    /**
     * 已进入 play，连接保持中。
     *
     * 出生过程中的位置同步与区块接收由协议层自动推进，进度见 [Session.world]；
     * 这不改变状态本身——协议状态始终是 play。
     */
    PLAY,

    /** 会话已终止，不会再有后续事件。 */
    CLOSED,
}
