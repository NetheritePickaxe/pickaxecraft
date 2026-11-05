package org.pickaxe.protocol

import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.cloudburstmc.math.vector.Vector3d
import org.pickaxe.protocol.entity.EntityAttribute
import org.pickaxe.protocol.entity.MobEffect
import org.pickaxe.protocol.entity.MovementInput
import org.pickaxe.protocol.internal.ChatInput
import org.pickaxe.protocol.internal.DigAction
import org.pickaxe.protocol.internal.McplConnection
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.Hand
import org.pickaxe.protocol.inventory.PlayerInventoryLayout
import org.pickaxe.protocol.inventory.SlotClick
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition

/**
 * 一条协议连接的句柄。
 *
 * 创建即分配好协议状态，[connect] 才会真正发起网络连接；[close] 是幂等的，
 * 会释放该连接独占的网络线程。上层拿不到 `Session`、`Packet` 或 `ByteBuf`。
 *
 * 订阅关系在创建时确定：事件只发给构造时传入的 [ProtocolListener]，且回调发生在
 * 该连接自己的网络线程上。
 */
public class ProtocolConnection private constructor(
    private val connection: McplConnection,
) : AutoCloseable {

    /** 发起连接。只允许调用一次，网络结果通过事件报告，不会阻塞调用线程。 */
    public fun connect() {
        connection.start()
    }

    /** 关闭连接并回收网络线程。重复调用无副作用。 */
    override fun close() {
        connection.shutdown()
    }

    /** 是否已经进入关闭流程。 */
    public val isClosed: Boolean
        get() = connection.isShutDown

    /**
     * 发送一条聊天消息。
     *
     * 消息不带签名：离线模式的服务端（`enforce-secure-profile=false`）可以直接接收，
     * 这也是本库当前支持的唯一场景；启用安全聊天的在线服务端需要会话密钥，属于 M5。
     *
     * 发送不与收包同步：服务端把消息广播回来之后，同一文本会作为
     * [ProtocolEvent.ChatReceived] 再出现一次，包括发送者自己发的那条。本端不在本地插入，
     * 因为服务端可能过滤、改写或拒收——本地插入会让“显示过”与“服务端认可”不一致。
     *
     * 调用线程不限；同一线程内的多次调用按调用顺序发出。
     *
     * @throws IllegalArgumentException 内容为空、只有空白、含换行或超过 256 个字符
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun sendChat(message: String) {
        connection.sendChat(ChatInput.message(message))
    }

    /**
     * 发送一条命令，例如 `sendCommand("/help")`。
     *
     * 前导斜杠可写可不写，包里的命令不带斜杠。命令包不参与聊天签名，因此在启用安全聊天的
     * 服务端上同样可用（能否执行仍取决于玩家权限）。
     *
     * @throws IllegalArgumentException 去掉斜杠后为空、只有空白、含换行或超过 256 个字符
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun sendCommand(command: String) {
        connection.sendCommand(ChatInput.command(command))
    }

    /**
     * 请求一次命令补全，[transactionId] 由调用方分配并在回答里原样带回。
     *
     * 这是上行里唯一**有回执**的请求：服务端要么回 [ProtocolEvent.CommandSuggestionsReceived]，
     * 要么什么都不发（算不出候选时）。编号在一个连接内唯一即可，并发请求靠它配对。
     *
     * [text] 是完整输入行（含前导斜杠）：服务端按自己解析出的位置决定替换区间。
     *
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun requestCommandSuggestions(transactionId: Int, text: String) {
        connection.requestCommandSuggestions(transactionId, text)
    }

    /**
     * 把本端位置与朝向声明给服务端。
     *
     * 只发送与上一状态不同的分量（协议里 `pos`、`rot`、`pos_rot` 是三个包）；坐标与朝向
     * 都没变且落地标记也没变时什么也不发。
     *
     * 服务端**接受**移动时不回包，因此这条调用不产生“确认”事件；服务端不同意时下发位置
     * 同步，表现为 [ProtocolEvent.PositionUpdated]。本端在发送后立即把自己记录的位置推进到
     * 目标值，并上报 [ProtocolEvent.PositionSent]。
     *
     * 调用线程不限；同一线程内的多次调用按调用顺序发出。
     *
     * @throws IllegalArgumentException 任一坐标为 NaN 或无穷
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun moveTo(
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
        onGround: Boolean = false,
    ) {
        connection.moveTo(x, y, z, yaw, pitch, onGround)
    }

    /**
     * 只改朝向，位置不变。
     *
     * 协议里的 `rot` 包不带坐标，因此这条路径不要求已经收到过位置同步。
     *
     * @throws IllegalArgumentException 任一角度为 NaN 或无穷
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun look(yaw: Float, pitch: Float, onGround: Boolean = false) {
        connection.look(yaw, pitch, onGround)
    }

    /**
     * 声明载具移动到了指定位置与朝向。
     *
     * 坐标是绝对量；包不带实体 id，只对「当前乘坐的载具」成立。
     *
     * @throws IllegalArgumentException 坐标或角度非法
     * @throws IllegalStateException 当前不在载具上，或尚未进入 play
     */
    public fun steerVehicle(
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
        onGround: Boolean = false,
    ) {
        connection.steerVehicle(x, y, z, yaw, pitch, onGround)
    }

    /**
     * 切换快捷栏选中格（0–8）。
     *
     * 本端不预设结果：服务端会用 `set_held_slot` 回一条，表现为
     * [ProtocolEvent.HeldSlotChanged]；被插件取消时回的是旧值。
     *
     * @throws IllegalArgumentException 下标不在 0..8 之间
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun selectHotbarSlot(slot: Int) {
        require(slot in 0 until PlayerInventoryLayout.HOTBAR_SIZE) {
            "快捷栏下标应在 0..${PlayerInventoryLayout.HOTBAR_SIZE - 1} 之间，实际为 $slot"
        }
        connection.selectHotbarSlot(slot)
    }

    /**
     * 点击一个窗口槽位，例如把箱子里的物品拿到光标上、再放进背包。
     *
     * [container] 必须是本端持有的那一份快照（[ProtocolEvent.ContainerOpened] /
     * [ProtocolEvent.ContainerUpdated] 里给的对象）：包里的窗口 id 与内容状态号都从它取，
     * 服务端据此判断本端看到的是不是最新内容。传一份过期的快照不会写坏数据——服务端会发现
     * 不一致，并把权威内容整包下发。
     *
     * **不做本地预测**：调用后本端内容不变，等服务端的 `container_set_content` /
     * `container_set_slot` / `set_cursor_item` 到达后才是新状态，表现为
     * [ProtocolEvent.ContainerUpdated]。因此“点了但还没生效”是正常现象，不要据此判断失败。
     *
     * 调用线程不限；同一线程内的多次调用按调用顺序发出。
     *
     * @throws IllegalArgumentException 槽位为负，或玩家背包窗口的槽位超过 46
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun clickSlot(container: Container, slot: Int, click: SlotClick) {
        connection.clickSlot(container, slot, click)
    }

    /**
     * 关闭一个已打开的窗口（窗口 0 会被忽略）。
     *
     * 客户端主动关闭时服务端**不回包**——只有服务端自己关窗才会下发 `container_close`。
     * 因此这条调用在本地立即收敛：窗口从缓存移除并上报 [ProtocolEvent.ContainerClosed]。
     * 已经搬进背包的物品不受影响；关窗时光标上还剩的物品由服务端决定去处（丢到地上或塞回背包），
     * 表现为随后的背包内容更新。
     *
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun closeContainer(windowId: Int) {
        connection.closeContainer(windowId)
    }

    /**
     * 某实体的属性表快照（键名 → [EntityAttribute]）；没有收到过就是空表。
     *
     * 服务端每次只下发改动的属性，这里返回的是**按键合并后**的累积结果。
     * 本端自己的属性也在其中：属性变化时服务端会直接推给本人。
     */
    public fun attributes(entityId: Int): Map<String, EntityAttribute> = connection.attributes(entityId)

    /** 按 [key]（如 `minecraft:max_health`）查单个属性；没有收到过就是 null。 */
    public fun attribute(entityId: Int, key: String): EntityAttribute? =
        connection.attributes(entityId)[key]

    /**
     * 整份声明运动按键（前进/后退/左右/跳跃/潜行/冲刺）。
     *
     * `player_input` 是状态包，服务端按最后一次收到的字节为准；本方法每次都把七个布尔位
     * 整份重发。没有回执，发送后本端状态立即推进。
     *
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun setMovementInput(input: MovementInput) {
        connection.setMovementInput(input)
    }

    /** 切换潜行（shift 位）。 */
    public fun setSneaking(enabled: Boolean) {
        connection.setSneaking(enabled)
    }

    /**
     * 切换冲刺：同时发 `player_input` 的 sprint 位与独立的 `player_command`。
     *
     * @throws IllegalStateException 尚未收到 Login 包（拿不到本端实体 id），或未进入 play
     */
    public fun setSprinting(enabled: Boolean) {
        connection.setSprinting(enabled)
    }

    /**
     * 请求开始/停止飞行。
     *
     * 成功没有回执：服务端若允许，会下发 `player_abilities`，表现为
     * [ProtocolEvent.AbilitiesUpdated]；拒绝时则什么都不发。
     */
    public fun setFlying(enabled: Boolean) {
        connection.setFlying(enabled)
    }

    /** 本端最后一次声明的运动按键。 */
    public fun movementInput(): MovementInput = connection.movementInput()

    /** 某实体当前的状态效果表（按键合并后的快照）。 */
    public fun effects(entityId: Int): Map<String, MobEffect> = connection.effects(entityId)

    /** 某窗口当前的数值属性表（原始下标 → 值；含义取决于容器类型）。 */
    public fun containerProperties(windowId: Int): Map<Int, Int> =
        connection.containerProperties(windowId)

    /**
     * 攻击一个实体。
     *
     * 攻击没有回执包：目标不在视野里时服务端静默忽略；打中了会看到
     * [ProtocolEvent.Damaged]（伤害事件）与实体状态变化。
     *
     * @param swing 是否跟随一次挥手动画（原版客户端攻击时总是挥手）
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun attack(entityId: Int, swing: Boolean = true) {
        connection.attack(entityId, swing)
    }

    /** 挥动一只手。只是视觉表现，与攻击判定无关。 */
    public fun swing(hand: Hand = Hand.MAIN_HAND) {
        connection.swing(hand)
    }

    /**
     * 与实体交互（右键；如对村民交易、给生物喂食——取决于服务端玩法）。
     *
     * @param sneaking 是否按住潜行声明；本库不跟踪潜行状态，由调用方传
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun interactWith(entityId: Int, hand: Hand = Hand.MAIN_HAND, sneaking: Boolean = false) {
        connection.interactWith(entityId, hand, sneaking, null)
    }

    /**
     * 在实体表面的指定点交互。
     *
     * [x] [y] [z] 是实体坐标系里的相对位置（原版用于铠甲架的部位判定等）。多数服务端
     * 对有无交互点的处理一致；两者都提供是为了忠实于协议而不是替调用方做取舍。
     */
    public fun interactAt(
        entityId: Int,
        x: Double,
        y: Double,
        z: Double,
        hand: Hand = Hand.MAIN_HAND,
        sneaking: Boolean = false,
    ) {
        connection.interactWith(entityId, hand, sneaking, Vector3d.from(x, y, z))
    }

    /**
     * 开始挖掘 [position] 处方块的 [face] 那一面。
     *
     * 这只是三段中的第一段：服务端收到后开始计时（挖掘耗时为 0 的方块会当场打碎），
     * 到时间之前既不下发方块变更，也不报错。取消用 [cancelDigging]，完成用 [finishDigging]；
     * 想一次完成，用 [digBlock]。
     *
     * 坐标与面必须在三段之间保持一致：服务端按两者配对，中途改动等于重新开始。
     *
     * @throws IllegalArgumentException 坐标超出协议位宽，或角度分量非法
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun startDigging(position: BlockPosition, face: BlockFace = BlockFace.UP) {
        connection.dig(DigAction.START, position, face)
    }

    /**
     * 放弃正在进行的挖掘。松手、换目标或被打断时发。
     *
     * 服务端会回滚它对这次挖掘做的预测（如果有），并回一个确认序号。
     *
     * @throws IllegalArgumentException 坐标超出协议位宽
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun cancelDigging(position: BlockPosition, face: BlockFace = BlockFace.UP) {
        connection.dig(DigAction.CANCEL, position, face)
    }

    /**
     * 完成挖掘：服务端在这一刻真正打碎方块、掉落物品并广播方块变更。
     *
     * 本库不计算挖掘进度。真实客户端会在本地按方块硬度与手持工具算出耗时，到点再发这个包；
     * 提前发送会被严格的服务端忽略（无回执），宽松的服务端（例如联调用的 Minestom）则接受。
     *
     * @throws IllegalArgumentException 坐标超出协议位宽
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun finishDigging(position: BlockPosition, face: BlockFace = BlockFace.UP) {
        connection.dig(DigAction.FINISH, position, face)
    }

    /**
     * 一次性挖掉一块方块：开始 + 完成，不等挖掘进度。
     *
     * 适合无头客户端与自动化：真客户端要按方块硬度与工具算耗时，那属于游戏规则而非协议。
     * 严格的服务端会拒绝“进度不足”的完成包（无回执），需要被认可时请自己控制三段。
     *
     * @throws IllegalArgumentException 坐标超出协议位宽
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun digBlock(position: BlockPosition, face: BlockFace = BlockFace.UP) {
        connection.digBlock(position, face)
    }

    /**
     * 对着某格方块使用物品：放置方块，或与方块交互（开箱子、按按钮、耕地）。
     *
     * 同一个包承担两件事，由服务端按手里的物品与方块行为决定，本端不判断——判断需要物品
     * 组件与方块行为表，那属于下游客户端的资产。
     *
     * 光标位置是点击点在方块内的相对坐标（三轴 0..1），服务端会据此验算面的命中点，
     * 默认取方块中心。放置方块时，落点是 `position.offset(face)`。
     *
     * @param insideBlock 玩家头部是否在目标方块内部；只有贴墙放置才会用到，默认 false
     * @throws IllegalArgumentException 坐标超出协议位宽，或光标位置不在 0..1 之间
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun useOnBlock(
        position: BlockPosition,
        face: BlockFace = BlockFace.UP,
        cursorX: Float = 0.5f,
        cursorY: Float = 0.5f,
        cursorZ: Float = 0.5f,
        hand: Hand = Hand.MAIN_HAND,
        insideBlock: Boolean = false,
    ) {
        connection.useOnBlock(position, face, cursorX, cursorY, cursorZ, hand, insideBlock)
    }

    /**
     * 对着空气使用手里的物品：吃、喝、投掷、拉弓。
     *
     * 包里带本端当前视角，服务端据此决定投掷物的方向。想让服务端先转过来，先调 [look]。
     *
     * @throws IllegalStateException 尚未进入 play 阶段，或连接已关闭
     */
    public fun useItem(hand: Hand = Hand.MAIN_HAND) {
        connection.useItem(hand)
    }

    public companion object {

        /**
         * 创建一次状态查询。
         *
         * 状态查询在协议层是短连接：拿到服务端信息与 Ping 往返耗时后由协议库收尾，
         * 上层随后调用 [close] 回收资源即可。
         */
        public fun status(
            address: ServerAddress,
            listener: ProtocolListener,
            connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
            readTimeoutSeconds: Int = DEFAULT_READ_TIMEOUT_SECONDS,
        ): ProtocolConnection = create(
            address = address,
            protocol = MinecraftProtocol(),
            listener = listener,
            statusQuery = true,
            connectTimeoutSeconds = connectTimeoutSeconds,
            readTimeoutSeconds = readTimeoutSeconds,
        )

        /**
         * 创建一次离线登录。
         *
         * 只支持离线模式：在线模式需要微软认证与令牌存储，属于 M5。
         */
        public fun login(
            address: ServerAddress,
            username: String,
            listener: ProtocolListener,
            connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
            readTimeoutSeconds: Int = DEFAULT_READ_TIMEOUT_SECONDS,
        ): ProtocolConnection {
            require(username.isNotBlank()) { "username 不能为空" }
            require(username.length <= MAX_USERNAME_LENGTH) {
                "username 最长 $MAX_USERNAME_LENGTH 个字符，实际为 ${username.length}"
            }
            return create(
                address = address,
                protocol = MinecraftProtocol(username),
                listener = listener,
                statusQuery = false,
                connectTimeoutSeconds = connectTimeoutSeconds,
                readTimeoutSeconds = readTimeoutSeconds,
            )
        }

        private fun create(
            address: ServerAddress,
            protocol: MinecraftProtocol,
            listener: ProtocolListener,
            statusQuery: Boolean,
            connectTimeoutSeconds: Int,
            readTimeoutSeconds: Int,
        ): ProtocolConnection {
            require(connectTimeoutSeconds > 0) { "connectTimeoutSeconds 必须为正数" }
            require(readTimeoutSeconds > 0) { "readTimeoutSeconds 必须为正数" }
            return ProtocolConnection(
                McplConnection(
                    target = address,
                    protocol = protocol,
                    listener = listener,
                    statusQuery = statusQuery,
                    connectTimeoutSeconds = connectTimeoutSeconds,
                    readTimeoutSeconds = readTimeoutSeconds,
                ),
            )
        }

        private const val DEFAULT_CONNECT_TIMEOUT_SECONDS: Int = 10
        private const val DEFAULT_READ_TIMEOUT_SECONDS: Int = 30

        /** Minecraft 用户名长度上限，超长会被服务端直接踢下线。 */
        private const val MAX_USERNAME_LENGTH: Int = 16
    }
}
