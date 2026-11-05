package org.pickaxe.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.ClientException
import org.pickaxe.client.ClientOptions
import org.pickaxe.client.CompletionResult
import org.pickaxe.client.MinecraftClient
import org.pickaxe.client.Session
import org.pickaxe.protocol.ProtocolVersion
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.inventory.Container
import org.pickaxe.protocol.inventory.InventoryItem
import org.pickaxe.protocol.inventory.SlotClick
import org.pickaxe.protocol.world.BlockFace
import org.pickaxe.protocol.world.BlockPosition
import org.pickaxe.protocol.world.HeightmapType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.system.exitProcess

private val usage = """
    用法: pickaxecraft <命令> [参数]

    命令:
      status <host> [port] [--timeout <秒>]
          查询服务端状态并打印版本、人数与延迟

      connect <host> [port] [--username <名称>] [--timeout <秒>] [--say <文本>]...
              [--walk <格数>] [--hold <0-8>] [--click <槽位>[:动作]]... [--close-window]
              [--dig] [--place] [--sneak] [--sprint] [--fly] [--light]
              [--commands] [--complete <文本>]...
          离线登录并持续打印会话事件，Ctrl+C 关闭会话后退出
          --say 在出生完成后再发送一条聊天消息，可重复；服务端会把它广播回来
          --walk 在出生后沿 +X 方向走指定格数（每步 0.5 格），打印每一步的坐标
          --hold 在出生后切换到指定快捷栏格，并打印该格上的物品
          --click 在出生后点击一格，可重复；动作见下，缺省为 pickup
          --close-window 在其余动作之后关闭当前打开的容器
          --dig 在出生后挖掉脚下那一格，等待服务端的方块变更回包
          --place 在出生后把手中物品放到脚旁东侧一格的上面；不能放进玩家自己
               占据的格子（服务端做碰撞检查，见 plan 文档），所以偏东一格
          --sneak / --sprint / --fly 在出生后声明潜行、冲刺或请求飞行；
               飞行是否被接受要看随后服务端的 player_abilities 包
          --light 在出生后打印本列的光照、高度图与方块实体；用来核对
               level_chunk_with_light 的四部分是否都到了本端
          --commands 在出生后打印服务端下发的命令树摘要（节点数、顶层命令名）
          --complete 在出生后请求一次补全，可重复；候选由服务端算，含前导斜杠

          点击动作:
            pickup      左键拿起或放下整堆（默认）
            right       右键拿起半堆或放下一个
            quick       Shift 点击，快速转移
            swap<N>     与快捷栏第 N 格交换，N 为 0-8
            throw       丢弃一个
            throw-all   丢弃整堆
            all         双击收拢同类物品

      --version   打印客户端版本与锁定的游戏/协议/服务端版本
      --help      打印本帮助

    不带参数时同样打印本帮助。
""".trimIndent()

private val versionInfo = """
    pickaxecraft ${BuildInfo.CLIENT_VERSION}
    minecraft ${ProtocolVersion.MINECRAFT_VERSION}
    protocol ${ProtocolVersion.PROTOCOL_VERSION}
    minestom ${BuildInfo.MINESTOM_VERSION}
""".trimIndent()

/** 未指定 --timeout 时的默认超时，单位秒。 */
private const val DEFAULT_TIMEOUT_SECONDS: Long = 15

/** 参数错误：用户可以修正，与网络失败区分开。 */
private const val EXIT_USAGE: Int = 2

/** 运行期失败：连接被拒、服务端断开、超时。 */
private const val EXIT_FAILED: Int = 1

public fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val exitCode = when (command) {
        null -> {
            println(usage)
            0
        }
        in setOf("--help", "-h") -> {
            println(usage)
            0
        }
        "--version" -> {
            println(versionInfo)
            0
        }
        "status" -> runStatus(args.drop(1))
        "connect" -> runConnect(args.drop(1))
        else -> {
            // 未知参数不做猜测，直接报错并给出用法，避免静默忽略。
            System.err.println("未知命令: $command")
            System.err.println(usage)
            EXIT_USAGE
        }
    }
    if (exitCode != 0) exitProcess(exitCode)
}

