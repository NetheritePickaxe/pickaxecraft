package org.pickaxe.testing.minestom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.instance.InstanceContainer
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.Session
import org.pickaxe.client.SessionState
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.chat.ChatMessage
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * 会话联调用例的共享夹具。
 *
 * 三个测试类按职责分开（生命周期与世界 / 交互 / 社交），但都需要同一套：随机端口、
 * 服务端启停、事件收集器、带诊断的等待工具，以及用例常数。
 *
 * 为什么用抽象基类而不是组合：启动服务端是 `@BeforeAll` / `@AfterAll` 的生命周期钩子，
 * JUnit 只在继承链上发现它们。`forkEvery = 1` 让每个类独占 JVM，因此每类各起一次服务端，
 * 这是拆分带来的固定成本（约 3–5 秒），换取的是失败时能立刻定位到职责范围。
 *
 * 常数放在顶层声明而不是基类 companion：子类可以不加限定地引用，
 * 也不需要为了一个常量继承任何行为。
 */
internal abstract class SessionTestBase {

    protected val port: Int = ServerSocket(0).use { it.localPort }
    protected val address: ServerAddress = ServerAddress(DEFAULT_HOST, port)

    protected lateinit var instance: InstanceContainer

    @BeforeAll
    fun startServer() {
        instance = startHarness(port)
        // 配置阶段就踢人：这条路径不需要玩家出生，能稳定覆盖“服务端主动断开”。
        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            if (event.player.username == KICKED_USERNAME) {
                event.player.kick(Component.text(KICK_MESSAGE))
            }
        }
    }

    @AfterAll
    fun stopServer() {
        stopHarness()
    }
}

internal suspend fun awaitChat(session: Session, predicate: (ChatMessage) -> Boolean): ChatMessage {
    var found: ChatMessage? = null
    awaitCondition("聊天记录中出现满足条件的消息", EVENT_TIMEOUT_MILLIS, session = session) {
        found = session.chat().lastOrNull(predicate)
        found != null
    }
    return assertNotNull(found)
}

/**
 * 服务端 `chat_type` 注册表里的格式 id（包内编码是 id + 1，0 表示内联定义）。
 *
 * 从服务端注册表反查而不是写死下标：条目顺序由服务端数据决定，写死会在
 * 换版本时变成“格式恒为空”的静默失败。
 */
internal fun chatTypeId(translationKey: String): Int {
    val registry = MinecraftServer.getChatTypeRegistry()
    val key = assertNotNull(
        registry.keys().firstOrNull { registry.get(it)?.chat()?.translationKey() == translationKey },
        "服务端 chat_type 注册表应包含 $translationKey",
    )
    return registry.getId(key) + 1
}

internal fun Session.isOpen(): Boolean = state.value != SessionState.CLOSED

internal fun sessionThreads(): List<Thread> =
    Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(SESSION_THREAD_PREFIX) }

/**
 * 轮询等待某个条件成立。
 *
 * 轮询而不是事件驱动：这里等的多是“会话状态/缓存值”这类没有专属事件的结果，
 * 给它们各造一个事件反而会把测试专用需求塞进公共 API。代价是最多 [POLL_INTERVAL_MILLIS]
 * 的延迟，与 10 秒的上限相比可以忽略。
 *
 * [what] 是等待内容的短描述：超时栈帧里只有协程状态机的 `invokeSuspend`，
 * 行号常为 -1，没有描述就无法判断卡在哪一步。超时抛出带诊断的 [AssertionError]：
 * 描述、调用位置、当前会话状态与最近事件。
 */
