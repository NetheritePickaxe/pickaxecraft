package org.pickaxe.protocol.world

import java.util.UUID

/**
 * 服务端玩家列表（Tab 列表）里的一项。
 *
 * 玩家列表回答的是“谁是谁”：它把 UUID 映射到用户名，并附带游戏模式与延迟。
 * 实体跟踪只能给出“那里有个 `PLAYER`”，名字必须靠这张表补上——两者用
 * [TrackedEntity.uuid] 关联起来。
 *
 * 不可变快照。服务端会分多次更新同一条目（先档案，再延迟，再游戏模式），
 * 适配层负责把增量合并成完整快照，上层拿到的永远是合并后的结果。
 *
 * 这是一个**会话级**事实，跨维度切换保留：换维度不会让人从 Tab 列表里消失。
 */
public data class RemotePlayer(
    /** 玩家档案 UUID，与 [TrackedEntity.uuid] 同源。 */
    public val uuid: UUID,
    /** 档案里的用户名；只更新了延迟等信息、还没收到档案时为 null。 */
    public val name: String?,
    /** 游戏模式名，取自协议枚举，例如 `SURVIVAL`。 */
    public val gameMode: String,
    /** 服务端上报的往返延迟（毫秒）。 */
    public val latencyMillis: Int,
)
