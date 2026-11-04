package org.pickaxe.protocol.chat

import java.util.UUID

/** 一条消息从哪来。协议用三个不同的包表达这三类，本层把它们收敛成同一种事实。 */
public enum class ChatKind {
    /** 服务端自己发的系统消息：公告、命令反馈、登录提示。没有发送者身份。 */
    SYSTEM,

    /**
     * 玩家通过聊天框发送的消息，带签名与发送者 UUID。
     *
     * 签名是安全聊天的凭据，本层只保留消息编号用于删除（见 [ChatMessage.signatureId]），
     * 不校验也不保存签名本身：无头客户端不发消息，没有可被冒用的会话。
     */
    PLAYER,

    /** 伪装成聊天的服务端消息（`/say`、`/me`、控制台广播）：有发送者名字，但没有 UUID 与签名。 */
    DISGUISED,
}

/** 聊天格式里的占位符，决定一条消息用什么填充模板。 */
public enum class ChatParameter {
    /** 消息正文。 */
    CONTENT,

    /** 发送者显示名。 */
    SENDER,

    /** 接收者显示名，私聊时才有。 */
    TARGET,
}

/**
 * 消息的展示格式，来自 `minecraft:chat_type` 注册表。
 *
 * 协议不传拼好的整行文本，而是传「模板键 + 参数顺序」：普通聊天是
 * `chat.type.text`（`<发送者> 正文`），表情是 `chat.type.emote`（`* 发送者 正文`）。
 * 模板文本在客户端语言文件里，不在协议里——所以本层给出键与参数，由下游决定怎么排版。
 */
public data class ChatFormat(
    /** 翻译键，例如 `chat.type.text`。 */
    public val key: String,
    /** 模板用到哪些填充值，顺序与模板一致。 */
    public val parameters: List<ChatParameter>,
)

/**
 * 一条已渲染成纯文本的聊天消息。
 *
 * 三个来源包的字段并不一致，本层补齐成一种形态：系统消息只有正文，玩家消息多出
 * 发送者与签名编号，伪装消息多出名字但没有 UUID。因此 [sender] 与 [senderName]
 * 都可能为空，判断“谁说的”要用 [sender]，判断“显示什么名字”用 [senderName]。
 *
 * 文本已经由协议层渲染成纯文本：颜色、粗体、点击事件等样式**不保留**。
 * 这些样式属于展示层，而本库不引入渲染依赖；需要样式的下游应当自己解码组件。
 */
public data class ChatMessage(
    /** 消息来源。 */
    public val kind: ChatKind,
    /** 正文纯文本。玩家消息优先取未签名内容，它可能带服务端改写过的格式。 */
    public val text: String,
    /** 发送者 UUID；只有 [ChatKind.PLAYER] 有。 */
    public val sender: UUID? = null,
    /** 发送者显示名，已渲染成纯文本。 */
    public val senderName: String? = null,
    /** 接收者显示名，私聊才有。 */
    public val targetName: String? = null,
    /** 展示格式；注册表里查不到时为 null，此时按 `发送者: 正文` 之类的朴素方式显示。 */
    public val format: ChatFormat? = null,
    /** 服务端签名这条消息的时刻（epoch 毫秒）；只有玩家消息有。 */
    public val timestamp: Long? = null,
    /** 是否显示在物品栏上方的覆盖层（动作栏）而不是聊天栏。只有系统消息会有。 */
    public val overlay: Boolean = false,
    /**
     * 服务端为这条消息分配的编号；只有带签名的玩家消息有。
     *
     * `delete_chat` 用同一个编号回收消息，本层据此从聊天记录里移除它。
     */
    public val signatureId: Int? = null,
)