private fun runStatus(args: List<String>): Int {
    val parsed = parseArgs(args) ?: return EXIT_USAGE
    val client = MinecraftClient(parsed.options())
    return try {
        // runBlocking 只出现在组合根：库本身全部是挂起 API。
        val status = runBlocking { client.status(parsed.address) }
        println(
            buildString {
                appendLine("server=${parsed.address.host}:${parsed.address.port}")
                appendLine("version=${status.versionName ?: "未知"} protocol=${status.protocolVersion ?: "未知"}")
                appendLine("players=${status.onlinePlayers ?: "未知"}/${status.maxPlayers ?: "未知"}")
                appendLine("latency=${status.latencyMillis}ms")
                append("motd=${status.description}")
            },
        )
        0
    } catch (e: ClientException) {
        System.err.println("状态查询失败: ${e.message}")
        EXIT_FAILED
    } finally {
        client.close()
    }
}

private fun runConnect(args: List<String>): Int {
    val parsed = parseArgs(args) ?: return EXIT_USAGE
    val client = MinecraftClient(parsed.options())

    // Ctrl+C 时先关闭客户端再退出：会话的网络线程与连接都在这里释放。
    // 退出码仍由信号决定，hook 只负责收尾。
    val hook = Thread({ client.close() }, "pickaxecraft-shutdown")
    Runtime.getRuntime().addShutdownHook(hook)

    return try {
        runBlocking {
            val session = client.connect(parsed.address, parsed.username)
            println(
                "state=${session.state.value} user=${parsed.username} " +
                    "server=${parsed.address.host}:${parsed.address.port}",
            )
            var chunkReported = false
            // 待发的聊天消息：出生完成（位置已同步且首批区块到达）后一次发出，
            // 避免在 configuration 阶段写包被服务端当作协议错误断开。
            val pendingSays = ArrayDeque(parsed.says)
            session.events.collect { event ->
                println(describe(event))
                // 重生换维度后重新打印一次摘要：同一行输出在新旧维度之间应当明显不同。
                if (event is ClientEvent.Respawned) chunkReported = false
                // 首批区块到齐后打印一次世界摘要：这是“区块真的解出来了”的现场证据。
                if (!chunkReported && event is ClientEvent.ChunksReceived && session.world.value?.position != null) {
                    chunkReported = true
                    println(describeChunk(session))
                    for (text in pendingSays) {
                        // 校验规则由库决定（长度上限、单行），命令行不重复一份；不合法就报出来继续跑。
                        try {
                            session.sendChat(text)
                            println("event=chat.sent text=$text")
                        } catch (e: IllegalArgumentException) {
                            System.err.println("聊天内容不合法: ${e.message}")
                        }
                    }
                    pendingSays.clear()
                    parsed.holdSlot?.let { slot ->
                        session.selectHotbarSlot(slot)
                        println("event=held.sent slot=$slot item=${session.heldItem()}")
                    }
                    parsed.walkDistance?.let { distance -> walkForward(session, distance) }
                    for (click in parsed.clicks) { clickSlot(session, click) }
                    if (parsed.closeWindow) closeActiveWindow(session)
                    if (parsed.digBelow) digBelow(session)
                    if (parsed.placeBelow) placeBelow(session)
                    if (parsed.reportLight) reportLight(session)
                    if (parsed.listCommands) reportCommands(session)
                    for (text in parsed.completions) reportCompletion(session, text)
                    applyMotionFlags(session, parsed)
                }
            }
        }
        0
    } catch (e: ClientException) {
        System.err.println("连接失败: ${e.message}")
        EXIT_FAILED
    } finally {
        client.close()
        // 关闭过程中 JVM 可能已在退出，移除失败无害。
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

private fun describe(event: ClientEvent): String = when (event) {
    is ClientEvent.StateChanged -> "event=state state=${event.state}"
    is ClientEvent.RegistryReceived -> "event=registry id=${event.registryId} entries=${event.entryCount}"
    is ClientEvent.JoinedWorld -> "event=joined entity=${event.entityId} dimension=${event.dimension} mode=${event.gameMode}"
    is ClientEvent.PositionUpdated -> with(event.position) {
        "event=position x=$x y=$y z=$z yaw=$yaw pitch=$pitch"
    }
    is ClientEvent.Respawned -> "event=respawned dimension=${event.dimension} mode=${event.gameMode}"
    is ClientEvent.ChunksReceived -> "event=chunks batch=${event.chunks} total=${event.total}"
    is ClientEvent.BlocksChanged -> event.changes.joinToString(" ") { change ->
        "event=block.changed pos=${change.position} state=${change.stateId}"
    }
    is ClientEvent.BlockAcknowledged -> "event=block.ack sequence=${event.sequence}"
    is ClientEvent.ChunkLightUpdated -> "event=light chunk=${event.chunkX},${event.chunkZ}"
    is ClientEvent.CommandTreeReceived -> "event=commands nodes=${event.tree.size} " +
        "root=${event.tree.rootIndex} literals=${event.tree.topLevelLiterals.size}"
    is ClientEvent.CommandSuggestionsReceived -> with(event.suggestions) {
        "event=suggestions id=$transactionId start=$start length=$length matches=$matches"
    }
    is ClientEvent.BlockEntityChanged -> with(event.entity) {
        "event=blockentity type=$type pos=$position keys=${data?.entries?.keys?.joinToString(",").orEmpty()}"
    }
    is ClientEvent.EntitySpawned -> with(event.entity) {
        val who = customName?.let { " name=$it" }.orEmpty()
        "event=entity.spawned id=$id type=$type$who x=$x y=$y z=$z"
    }
    is ClientEvent.EntityRemoved -> "event=entity.removed ids=${event.ids}"
    is ClientEvent.EntityAttributesUpdated ->
        "event=attributes id=${event.entityId} " +
            event.changed.joinToString(" ") { "${it.key}=${it.value}" }
    is ClientEvent.EntityDamaged -> "event=damaged id=${event.entityId} source=${event.sourceType}"
    is ClientEvent.EntityHealthChanged -> "event=entity.health id=${event.entityId} hp=${event.health}"
    is ClientEvent.AbilitiesChanged -> with(event.abilities) {
        "event=abilities invincible=$invincible canFly=$canFly flying=$flying " +
            "creative=$creative flySpeed=$flySpeed walkSpeed=$walkSpeed"
    }
    is ClientEvent.EntityEffectsChanged ->
        "event=effects id=${event.entityId} " +
            "changed=${event.changed.map { "${it.key}x${it.amplifier}" }} removed=${event.removed}"
    is ClientEvent.ContainerPropertyChanged ->
        "event=container.property window=${event.windowId} " +
            "property=${event.property} value=${event.value}"
    is ClientEvent.PlayerListChanged -> "event=players updated=${event.updated.map { it.name ?: it.uuid }}" +
        " removed=${event.removed}"
    is ClientEvent.ChatReceived -> with(event.message) {
        val where = if (overlay) "actionbar" else kind.name.lowercase()
        val who = listOfNotNull(senderName, targetName?.let { "-> $it" }).joinToString(" ").let {
            if (it.isEmpty()) "" else " $it"
        }
        "event=chat kind=$where$who fmt=${format?.key} text=$text"
    }
    is ClientEvent.ChatDeleted -> "event=chat.deleted id=${event.signatureId}"
    is ClientEvent.InventoryChanged -> "event=inventory window=${event.windowId}"
    is ClientEvent.ContainerOpened ->
        "event=container.opened window=${event.windowId} type=${event.type} title=${event.title}"
    is ClientEvent.ContainerClosed -> "event=container.closed window=${event.windowId}"
    is ClientEvent.HeldSlotChanged -> "event=held slot=${event.slot}"
    is ClientEvent.StatusChanged -> with(event.status) {
        "event=status hp=$health food=$food saturation=$foodSaturation " +
            "xp=$experienceBar level=$level total=$totalExperience"
    }
    ClientEvent.Closed -> "event=closed"
    is ClientEvent.Failed -> "event=failed kind=${event.error::class.simpleName} message=${event.error.message}"
}

/**
 * 出生后声明运动状态。
 *
 * 三个标志分别对应三条不同的上行路径；打印的都是「已发出」，飞行是否被接受
 * 只能由随后的 `event=abilities` 证明——服务端可能因为 canFly=false 拒绝。
 */
private fun applyMotionFlags(session: Session, parsed: ParsedArgs) {
    if (parsed.sneak) {
        session.setSneaking(true)
        println("event=sneak.sent input=${session.movementInput.value}")
    }
    if (parsed.sprint) {
        session.setSprinting(true)
        println("event=sprint.sent input=${session.movementInput.value}")
    }
    if (parsed.fly) {
        session.setFlying(true)
        println("event=fly.sent")
    }
}

/** 每步移动的距离：半格，足够看出“人真的在走”，也不会让服务端的距离校验有意见。 */
private const val WALK_STEP: Double = 0.5

/** 步间间隔：20 次/秒，与真实客户端的移动包频率一致。 */
private const val WALK_INTERVAL_MILLIS: Long = 50

/**
 * 沿 +X 方向走 [distance] 格。
 *
 * 每步只声明坐标，朝向保持出生时的值——走到服务端的是移动包而不是一次传送。
 * 步数向上取整，最后一步停在精确的终点上；终点由本端声明，服务端不接受时会有
 * 位置同步把它改回来（这一步的现场证据看日志里的 `event=position`）。
 */
private suspend fun walkForward(session: Session, distance: Double) {
    val start = session.world.value?.position ?: run {
        System.err.println("位置尚未同步，跳过 --walk")
        return
    }
    val steps = ceil(distance / WALK_STEP).toInt()
    for (step in 1..steps) {
        val x = start.x + minOf(step * WALK_STEP, distance)
        session.moveTo(start.copy(x = x))
        println("event=move.sent x=$x y=${start.y} z=${start.z}")
        delay(WALK_INTERVAL_MILLIS)
    }
    val end = session.world.value?.position
    println("event=move.done from=${start.x} x=${end?.x} y=${end?.y} z=${end?.z}")
}

/** 一次点击的参数：窗口槽位与意图。 */
private data class ClickArg(val slot: Int, val click: SlotClick)

/**
 * 点击一格并打印“已发出”。
 *
 * 打印的是**发送**这件事，不是结果：本库不做本地预测，槽位真正变化要等服务端下发内容，
 * 会在随后的 `event=inventory` 行里出现。窗口内容还没到时跳过而不是抛出去——命令行不该
 * 因为一次早到的点击而整场退出。
 */
private fun clickSlot(session: Session, arg: ClickArg) {
    try {
        session.clickSlot(arg.slot, arg.click)
    } catch (e: IllegalArgumentException) {
        System.err.println("点击参数不合法: ${e.message}")
        return
    } catch (e: IllegalStateException) {
        System.err.println("跳过点击（${e.message}）")
        return
    }
    println("event=click.sent window=${session.activeWindowId} slot=${arg.slot} kind=${describeClick(arg.click)}")
}

/** 关闭当前打开的容器；没有容器时说明原因，不当作错误。 */
private fun closeActiveWindow(session: Session) {
    val windowId = session.activeWindowId
    if (windowId == Container.PLAYER_WINDOW_ID) {
        System.err.println("没有打开的窗口，跳过 --close-window")
        return
    }
    session.closeContainer(windowId)
    println("event=window.close.sent window=$windowId")
}

/** 点击意图的短名字，与命令行里的动作写法一致。 */
private fun describeClick(click: SlotClick): String = when (click) {
    is SlotClick.Pickup -> if (click.rightButton) "right" else "pickup"
    is SlotClick.QuickMove -> if (click.rightButton) "quick-right" else "quick"
    is SlotClick.SwapWithHotbar -> "swap${click.hotbar}"
    is SlotClick.Throw -> if (click.wholeStack) "throw-all" else "throw"
    SlotClick.PickupAll -> "all"
}

/** 等待方块缓存出现变化的时限：覆盖两个往返绰绰有余，又不至于让演示卡住。 */
private const val BLOCK_CHANGE_TIMEOUT_MILLIS: Long = 3_000

/** 脚下方块：客户端不做物理，直接用声明的坐标向下取整再减一。 */
private fun blockBelow(session: Session): BlockPosition? {
    val position = session.world.value?.position ?: run {
        System.err.println("位置尚未同步，跳过方块交互")
        return null
    }
    return BlockPosition(position.x.toInt(), floor(position.y).toInt() - 1, position.z.toInt())
}

/**
 * 挖掉脚下那一格。
 *
 * 打印的是**发送**与**结果**两件事：本库不做挖掘进度，`dig.sent` 只证明包发出去了；
 * `dig.done` 里的 state 才是服务端认可的权威结果——它来自方块变更写入后的缓存。
 */
private suspend fun digBelow(session: Session) {
    val target = blockBelow(session) ?: return
    val before = session.blockStateAt(target)
    try {
        session.digBlock(target)
    } catch (e: IllegalArgumentException) {
        System.err.println("挖掘参数不合法: ${e.message}")
        return
    } catch (e: IllegalStateException) {
        System.err.println("跳过挖掘（${e.message}）")
        return
    }
    println("event=dig.sent pos=$target before=$before")
    val confirmed = awaitBlockState(target, expectNot = before, session)
    println(
        "event=dig.done pos=$target state=${session.blockStateAt(target)} " +
            "ack=${session.lastAcknowledgedSequence} confirmed=$confirmed",
    )
}

/**
 * 把手中物品放到脚旁东侧一格的上面。
 *
 * 不放“脚下方块的上表面”是有意的：那格正是玩家自己占据的格子，服务端会做碰撞检查、
 * 只回确认不放置（原版行为）。偏东一格避开了自己的碰撞盒，演示才能看到真实的放置。
 */
private suspend fun placeBelow(session: Session) {
    val feetBlock = blockBelow(session) ?: return
    val target = feetBlock.offset(BlockFace.EAST)
    val above = target.offset(BlockFace.UP)
    val before = session.blockStateAt(above)
    try {
        session.useOnBlock(target, BlockFace.UP, cursorY = 1f)
    } catch (e: IllegalArgumentException) {
        System.err.println("放置参数不合法: ${e.message}")
        return
    } catch (e: IllegalStateException) {
        System.err.println("跳过放置（${e.message}）")
        return
    }
    println("event=place.sent pos=$target face=UP placingAt=$above held=${session.heldItem()}")
    val confirmed = awaitBlockState(above, expectNot = before, session)
    println(
        "event=place.done pos=$above state=${session.blockStateAt(above)} " +
            "ack=${session.lastAcknowledgedSequence} confirmed=$confirmed",
    )
}

/** 轮询方块缓存直到它变成 [expectNot] 以外的值，或超时。 */
private suspend fun awaitBlockState(
    target: BlockPosition,
    expectNot: Int?,
    session: Session,
): Boolean {
    val deadline = System.nanoTime() + BLOCK_CHANGE_TIMEOUT_MILLIS * 1_000_000
    while (System.nanoTime() < deadline) {
        if (session.blockStateAt(target) != expectNot) return true
        delay(50)
    }
    return false
}

/** 出生点所在区块的摘要：维度范围、缓存数量与脚下方块。 */
private fun describeChunk(session: Session): String {
    val position = session.world.value?.position ?: return "chunk=unknown reason=位置尚未同步"
    val x = position.x.toInt()
    val y = position.y.toInt()
    val z = position.z.toInt()
    val column = session.chunk(x shr 4, z shr 4) ?: return "chunk=unloaded at (${x shr 4}, ${z shr 4})"

    val below = if (column.sectionAt(y - 1) == null) "越界" else session.blockStateAt(x, y - 1, z).toString()
    // 光照与高度图来自同一个区块包，缺失时这里是 null 而不是 0——两者含义不同。
    val height = session.heightAt(HeightmapType.MOTION_BLOCKING, x, z)
    val sky = session.skyLightAt(x, y, z)
    val block = session.blockLightAt(x, y, z)
    // 玩家列表与实体表分开统计：前者是“谁在线”，后者是“视野里有什么”。
    val players = session.players().values.map { it.name ?: it.uuid.toString() }
    val inventory = session.inventory()?.let { container ->
        val named = container.usedSlots.takeIf { n -> n > 0 }
            ?.let { " items=" + container.slots.filterNotNull().joinToString(",", transform = ::describeItem) }
            .orEmpty()
        "slots=${container.slots.size} used=${container.usedSlots}$named"
    } ?: "未知"
    return "chunk x=${column.x} z=${column.z} minY=${column.minY} sections=${column.sectionCount} " +
        "cached=${session.loadedChunks} entities=${session.entityCount} chat=${session.chatCount} players=$players " +
        "block=($x, $y, $z):${session.blockStateAt(x, y, z)} below=$below " +
        "sky=$sky blockLight=$block height=$height blockEntities=${column.blockEntities.size} " +
        "held=${session.heldSlot} inventory=$inventory " +
        "hp=${session.status.value.health} level=${session.status.value.level}"
}

/**
 * 打印本列的光照、高度图与方块实体。
 *
 * 这三样与方块来自同一个 `level_chunk_with_light` 包，但走的是不同的解码路径：方块由本库
 * 自己的解码器拆 section，其余三样由协议库解析、本层换算坐标。打印出来是为了能一眼看出
 * “光照真的到了”“高度图与世界高度对得上”，而不是只相信包已经收到。
 */
private fun reportLight(session: Session) {
    val position = session.world.value?.position ?: run {
        System.err.println("跳过光照检查（位置尚未同步）")
        return
    }
    val x = floor(position.x).toInt()
    val y = floor(position.y).toInt()
    val z = floor(position.z).toInt()
    val column = session.chunk(x shr 4, z shr 4)
    println(
        "event=light pos=($x, $y, $z) sky=${session.skyLightAt(x, y, z)} " +
            "block=${session.blockLightAt(x, y, z)} skyBelow=${session.skyLightAt(x, y - 2, z)} " +
            "height(motion_blocking)=${session.heightAt(HeightmapType.MOTION_BLOCKING, x, z)} " +
            "blockEntities=${column?.blockEntities?.size ?: "未加载"}",
    )
}

/**
 * 打印服务端下发的命令树摘要。
 *
 * 只列节点数与顶层命令名：整棵树在真实服务端上有几百个节点，全量打印会把日志淹掉。
 * 树还没到时明确说出来——出生完成与命令树下发是两个包，理论上可能差一拍。
 */
private fun reportCommands(session: Session) {
    val tree = session.commands.value ?: run {
        System.err.println("跳过命令树（尚未收到）")
        return
    }
    val literals = tree.topLevelLiterals
    println(
        "event=commands nodes=${tree.size} root=${tree.rootIndex} " +
            "literals=${literals.size} first=${literals.take(8)}",
    )
}

/**
 * 请求一次命令补全并打印结果。
 *
 * 候选全部来自服务端：本端不猜。无回执时明确打印 no-answer，而不是打印空列表——
 * 两者在“服务端不支持补全”和“这里没有可选值”之间是不同的结论。
 */
private suspend fun reportCompletion(session: Session, text: String) {
    when (val result = session.complete(text)) {
        is CompletionResult.Suggestions -> with(result.suggestions) {
            println(
                "event=complete text=$text id=$transactionId start=$start length=$length " +
                    "matches=$matches applied=${applyTo(text)}",
            )
        }
        CompletionResult.NoAnswer -> println("event=complete text=$text result=no-answer")
    }
}

/** 摘要里把物品的非默认组件一起列出来，便于肉眼核对组件补丁是否穿过协议。 */
private fun describeItem(item: InventoryItem): String {
    val details = buildList {
        item.damage?.let { add("damage=$it") }
        item.maxDamage?.let { add("maxDamage=$it") }
        if (item.unbreakable) add("unbreakable")
        item.repairCost?.let { add("repairCost=$it") }
        item.enchantments.forEach { (key, level) -> add("$key=$level") }
        item.storedEnchantments.forEach { (key, level) -> add("stored:$key=$level") }
        if (item.lore.isNotEmpty()) add("lore=${item.lore.size}")
    }
    val key = item.key ?: "#${item.itemId}"
    return if (details.isEmpty()) key else "$key(${details.joinToString(",")})"
}

/** 解析后的命令行参数。 */
private class ParsedArgs(
    val address: ServerAddress,
    val username: String,
    val timeoutSeconds: Long,
    /** 出生后要发送的聊天消息，按命令行顺序。 */
    val says: List<String> = emptyList(),
    /** 出生后沿 +X 方向走的格数；null 表示不走。 */
    val walkDistance: Double? = null,
    /** 出生后要切换到的快捷栏格；null 表示不切。 */
    val holdSlot: Int? = null,
    /** 出生后要点击的格子，按命令行顺序。 */
    val clicks: List<ClickArg> = emptyList(),
    /** 其余动作之后是否关闭当前打开的容器。 */
    val closeWindow: Boolean = false,
    /** 出生后挖掉脚下方块。 */
    val digBelow: Boolean = false,
    /** 出生后把手中物品放到脚下方块的上表面。 */
    val placeBelow: Boolean = false,
    /** 出生后声明潜行、冲刺或请求飞行。 */
    val sneak: Boolean = false,
    val sprint: Boolean = false,
    val fly: Boolean = false,
    /** 出生后打印本列的光照、高度图与方块实体。 */
    val reportLight: Boolean = false,
    /** 出生后打印命令树摘要。 */
    val listCommands: Boolean = false,
    /** 出生后要补全的输入行（含前导斜杠），按命令行顺序。 */
    val completions: List<String> = emptyList(),
) {
    fun options(): ClientOptions = ClientOptions(
        connectTimeoutMillis = timeoutSeconds * 1000,
        loginTimeoutMillis = timeoutSeconds * 1000,
        statusTimeoutMillis = timeoutSeconds * 1000,
    )
}

/** 解析 `host [port] [--username 名称] [--timeout 秒] [--say 文本]`；不合法时打印原因并返回 null。 */
private fun parseArgs(args: List<String>): ParsedArgs? {
    if (args.isEmpty()) {
        System.err.println("缺少 host")
        return null
    }

    val host = args.first()
    var port = ServerAddress.DEFAULT_PORT
    var username = MinecraftClient.DEFAULT_USERNAME
    var timeoutSeconds = DEFAULT_TIMEOUT_SECONDS
    val says = ArrayList<String>()
    var walkDistance: Double? = null
    var holdSlot: Int? = null
    val clicks = ArrayList<ClickArg>()
    var closeWindow = false
    var digBelow = false
    var placeBelow = false
    var reportLight = false
    var listCommands = false
    val completions = ArrayList<String>()
    var sneak = false
    var sprint = false
    var fly = false

    var index = 1
    // 第二个位置参数若是纯数字则视为端口。
    args.getOrNull(index)?.takeIf { value -> value.all(Char::isDigit) }?.let { value ->
        port = value.toInt()
        index++
    }

    while (index < args.size) {
        val option = args[index]
        val value = args.getOrNull(index + 1)
        when (option) {
            "--username" -> {
                if (value == null) {
                    System.err.println("--username 缺少取值")
                    return null
                }
                username = value
                index += 2
            }
            "--timeout" -> {
                val seconds = value?.toLongOrNull()
                if (seconds == null || seconds <= 0) {
                    System.err.println("--timeout 需要一个正数秒数")
                    return null
                }
                timeoutSeconds = seconds
                index += 2
            }
            "--say" -> {
                if (value == null) {
                    System.err.println("--say 缺少取值")
                    return null
                }
                says += value
                index += 2
            }
            "--hold" -> {
                val slot = value?.toIntOrNull()
                if (slot == null || slot < 0 || slot > 8) {
                    System.err.println("--hold 需要 0-8 之间的快捷栏格号")
                    return null
                }
                holdSlot = slot
                index += 2
            }
            "--walk" -> {
                val distance = value?.toDoubleOrNull()
                if (distance == null || distance <= 0.0 || !distance.isFinite()) {
                    System.err.println("--walk 需要一个正数格数")
                    return null
                }
                walkDistance = distance
                index += 2
            }
            "--click" -> {
                val click = value?.let(::parseClick)
                if (click == null) {
                    System.err.println("--click 需要 <槽位>[:动作]，动作见 --help")
                    return null
                }
                clicks += click
                index += 2
            }
            "--close-window" -> {
                closeWindow = true
                index += 1
            }
            "--dig" -> {
                digBelow = true
                index += 1
            }
            "--place" -> {
                placeBelow = true
                index += 1
            }
            "--sneak" -> {
                sneak = true
                index += 1
            }
            "--sprint" -> {
                sprint = true
                index += 1
            }
            "--fly" -> {
                fly = true
                index += 1
            }
            "--light" -> {
                reportLight = true
                index += 1
            }
            "--commands" -> {
                listCommands = true
                index += 1
            }
            "--complete" -> {
                if (value == null) {
                    System.err.println("--complete 缺少取值")
                    return null
                }
                completions += value
                index += 2
            }
            else -> {
                System.err.println("未知参数: $option")
                return null
            }
        }
    }

    return try {
        ParsedArgs(
            address = ServerAddress(host, port),
            username = username,
            timeoutSeconds = timeoutSeconds,
            says = says,
            walkDistance = walkDistance,
            holdSlot = holdSlot,
            clicks = clicks,
            closeWindow = closeWindow,
            digBelow = digBelow,
            placeBelow = placeBelow,
            sneak = sneak,
            sprint = sprint,
            fly = fly,
            reportLight = reportLight,
            listCommands = listCommands,
            completions = completions,
        )
    } catch (e: IllegalArgumentException) {
        System.err.println("参数不合法: ${e.message}")
        null
    }
}

/**
 * 解析 `槽位[:动作]`。
 *
 * 动作名与协议里的点击类型一一对应，但不暴露协议名：`swap<N>` 里的 N 是快捷栏下标，
 * 不是窗口槽位——这两套编号在窗口 0 里差 36，混用会静默点到别的格子。
 */
private fun parseClick(value: String): ClickArg? {
    val parts = value.split(':', limit = 2)
    val slot = parts[0].toIntOrNull()
    if (slot == null || slot < 0) return null

    val kind = parts.getOrNull(1) ?: "pickup"
    val click = when {
        kind == "pickup" -> SlotClick.Pickup()
        kind == "right" -> SlotClick.Pickup(rightButton = true)
        kind == "quick" -> SlotClick.QuickMove()
        kind == "quick-right" -> SlotClick.QuickMove(rightButton = true)
        kind == "throw" -> SlotClick.Throw()
        kind == "throw-all" -> SlotClick.Throw(wholeStack = true)
        kind == "all" -> SlotClick.PickupAll
        // 越界的快捷栏下标由 SlotClick 自己拒绝，这里只负责把错误挡在启动阶段。
        kind.startsWith("swap") -> runCatching {
            SlotClick.SwapWithHotbar(kind.removePrefix("swap").toInt())
        }.getOrNull()
        else -> null
    } ?: return null
    return ClickArg(slot, click)
}
