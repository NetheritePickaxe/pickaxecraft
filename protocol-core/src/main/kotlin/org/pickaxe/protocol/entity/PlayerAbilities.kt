package org.pickaxe.protocol.entity

/**
 * 服务端下发的本端能力。
 *
 * 来自 `player_abilities`：能力是**服务端权威状态**，不能由本端上行包推断。
 * 例如 `flying` 表示服务端目前认为玩家在飞，而 `Session.setFlying(true)` 只是本端的一个请求；
 * 服务端可能因为 `canFly=false` 而拒绝，它随后下发的这份快照才是结果。
 *
 * 速度是倍率（原版默认飞行 0.05、行走 0.1），不是格/秒。
 */
public data class PlayerAbilities(
    public val invincible: Boolean = false,
    public val canFly: Boolean = false,
    public val flying: Boolean = false,
    public val creative: Boolean = false,
    public val flySpeed: Float = 0f,
    public val walkSpeed: Float = 0f,
)
