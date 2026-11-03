package org.pickaxe.client

/** 一次会话对外可见的状态。 */
public enum class SessionState {
    /** 已发出连接，尚未进入协议登录阶段。 */
    CONNECTING,

    /** 已进入登录阶段（握手与加密协商）。 */
    LOGIN,

    /** 配置阶段：接收注册表与标签数据。 */
    CONFIGURATION,

    /** 已进入 play，连接保持中。M1 不包含出生与世界同步。 */
    PLAY,

    /** 会话已终止，不会再有后续事件。 */
    CLOSED,
}
