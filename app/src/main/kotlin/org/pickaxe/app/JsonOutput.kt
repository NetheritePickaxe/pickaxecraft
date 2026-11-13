package org.pickaxe.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.pickaxe.client.ClientEvent
import org.pickaxe.client.ClientException
import org.pickaxe.protocol.PluginMessages
import org.pickaxe.protocol.ServerAddress
import org.pickaxe.protocol.ServerStatus

/**
 * `--json` 的 JSONL 输出。
 *
 * 每行一个 JSON 对象，字段词表固定为 `type` / `event` / `session` / `data` / `ok` /
 * `error` / `exitCode`：
 * - 事件行：`type=event`，`event` 是稳定的事件名，`data` 是该事件的字段；
 * - 结果行：`type=result`（`status` 查询为 `type=status`），带 `ok` 与 `exitCode`。
 *
 * 这里是 0.x 的实验契约：字段可以随小版本调整，调用方应按 `type` 分支处理、
 * 对未知字段保持宽容。人类可读输出仍是默认格式；`--json` 只改变呈现，不改变行为。
 * 事件里没有值的字段输出为 `null`（键保持稳定），不做省略。
 *
 * 结构化字段与 [describe] 服务同一批 [ClientEvent]，新增事件时两处都要补，
 * 否则 JSON 消费者会看到一个只有事件名、没有数据的对象。
 */
internal fun jsonEventLine(event: ClientEvent, username: String, address: ServerAddress): String =
    buildJsonObject {
        put("type", "event")
        put("event", eventName(event))
        put("session", sessionJson(username, address))
        put("data", eventData(event))
        // 终止事件本身就是失败信号；其余事件只要到达就是可观察的事实。
        put("ok", event !is ClientEvent.Failed)
    }.toString()

/** `connect` 的最终结果；`error` 只在连接在会话建立前就失败时出现。 */
internal fun jsonConnectResultLine(
    username: String,
    address: ServerAddress,
    ok: Boolean,
    error: ClientException?,
    exitCode: Int,
): String =
    buildJsonObject {
        put("type", "result")
        put("session", sessionJson(username, address))
        put("ok", ok)
        if (error != null) put("error", errorJson(error))
        put("exitCode", exitCode)
    }.toString()

/** `status` 的一次查询结果；成功与失败都是同一个对象形状。 */
internal fun jsonStatusLine(
    address: ServerAddress,
    status: ServerStatus?,
    error: ClientException?,
    exitCode: Int,
): String =
    buildJsonObject {
        put("type", "status")
        put("session", sessionJson(username = null, address = address))
        put("ok", error == null)
        if (error != null) put("error", errorJson(error))
        if (status != null) put("data", statusJson(status))
        put("exitCode", exitCode)
    }.toString()

private fun sessionJson(username: String?, address: ServerAddress): JsonObject = buildJsonObject {
    // 状态查询没有用户名；键仍然存在并输出 null，避免消费者按“键缺失”判断会话类型。
    put("username", username)
    put("host", address.host)
    put("port", address.port)
}

private fun statusJson(status: ServerStatus): JsonObject = buildJsonObject {
    put("version", status.versionName)
    put("protocol", status.protocolVersion)
    put("players", status.onlinePlayers)
    put("maxPlayers", status.maxPlayers)
    put("latencyMillis", status.latencyMillis)
    put("motd", status.description)
}

private fun errorJson(error: ClientException): JsonObject = buildJsonObject {
    put("kind", error::class.simpleName ?: "ClientException")
    put("message", error.message ?: "")
}

/**
 * 事件名：与人类输出里的 `event=` 前缀保持一致，方便两种模式对照阅读。
 *
 * 用密封接口的穷尽 `when` 而不是反射类名：类名是 Kotlin 实现细节，事件名是输出契约。
 */
