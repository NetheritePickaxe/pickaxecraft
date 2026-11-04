package org.pickaxe.protocol.internal

/**
 * 聊天与命令上行的输入校验。
 *
 * 校验放在适配层而不是调用方：长度上限由协议决定（服务端用同一个数字拒绝），
 * 换行则是本层额外的限制——协议允许字符串里带换行，但下游都是按单行渲染，
 * 一条带换行的消息会在任何朴素实现里变成两行，等于让对端伪造出一行自己没说过的话。
 *
 * 这里只做“能不能发”的判断，不做改写：发送的文本与调用方给的一致，不 trim、
 * 不做命令补全、不处理 `@` 之类的语义。
 */
internal object ChatInput {

    /** 26.2 的聊天与命令长度上限，超出后服务端会以 `message too long` 断开。 */
    const val MAX_LENGTH: Int = 256

    /**
     * 校验一条聊天消息并原样返回。
     *
     * @throws IllegalArgumentException 内容为空、只有空白、含换行或超过 [MAX_LENGTH] 个字符
     */
    fun message(text: String): String {
        require(text.isNotEmpty()) { "聊天内容不能为空" }
        require(text.isNotBlank()) { "聊天内容不能只有空白字符" }
        requireLength(text, "聊天内容")
        require(!text.contains('\n') && !text.contains('\r')) { "聊天内容不能包含换行" }
        return text
    }

    /**
     * 校验一条命令，去掉允许出现的前导斜杠后返回。
     *
     * 包里的命令**不带**斜杠，调用方按习惯写 `/help` 时由这里剥掉；
     * 剥离只做一次，`//help` 会被当成名为 `/help` 的命令原样发出。
     *
     * @throws IllegalArgumentException 去掉斜杠后为空、只有空白或超过 [MAX_LENGTH] 个字符
     */
    fun command(command: String): String {
        val stripped = command.removePrefix("/")
        require(stripped.isNotEmpty()) { "命令不能为空" }
        require(stripped.isNotBlank()) { "命令不能只有空白字符" }
        requireLength(stripped, "命令")
        require(!stripped.contains('\n') && !stripped.contains('\r')) { "命令不能包含换行" }
        return stripped
    }

    /** 长度按字符（码点之前的 UTF-16 单元）计，与协议写入的字符串长度口径一致。 */
    private fun requireLength(value: String, what: String) {
        require(value.length <= MAX_LENGTH) { "${what}最长 $MAX_LENGTH 个字符，实际为 ${value.length}" }
    }
}