internal suspend fun awaitCondition(
    what: String,
    timeoutMillis: Long = EVENT_TIMEOUT_MILLIS,
    session: Session? = null,
    events: EventLog? = null,
    condition: () -> Boolean,
) {
    try {
        withTimeout(timeoutMillis) {
            while (!condition()) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError(describeTimeout(what, timeoutMillis, session, events), timeout)
    }
}

/** 超时诊断：等待条件、调用位置、会话状态，以及事件流里最近的若干条事件。 */
internal fun describeTimeout(
    what: String,
    timeoutMillis: Long,
    session: Session?,
    events: EventLog?,
): String = buildString {
    appendLine("等待超时（${timeoutMillis}ms）：$what（${callerLocation()}）")
    if (session == null) {
        appendLine("会话状态：未提供会话")
    } else {
        appendLine("会话状态：${session.state.value}（entityId=${session.selfEntityId}）")
    }
    val recent = events?.snapshot()?.takeLast(15).orEmpty()
    if (recent.isEmpty()) {
        appendLine("最近事件：无（或未提供事件收集器）")
    } else {
        appendLine("最近事件（最多 15 条）：")
        for (event in recent) {
            val text = event.toString()
            appendLine("  - " + if (text.length <= 200) text else text.take(200) + "…")
        }
    }
}

/** 调用位置：跳过本文件的诊断辅助函数，取第一个业务侧栈帧。 */
private fun callerLocation(): String = Throwable().stackTrace
    .firstOrNull {
        it.className.startsWith("org.pickaxe.") && it.className != FIXTURE_FILE_CLASS
    }
    ?.let { "${it.fileName}:${it.lineNumber}" }
    ?: "未知位置"

/** 本文件顶层函数编译出的类名；诊断只需要业务侧的位置。 */
private const val FIXTURE_FILE_CLASS: String = "org.pickaxe.testing.minestom.SessionFixtureKt"

/**
 * 后台收集会话事件。事件流是单消费者，因此只能由它持有。
 *
 * 超时诊断直接读这里的快照：失败时最想知道的是“最后收到了什么”，
 * 而不是再去猜是不是断言写错了。
 */
internal class EventLog(private val scope: CoroutineScope, val session: Session) {

    private val collected = CopyOnWriteArrayList<ClientEvent>()

    private val job = scope.launch { session.events.collect { collected += it } }

    fun snapshot(): List<ClientEvent> = collected.toList()

    suspend fun await(what: String, predicate: (ClientEvent) -> Boolean) {
        try {
            withTimeout(EVENT_TIMEOUT_MILLIS) {
                while (collected.none(predicate)) {
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(describeTimeout(what, EVENT_TIMEOUT_MILLIS, session, this), timeout)
        }
    }

    fun close() {
        job.cancel()
    }
}

// 用例常数：顶层声明，三个测试类都能不加限定地引用。

internal const val KICKED_USERNAME: String = "Kicked"
internal const val WATCHER_USERNAME: String = "Watcher"
internal const val SUBJECT_USERNAME: String = "Subject"
internal const val ZOMBIE_NAME: String = "名牌僵尸"
internal const val RENAMED_ZOMBIE_NAME: String = "改名僵尸"
internal const val KICK_MESSAGE: String = "测试踢出"

/** 聊天用例：正文、名字与编号都由服务端侧写死，客户端只负责读出来。 */
internal const val CHAT_USERNAME: String = "Chatter"
internal const val SYSTEM_CHAT_TEXT: String = "服务器公告"
internal const val ACTION_BAR_TEXT: String = "动作栏提示"
internal const val PLAYER_CHAT_TEXT: String = "你好，世界"
internal const val SENDER_NAME: String = "Alice"
internal const val DISGUISED_TEXT: String = "* Bob 挥了挥手"
internal const val DISGUISED_SENDER: String = "Bob"
internal const val CHAT_FORMAT_KEY: String = "chat.type.text"
internal const val CHAT_SIGNATURE_ID: Int = 7
internal const val SIGNATURE_FILL: Byte = 0x5A
internal val SENDER_UUID: UUID = UUID.fromString("6f2a1c39-0d4f-4a2b-9c8e-1b7d3f5a0e21")

/** 聊天上行用例：正文与命令参数都由服务端侧核对，客户端只负责发。 */
internal const val SAY_USERNAME: String = "SayBot"
internal const val SAY_TEXT: String = "你好，服务端"
internal const val COMMAND_ARGS: String = "hello world"

/** 移动上行用例：目标坐标由客户端给定，服务端侧的 PlayerMoveEvent 是唯一证据。 */
internal const val MOVE_USERNAME: String = "MoveBot"
internal const val MOVE_DISTANCE: Double = 2.0
internal const val LOOK_YAW: Float = 90f
internal const val LOOK_PITCH: Float = 15f

/** 骑乘用例：载具类型与地面高度都由 harness 侧给定，客户端只读结果。 */
internal const val RIDE_USERNAME: String = "RideBot"
internal const val RIDE_GROUND_Y: Double = 40.0

/** 服务端强制传送的目标：与出生点同区块，避开“移动进未加载区块”的分支。 */
internal const val CORRECTION_X: Double = 5.5
internal const val CORRECTION_Z: Double = 1.5

/** 背包用例：容器类型与标题由服务端给出，槽位换算由客户端负责。 */
internal const val BAG_USERNAME: String = "BagBot"
internal const val CHEST_TITLE: String = "测试箱子"
internal const val CHEST_TYPE: String = "GENERIC_9X3"
internal const val HELD_SLOT: Int = 3

/** 点击用例的账号、箱子标题与三个格子上的物品数量。 */
internal const val CLICK_USERNAME: String = "ClickBot"
internal const val CLICK_CHEST_TITLE: String = "点击箱子"
internal const val CLICK_SLOT: Int = 0
internal const val SHIFT_SLOT: Int = 1
internal const val THROW_SLOT: Int = 2
internal const val GOLD_COUNT: Int = 3
internal const val APPLE_COUNT: Int = 2
internal const val DIRT_COUNT: Int = 5

/**
 * 3 行箱子窗口里快捷栏第一格的窗口槽位：27 格容器 + 27 格主背包，之后才是快捷栏。
 *
 * Minestom 的打开容器整包只带容器自己的槽位，所以这一格在快照里并不存在——
 * 但它仍然是合法点击目标，这也正是“容器窗口不校验槽位上限”的原因。
 */
internal const val CHEST_HOTBAR_FIRST_SLOT: Int = 54

/** 自身状态用例：数值由服务端设置，客户端只负责读出来。 */
internal const val STATE_USERNAME: String = "StateBot"
internal const val STATE_HEALTH: Float = 7.5f
internal const val STATE_FOOD: Int = 12
internal const val STATE_SATURATION: Float = 3.5f
internal const val STATE_LEVEL: Int = 5
internal const val STATE_EXPERIENCE_BAR: Float = 0.5f
internal const val EQUIPMENT_HELMET: String = "HELMET"

/** 方块交互用例：挖出生点脚下的那格石头，把服务端发的石头放到旁边两格的顶面。 */
internal const val DIG_USERNAME: String = "DigBot"
internal const val PLACE_USERNAME: String = "PlaceBot"
internal const val PLACE_COUNT: Int = 8

/** 属性用例：自查默认最大生命，然后由 harness 改基值并等事件。 */
internal const val ATTR_USERNAME: String = "AttrBot"
internal const val MAX_HEALTH_KEY: String = "minecraft:max_health"
internal const val MOVE_SPEED_KEY: String = "minecraft:movement_speed"
internal const val DEFAULT_MAX_HEALTH: Double = 20.0
internal const val RAISED_MAX_HEALTH: Double = 40.0
internal const val RAISED_MOVE_SPEED: Double = 1.5

/** 实体交互用例：生成一只僵尸，攻击它、右键它，两侧对账。 */
internal const val MOB_USERNAME: String = "MobBot"
internal const val MOB_ZOMBIE_X: Double = 2.5
internal const val ATTACK_DAMAGE: Double = 3.0

/** 生物血量用例：僵尸改血量，掉落物作为「同下标但不是血量」的反例。 */
internal const val HEALTH_USERNAME: String = "HealthBot"
internal const val HEALTH_ZOMBIE_X: Double = 8.5
internal const val HEALTH_ITEM_X: Double = 9.5
internal const val ZOMBIE_HEALTH: Float = 7.5f

/** 物品键名用例：服务端塞一块带名字的石头与一颗无名钻石。 */
internal const val ITEM_USERNAME: String = "ItemBot"
internal const val NAMED_ITEM_NAME: String = "试金石"

/** 物品组件用例：一把把常用组件设满的钻石剑。 */
internal const val COMPONENT_USERNAME: String = "ComponentBot"

/** 物品原型用例：三件都不设组件的物品，耐久与堆叠上限只能来自客户端原型表。 */
internal const val PROTOTYPE_USERNAME: String = "PrototypeBot"
internal const val PROTOTYPE_SWORD_MAX_DAMAGE: Int = 1561
internal val PROTOTYPE_ITEM_KEYS: Set<String> =
    setOf("minecraft:diamond_sword", "minecraft:stone", "minecraft:ender_pearl")

/** 插件消息用例：自定义渠道逐字节回环，品牌串由 Minestom 在配置阶段下发。 */
internal const val PLUGIN_USERNAME: String = "PluginBot"
internal const val PLUGIN_CHANNEL: String = "pickaxecraft:test"
internal const val HARNESS_BRAND: String = "PickaxeHarness"

/** 拖拽与创造模式用例：石头 id 来自物品表（`minecraft:stone`，与原型表同源）。 */
internal const val CREATIVE_USERNAME: String = "CreativeBot"
internal const val STONE_ITEM_ID: Int = 1
internal const val DRAG_SOURCE_SLOT: Int = 36 // 快捷栏第 0 格（窗口编号）
internal const val DRAG_TARGET_FIRST: Int = 9 // 主背包第一行

/** 运动状态用例：上行包由 PlayerPacketEvent 登记，能力由服务端权威下发。 */
internal const val MOTION_USERNAME: String = "MotionBot"
internal const val ABILITIES_FLYING: Int = 0x02
internal const val ABILITIES_CAN_FLY: Int = 0x04
internal const val ABILITIES_FLY_SPEED: Float = 0.08f

/** 状态效果用例：两个键合并后移除其中一个。 */
internal const val EFFECT_USERNAME: String = "EffectBot"
internal const val SPEED_KEY: String = "minecraft:speed"
internal const val HASTE_KEY: String = "minecraft:haste"
internal const val EFFECT_AMPLIFIER: Int = 1
internal const val EFFECT_DURATION: Int = 200

/** 命令树与补全用例：候选由服务端的回调按前缀过滤。 */
internal const val COMMAND_USERNAME: String = "CommandBot"

/** 转移用例：同名重连，用来验证旧会话真的关掉了。 */
internal const val TRANSFER_USERNAME: String = "TransferBot"

/** 服务端不回包时的等待上限：只为证明“没有答案”不会挂住调用方。 */
internal const val NO_ANSWER_TIMEOUT_MILLIS: Long = 800

/** 容器属性用例：熔炉进度与总时长两个下标。 */
internal const val PROPERTY_USERNAME: String = "PropertyBot"
internal const val FURNACE_PROGRESS: Int = 2
internal const val FURNACE_TOTAL: Int = 3

internal const val EVENT_TIMEOUT_MILLIS: Long = 10_000
internal val MILESTONE_TIMEOUT: Duration = Duration.ofSeconds(10)
internal const val THREAD_RELEASE_TIMEOUT_MILLIS: Long = 5_000
internal const val POLL_INTERVAL_MILLIS: Long = 10
internal const val SESSION_THREAD_PREFIX: String = "pickaxe-mcpl"

/** 破坏进度用例：观察端与被挖的方块，破坏者是出生点附近的僵尸。 */
internal const val DESTRUCTION_USERNAME: String = "DestructionBot"
internal const val DESTRUCTION_X: Int = 8
internal const val DESTRUCTION_Z: Int = 8
internal const val DESTRUCTION_DIGGER_X: Double = 10.5

/** Minestom 默认主世界的垂直范围，harness 没有自定义维度类型。 */
internal const val OVERWORLD_MIN_Y: Int = -64
internal const val OVERWORLD_HEIGHT: Int = 384
internal const val OVERWORLD_SECTIONS: Int = OVERWORLD_HEIGHT / 16

/** 重生用例切到的维度：高度 256、最低层 0，与主世界完全不同。 */
internal const val NETHER_DIMENSION: String = "minecraft:the_nether"
internal const val NETHER_MIN_Y: Int = 0
internal const val NETHER_SECTIONS: Int = 256 / 16

/** 石头平台的顶面高度，玩家与实体都站在这一层。 */
internal const val GROUND_LEVEL: Double = 40.0

/** 区块环境用例：出生前就摆好的箱子、出生后放的箱子、以及火把，都在出生区块内。 */
internal const val ENVIRONMENT_USERNAME: String = "EnvBot"
internal const val INITIAL_CHEST_X: Int = 3
internal const val INITIAL_CHEST_Z: Int = 3
internal const val LIVE_CHEST_X: Int = 5
internal const val LIVE_CHEST_Z: Int = 3
internal const val TORCH_X: Int = 7
internal const val TORCH_Z: Int = 3

/** 高度图用例的列：出生区块内、但没有别的用例在那里改方块。 */
internal const val HEIGHTMAP_X: Int = 9
internal const val HEIGHTMAP_Z: Int = 9

/** 僵尸的生成位置与传送目标；都在出生点所在区块内，不触发额外区块加载。 */
internal const val ZOMBIE_START_X: Double = 3.5
internal const val TELEPORT_X: Double = 6.5

/** 水平速度（格/秒），足以在几百毫秒内跨过一整格。 */
internal const val MOVE_SPEED: Double = 4.0

/**
 * 位置对账容差。
 *
 * 相对移动以 1/4096 格编码，叠加多步后会留下少量量化误差；这个量级远小于
 * 一格，足够区分“真的对上了”和“漏加/重复加了一次增量”。
 */
internal const val POSITION_TOLERANCE: Double = 0.02