private fun eventName(event: ClientEvent): String = when (event) {
    is ClientEvent.StateChanged -> "state"
    is ClientEvent.RegistryReceived -> "registry"
    is ClientEvent.JoinedWorld -> "joined"
    is ClientEvent.PositionUpdated -> "position"
    is ClientEvent.Respawned -> "respawned"
    is ClientEvent.ChunksReceived -> "chunks"
    is ClientEvent.BlocksChanged -> "blocks_changed"
    is ClientEvent.BlockAcknowledged -> "block_acknowledged"
    is ClientEvent.BlockDestructionChanged -> "block_destruction"
    is ClientEvent.ChunkLightUpdated -> "light"
    is ClientEvent.BlockEntityChanged -> "block_entity"
    is ClientEvent.EntitySpawned -> "entity_spawned"
    is ClientEvent.EntityRemoved -> "entity_removed"
    is ClientEvent.EntityAttributesUpdated -> "attributes"
    is ClientEvent.EntityDamaged -> "damaged"
    is ClientEvent.EntityHealthChanged -> "entity_health"
    is ClientEvent.PassengersChanged -> "passengers"
    is ClientEvent.VehicleChanged -> "vehicle"
    is ClientEvent.PlayerListChanged -> "players"
    is ClientEvent.ChatReceived -> "chat"
    is ClientEvent.ChatDeleted -> "chat_deleted"
    is ClientEvent.InventoryChanged -> "inventory"
    is ClientEvent.ContainerOpened -> "container_opened"
    is ClientEvent.ContainerClosed -> "container_closed"
    is ClientEvent.HeldSlotChanged -> "held_slot"
    is ClientEvent.StatusChanged -> "status"
    is ClientEvent.AbilitiesChanged -> "abilities"
    is ClientEvent.EntityEffectsChanged -> "effects"
    is ClientEvent.ContainerPropertyChanged -> "container_property"
    is ClientEvent.CommandTreeReceived -> "commands"
    is ClientEvent.CommandSuggestionsReceived -> "suggestions"
    is ClientEvent.TransferRequested -> "transfer"
    is ClientEvent.PluginMessageReceived -> "plugin"
    ClientEvent.Closed -> "closed"
    is ClientEvent.Failed -> "failed"
}

