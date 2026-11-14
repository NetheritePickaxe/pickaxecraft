package org.pickaxe.testing.minestom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.crypto.FilterMask
import net.minestom.server.crypto.LastSeenMessages
import net.minestom.server.crypto.MessageSignature
import net.minestom.server.crypto.SignedMessageBody
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.DeleteChatPacket
import net.minestom.server.network.packet.server.play.DisguisedChatPacket
import net.minestom.server.network.packet.server.play.PlayerChatMessagePacket
import net.minestom.server.network.packet.server.play.SystemChatPacket
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.CompletionResult
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.Session
import org.pickaxe.client.SessionState
import org.pickaxe.client.TransferRequest
import org.pickaxe.protocol.chat.ChatKind
import org.pickaxe.protocol.chat.ChatParameter
import org.pickaxe.protocol.command.CommandArgumentProperty
import org.pickaxe.protocol.command.CommandArgumentType
import org.pickaxe.protocol.command.CommandNodeKind
import org.pickaxe.protocol.command.CommandTextMode
import java.time.Duration
import java.time.Instant
import java.util.BitSet
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 社交与连接层：聊天读写、命令树与补全、跨服转移、插件消息。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SessionSocialTest : SessionTestBase() {

    @Test
    fun `聊天消息可读且能按编号删除`() = runBlocking {
        // 三个来源包字段各不相同：系统消息没有发送者，玩家消息带 UUID 与签名编号，
        // 伪装消息有名字但没有 UUID。这里逐项对账，重点是别把它们混成一种东西。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, CHAT_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("聊天消息可读且能按编号删除") { it is ClientEvent.ChunksReceived && it.total > 0 }
                val player = assertNotNull(
                    MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(CHAT_USERNAME),
                    "服务端应已保存该玩家",
                )
                val typeId = chatTypeId(CHAT_FORMAT_KEY)

                // 系统消息；动作栏与聊天栏走同一个包，只差 overlay 位。
                player.sendMessage(Component.text(SYSTEM_CHAT_TEXT))
                player.sendPacket(SystemChatPacket(Component.text(ACTION_BAR_TEXT), true))

                // 玩家聊天：签名、消息编号、格式引用都在这一个包里。
                player.sendPacket(
                    PlayerChatMessagePacket(
                        12,
                        SENDER_UUID,
                        CHAT_SIGNATURE_ID,
                        MessageSignature(ByteArray(256) { SIGNATURE_FILL }),
                        SignedMessageBody.Packed(
                            PLAYER_CHAT_TEXT,
                            Instant.now(),
                            42L,
                            LastSeenMessages.Packed.EMPTY,
                        ),
                        null,
                        FilterMask(FilterMask.Type.PASS_THROUGH, BitSet()),
                        typeId,
                        Component.text(SENDER_NAME),
                        null,
                    ),
                )

                // 伪装聊天：服务端借某人的名义说话，没有 UUID 与签名。
                player.sendPacket(
                    DisguisedChatPacket(
                        Component.text(DISGUISED_TEXT),
                        typeId,
                        Component.text(DISGUISED_SENDER),
                        null,
                    ),
                )

                log.await("聊天消息可读且能按编号删除") { it is ClientEvent.ChatReceived && it.message.text == SYSTEM_CHAT_TEXT }
                val system = awaitChat(session) { it.text == SYSTEM_CHAT_TEXT }
                assertEquals(ChatKind.SYSTEM, system.kind)
                assertEquals(false, system.overlay, "普通系统消息显示在聊天栏")
                assertNull(system.sender, "系统消息没有发送者身份")

                val actionBar = awaitChat(session) { it.text == ACTION_BAR_TEXT }
                assertEquals(true, actionBar.overlay, "overlay 位应区分动作栏")

                val chat = awaitChat(session) { it.text == PLAYER_CHAT_TEXT }
                assertEquals(ChatKind.PLAYER, chat.kind)
                assertEquals(SENDER_UUID, chat.sender, "玩家消息带发送者 UUID")
                assertEquals(SENDER_NAME, chat.senderName)
                assertEquals(CHAT_SIGNATURE_ID, chat.signatureId, "带签名的消息应保留编号")
                assertNotNull(chat.timestamp, "玩家消息带签名时间")
                // 格式来自 chat_type 注册表：键与参数顺序都应与服务端一致。
                assertEquals(CHAT_FORMAT_KEY, chat.format?.key)
                assertEquals(
                    listOf(ChatParameter.SENDER, ChatParameter.CONTENT),
                    chat.format?.parameters,
                    "普通聊天的模板是「发送者 + 正文」",
                )

                val disguised = awaitChat(session) { it.text == DISGUISED_TEXT }
                assertEquals(ChatKind.DISGUISED, disguised.kind)
                assertEquals(DISGUISED_SENDER, disguised.senderName)
                assertNull(disguised.sender, "伪装消息只有名字，没有 UUID")
                assertEquals(CHAT_FORMAT_KEY, disguised.format?.key)

                assertEquals(
                    listOf(SYSTEM_CHAT_TEXT, ACTION_BAR_TEXT, PLAYER_CHAT_TEXT, DISGUISED_TEXT),
                    session.chat().map { it.text },
                    "聊天记录按到达顺序保存",
                )

                // 删除只对带编号的消息生效：服务端撤回后记录里不该再有它。
                player.sendPacket(DeleteChatPacket(MessageSignature.Packed(CHAT_SIGNATURE_ID, null)))
                log.await("聊天消息可读且能按编号删除") { it is ClientEvent.ChatDeleted && it.signatureId == CHAT_SIGNATURE_ID }
                awaitCondition("聊天消息可读且能按编号删除", EVENT_TIMEOUT_MILLIS) {
                    session.chat().none { it.signatureId == CHAT_SIGNATURE_ID }
                }
                assertEquals(
                    listOf(SYSTEM_CHAT_TEXT, ACTION_BAR_TEXT, DISGUISED_TEXT),
                    session.chat().map { it.text },
                    "只删除被引用的那条，其余保持原顺序",
                )
            } finally {
                log.close()
                session.close()
            }

            assertEquals(0, session.chatCount, "关闭后不应继续持有聊天记录")
        } finally {
            client.close()
        }
    }

    @Test
    fun `发送的聊天与命令服务端可见`() = runBlocking {
        // 上行有两条独立的证据链：服务端收到原文（PlayerChatEvent / 命令执行器），
        // 以及消息被广播回来。只断言回显会漏掉“服务端其实没收到、回显是本端插的”这种情况。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, SAY_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("发送的聊天与命令服务端可见") { it is ClientEvent.ChunksReceived && it.total > 0 }

                session.sendChat(SAY_TEXT)
                assertTrue(
                    withContext(Dispatchers.IO) { awaitPlayerChat(SAY_USERNAME, SAY_TEXT, MILESTONE_TIMEOUT) },
                    "服务端应收到聊天原文：$SAY_TEXT",
                )

                // 广播回来的那条才是服务端认可的版本；本端不在本地插入副本。
                // Minestom 用系统消息广播玩家聊天，因此这里是 SYSTEM 而不是 PLAYER；
                // harness 把默认的可翻译模板换成了字面文本，回显才能与正文对账。
                val expectedEcho = "$SAY_USERNAME: $SAY_TEXT"
                log.await("发送的聊天与命令服务端可见") { it is ClientEvent.ChatReceived && it.message.text == expectedEcho }
                val echoed = awaitChat(session) { it.text == expectedEcho }
                assertEquals(ChatKind.SYSTEM, echoed.kind, "Minestom 以系统消息广播玩家聊天")

                // 命令：调用方写前导斜杠，包里不带；带空格的参数必须原样到达服务端。
                session.sendCommand("/$ECHO_COMMAND_NAME $COMMAND_ARGS")
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitCommand(SAY_USERNAME, "$ECHO_COMMAND_NAME $COMMAND_ARGS", MILESTONE_TIMEOUT)
                    },
                    "服务端应执行 echo 命令并看到完整参数",
                )
                val reply = awaitChat(session) { it.text == ECHO_COMMAND_REPLY }
                assertEquals(ChatKind.SYSTEM, reply.kind, "命令输出以系统消息回到本端")

                // 本地校验失败不应把会话打断：这些消息一个字节都不该发出去。
                assertFailsWith<IllegalArgumentException> { session.sendChat("x".repeat(257)) }
                assertFailsWith<IllegalArgumentException> { session.sendCommand("/") }
                assertEquals(SessionState.PLAY, session.state.value, "校验失败后会话应仍在 play")
            } finally {
                log.close()
                session.close()
            }

            // 关闭后发送被拒绝：连接已回收，写包只会进待发队列。
            val rejected = assertFailsWith<IllegalStateException> { session.sendChat("closed") }
            assertTrue(
                "CLOSED" in rejected.message.orEmpty() && "聊天" in rejected.message.orEmpty(),
                "拒绝原因应说明当前状态与操作，实际为 ${rejected.message}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `命令树与补全由服务端给出`() = runBlocking {
        // 命令树与候选都只能由服务端决定：前者随 Player#spawn 下发，后者由服务端按权限与
        // 当前输入算。客户端能证明的是“包到了、形状对、区间落在该换的那一段上”，
        // 服务端侧另有补全请求原文作为独立证据。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, COMMAND_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("命令树与补全由服务端给出") { it is ClientEvent.ChunksReceived && it.total > 0 }

                log.await("命令树与补全由服务端给出") { it is ClientEvent.CommandTreeReceived }
                val tree = withTimeout(EVENT_TIMEOUT_MILLIS) { session.commands.first { it != null } }!!
                assertTrue(
                    SUGGEST_COMMAND_NAME in tree.topLevelLiterals,
                    "harness 注册的命令应出现在树里，实际为 ${tree.topLevelLiterals}",
                )

                // 逐层走一遍：命令名下面是唯一的参数节点，类型与文本模式都要对得上。
                val command = tree.childrenOf(tree.rootIndex).first { it.name == SUGGEST_COMMAND_NAME }
                assertTrue(command.executable, "带默认执行器的命令应当可执行")
                val argument = tree.childrenOf(command.index).single()
                assertEquals(CommandNodeKind.ARGUMENT, argument.kind)
                assertEquals(CommandArgumentType.STRING, argument.argumentType)
                assertEquals(
                    CommandArgumentProperty.Text(CommandTextMode.SINGLE_WORD),
                    argument.property,
                    "ArgumentType.Word 在协议里是 string + 单词模式",
                )

                // 补全：候选由服务端的回调按前缀过滤，所以只有 red 回来。
                val text = "/$SUGGEST_COMMAND_NAME r"
                val result = session.complete(text)
                val suggestions = assertIs<CompletionResult.Suggestions>(result).suggestions
                assertEquals(listOf("red"), suggestions.matches, "候选由服务端过滤，不该是全部三个")

                // 区间说的就是“把这几个字符换掉”：取出来必须正好是已输入的前缀。
                val replaced = text.substring(suggestions.start, suggestions.start + suggestions.length)
                assertEquals("r", replaced, "替换区间应指向最后一个词")
                assertEquals("/$SUGGEST_COMMAND_NAME red", suggestions.applyTo(text), "套回原文应得到完整命令")

                // 事件与挂起 API 是同一份数据；不想用挂起接口的调用方可以只看事件。
                log.await("命令树与补全由服务端给出") { it is ClientEvent.CommandSuggestionsReceived }

                // 服务端确实收到了这次请求：包里的原文就是证据（补全包本身没有其他回执）。
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitPlayerPacket(COMMAND_USERNAME, "suggest.$text", MILESTONE_TIMEOUT)
                    },
                    "服务端应收到补全请求：$text",
                )

                // 选中的候选可以直接下发执行，服务端侧按真实命令跑一遍。
                session.sendCommand("/$SUGGEST_COMMAND_NAME red")
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitCommand(COMMAND_USERNAME, "/$SUGGEST_COMMAND_NAME red", MILESTONE_TIMEOUT)
                    },
                    "补全出来的命令应能在服务端执行",
                )

                // 没有补全回调的命令服务端**不回包**：这是“没有答案”，不是“没有候选”。
                // 超时刻意压短，避免把用例拖成十秒。
                assertEquals(
                    CompletionResult.NoAnswer,
                    session.complete("/$ECHO_COMMAND_NAME hi", timeoutMillis = NO_ANSWER_TIMEOUT_MILLIS),
                    "服务端不回包时应报告 NoAnswer",
                )
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `转移请求只上报地址，换服由调用方决定`() = runBlocking {
        // 转移是一条“去连那个地址”的指令，不是连接状态的变化：协议库的自动跟随已关闭，
        // 收到包之后本会话必须原地不动。换服是一条新连接，必须是调用方的显式动作——
        // 否则本库会替调用方建一条它管不到的会话（M1 踩过的坑，见 protocol-contract 2.7）。
        val client = MinecraftClient()
        try {
            val session = client.connect(address, TRANSFER_USERNAME)
            val log = EventLog(this, session)
            var next: Session? = null
            try {
                log.await("转移请求只上报地址，换服由调用方决定") { it is ClientEvent.ChunksReceived && it.total > 0 }

                assertTrue(
                    sendTransfer(TRANSFER_USERNAME, DEFAULT_HOST, port),
                    "服务端应能找到该玩家",
                )

                log.await("转移请求只上报地址，换服由调用方决定") { it is ClientEvent.TransferRequested }
                val event = log.snapshot().filterIsInstance<ClientEvent.TransferRequested>().single()
                assertEquals(DEFAULT_HOST, event.host)
                assertEquals(port, event.port, "端口原样转达，不做补全")
                // 坏地址只在真正要连的时候才报错：收包阶段不该因为一个坏包结束会话。
                assertFailsWith<IllegalArgumentException> { TransferRequest(DEFAULT_HOST, 0).address }
                assertEquals(
                    TransferRequest(DEFAULT_HOST, port),
                    session.transferRequest.value,
                    "转移目标要能从状态里读到，晚订阅的调用方不必依赖事件",
                )

                // 关键断言：库没有替调用方换服。会话还是同一个、还在 play，客户端也仍持有它。
                delay(200)
                assertEquals(SessionState.PLAY, session.state.value, "收到转移请求不应改变会话状态")

                // 真正的换服：关旧会话 → 连新地址 → 返回新会话。
                // 目标刻意指向同一个服务端，这样“能连上、能进世界”是可验证的，
                // 而不需要在一个 JVM 里跑第二个 Minestom（它是进程级单例）。
                next = client.followTransfer(session)
                assertEquals(DEFAULT_HOST, next.address.host)
                assertEquals(port, next.address.port)
                assertEquals(SessionState.PLAY, next.state.value, "新会话必须真的进入 play")
                assertEquals(SessionState.CLOSED, session.state.value, "旧会话应当已经关闭")

                // 转移目标不随会话结束清空：事后核对“为什么断开”靠它，
                // 而不是靠断开的文本原因（那只是一句翻译键）。
                assertEquals(TransferRequest(DEFAULT_HOST, port), session.transferRequest.value)

                // 新会话是完整会话而不是半截连接：能重新收到区块。
                val nextLog = EventLog(this, next)
                try {
                    nextLog.await("新会话应重新收到区块") { it is ClientEvent.ChunksReceived }
                } finally {
                    nextLog.close()
                }
            } finally {
                log.close()
                next?.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `插件消息逐字节回环且品牌可读`() = runBlocking {
        val client = MinecraftClient()
        try {
            val session = client.connect(address, PLUGIN_USERNAME)
            val log = EventLog(this, session)
            try {
                log.await("插件消息逐字节回环且品牌可读") { it is ClientEvent.ChunksReceived && it.total > 0 }
                awaitCondition("插件消息逐字节回环且品牌可读", EVENT_TIMEOUT_MILLIS) { session.world.value?.position != null }

                // Minestom 在配置阶段就把品牌串发到 minecraft:brand；进 play 时应该已经能读到。
                awaitCondition("插件消息逐字节回环且品牌可读", EVENT_TIMEOUT_MILLIS) { session.serverBrand.value != null }
                assertEquals("Minestom", session.serverBrand.value, "品牌串应来自配置阶段的插件消息")

                // 下行：服务端发自定义渠道，客户端收到的字节必须逐字节相同。
                // payload 里放 0x00 与高位字节，防「能过字符串通道但过不了字节通道」的假阳性。
                val payload = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x42)
                assertTrue(
                    withContext(Dispatchers.IO) {
                        sendPluginMessage(PLUGIN_USERNAME, PLUGIN_CHANNEL, payload)
                    },
                    "服务端应找到该玩家并下发插件消息",
                )
                log.await("插件消息逐字节回环且品牌可读") { it is ClientEvent.PluginMessageReceived && it.message.channel == PLUGIN_CHANNEL }
                val received = log.snapshot()
                    .filterIsInstance<ClientEvent.PluginMessageReceived>()
                    .first { it.message.channel == PLUGIN_CHANNEL }
                assertContentEquals(payload, received.message.data(), "下行的 payload 应逐字节相同")

                // 上行：客户端原样发回，服务端在 PlayerPluginMessageEvent 里看到同一份字节。
                session.sendPluginMessage(PLUGIN_CHANNEL, payload)
                assertTrue(
                    withContext(Dispatchers.IO) {
                        awaitPlayerPluginMessage(PLUGIN_USERNAME, Duration.ofSeconds(10)) {
                            it.channel == PLUGIN_CHANNEL && it.data.contentEquals(payload)
                        }
                    },
                    "服务端应收到客户端发回的同一份 channel 与字节",
                )

                // 品牌串是普通插件消息的特例：服务端改发一次，客户端的解析必须跟着更新。
                assertTrue(
                    withContext(Dispatchers.IO) { sendBrand(PLUGIN_USERNAME, HARNESS_BRAND) },
                    "服务端应能改发品牌串",
                )
                awaitCondition("插件消息逐字节回环且品牌可读", EVENT_TIMEOUT_MILLIS) {
                    session.serverBrand.value == HARNESS_BRAND
                }
            } finally {
                log.close()
                session.close()
            }
        } finally {
            client.close()
        }
    }
}