private fun eventData(event: ClientEvent): JsonObject = buildJsonObject {
    when (event) {
        is ClientEvent.StateChanged -> put("state", event.state.name)

        is ClientEvent.RegistryReceived -> {
            put("registry", event.registryId)
            put("entries", event.entryCount)
        }

        is ClientEvent.JoinedWorld -> {
            put("entityId", event.entityId)
            put("dimension", event.dimension)
            put("gameMode", event.gameMode)
        }

        is ClientEvent.PositionUpdated -> with(event.position) {
            put("x", x)
            put("y", y)
            put("z", z)
            put("yaw", yaw)
            put("pitch", pitch)
        }

        is ClientEvent.Respawned -> {
            put("dimension", event.dimension)
            put("gameMode", event.gameMode)
        }

        is ClientEvent.ChunksReceived -> {
            put("chunks", event.chunks)
            put("total", event.total)
        }

        is ClientEvent.BlocksChanged -> put(
            "changes",
            buildJsonArray {
                for (change in event.changes) {
                    add(
                        buildJsonObject {
                            put("x", change.position.x)
                            put("y", change.position.y)
                            put("z", change.position.z)
                            put("state", change.stateId)
                        },
                    )
                }
            },
        )

        is ClientEvent.BlockAcknowledged -> put("sequence", event.sequence)

        is ClientEvent.BlockDestructionChanged -> {
            put("x", event.position.x)
            put("y", event.position.y)
            put("z", event.position.z)
            val destruction = event.destruction
            if (destruction == null) {
                put("reset", true)
            } else {
                put("reset", false)
                put("breaker", destruction.breakerEntityId)
                put("stage", destruction.stage)
            }
        }

        is ClientEvent.ChunkLightUpdated -> {
            put("chunkX", event.chunkX)
            put("chunkZ", event.chunkZ)
        }

        is ClientEvent.BlockEntityChanged -> with(event.entity) {
            put("type", type)
            put("x", position.x)
            put("y", position.y)
            put("z", position.z)
            val data = data
            if (data == null) {
                put("keys", JsonNull)
            } else {
                put("keys", stringArray(data.entries.keys.toList()))
            }
        }

        is ClientEvent.EntitySpawned -> with(event.entity) {
            put("id", id)
            put("type", type)
            put("name", customName)
            put("x", x)
            put("y", y)
            put("z", z)
            put("yaw", yaw)
            put("pitch", pitch)
        }

        is ClientEvent.EntityRemoved -> put("ids", intArray(event.ids))

        is ClientEvent.EntityAttributesUpdated -> {
            put("entityId", event.entityId)
            put(
                "changed",
                buildJsonArray {
                    for (attribute in event.changed) {
                        add(
                            buildJsonObject {
                                put("key", attribute.key)
                                put("value", attribute.value)
                            },
                        )
                    }
                },
            )
        }

        is ClientEvent.EntityDamaged -> {
            put("entityId", event.entityId)
            put("sourceType", event.sourceType)
            put("sourceCause", event.sourceCause)
            put("sourceDirect", event.sourceDirect)
        }

        is ClientEvent.EntityHealthChanged -> {
            put("entityId", event.entityId)
            put("health", event.health)
        }

        is ClientEvent.PassengersChanged -> {
            put("vehicleId", event.vehicleId)
            put("passengers", intArray(event.passengerIds))
        }

        is ClientEvent.VehicleChanged -> if (event.vehicleId == null) {
            put("vehicleId", JsonNull)
        } else {
            put("vehicleId", event.vehicleId)
        }

        is ClientEvent.PlayerListChanged -> {
            put(
                "updated",
                buildJsonArray {
                    for (player in event.updated) {
                        add(
                            buildJsonObject {
                                put("uuid", player.uuid.toString())
                                put("name", player.name)
                                put("gameMode", player.gameMode)
                                put("latencyMillis", player.latencyMillis)
                            },
                        )
                    }
                },
            )
            put("removed", stringArray(event.removed.map { it.toString() }))
        }

        is ClientEvent.ChatReceived -> with(event.message) {
            put("kind", kind.name)
            put("overlay", overlay)
            put("sender", sender?.toString())
            put("senderName", senderName)
            put("targetName", targetName)
            put("format", format?.key)
            put("text", text)
        }

        is ClientEvent.ChatDeleted -> put("signatureId", event.signatureId)

        is ClientEvent.InventoryChanged -> put("windowId", event.windowId)

        is ClientEvent.ContainerOpened -> {
            put("windowId", event.windowId)
            put("type", event.type)
            put("title", event.title)
        }

        is ClientEvent.ContainerClosed -> put("windowId", event.windowId)

        is ClientEvent.HeldSlotChanged -> put("slot", event.slot)

        is ClientEvent.StatusChanged -> with(event.status) {
            put("health", health)
            put("food", food)
            put("foodSaturation", foodSaturation)
            put("experienceBar", experienceBar)
            put("level", level)
            put("totalExperience", totalExperience)
        }

        is ClientEvent.AbilitiesChanged -> with(event.abilities) {
            put("invincible", invincible)
            put("canFly", canFly)
            put("flying", flying)
            put("creative", creative)
            put("flySpeed", flySpeed)
            put("walkSpeed", walkSpeed)
        }

        is ClientEvent.EntityEffectsChanged -> {
            put("entityId", event.entityId)
            put(
                "changed",
                buildJsonArray {
                    for (effect in event.changed) {
                        add(
                            buildJsonObject {
                                put("key", effect.key)
                                put("amplifier", effect.amplifier)
                                put("durationTicks", effect.durationTicks)
                            },
                        )
                    }
                },
            )
            put("removed", stringArray(event.removed))
        }

        is ClientEvent.ContainerPropertyChanged -> {
            put("windowId", event.windowId)
            put("property", event.property)
            put("value", event.value)
        }

        is ClientEvent.CommandTreeReceived -> {
            put("nodes", event.tree.size)
            put("root", event.tree.rootIndex)
            put("literals", stringArray(event.tree.topLevelLiterals))
        }

        is ClientEvent.CommandSuggestionsReceived -> with(event.suggestions) {
            put("id", transactionId)
            put("start", start)
            put("length", length)
            put("matches", stringArray(matches))
        }

        is ClientEvent.TransferRequested -> {
            put("host", event.host)
            put("port", event.port)
        }

        is ClientEvent.PluginMessageReceived -> with(event.message) {
            // 取一次副本即可：这里只用于计数与品牌解析。
            val bytes = data()
            put("channel", channel)
            put("bytes", bytes.size)
            if (channel == PluginMessages.BRAND_CHANNEL) {
                put("brand", PluginMessages.parseBrand(bytes))
            }
        }

        ClientEvent.Closed -> Unit

        is ClientEvent.Failed -> with(event.error) {
            put("kind", this::class.simpleName ?: "ClientException")
            put("message", message ?: "")
        }
    }
}

/** 字符串列表输出为 JSON 数组，顺序与协议下发顺序一致。 */
private fun stringArray(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

/** 整数列表输出为 JSON 数组。 */
private fun intArray(values: List<Int>): JsonArray = JsonArray(values.map(::JsonPrimitive))
