package org.pickaxe.protocol.entity

/**
 * 本端上行的一组运动按键状态。
 *
 * 26.2 把按键放在 `player_input` 包里：一个字节同时声明前进/后退/左右/跳跃/潜行/冲刺。
 * 它是**整体状态**而不是事件，服务端按最近一次收到的字节为准；因此本类是不可变快照，
 * 每次改动都整份下发，不做“按键增量”。
 *
 * 冲刺还有一条独立的 `player_command`（START/STOP_SPRINTING）语义，由
 * `Session.setSprinting` 同时处理；潜行与飞行则分别由 `player_input` 的 shift 位和
 * `player_abilities` 上行包表达。
 */
public data class MovementInput(
    public val forward: Boolean = false,
    public val backward: Boolean = false,
    public val left: Boolean = false,
    public val right: Boolean = false,
    public val jump: Boolean = false,
    public val sneaking: Boolean = false,
    public val sprinting: Boolean = false,
) {
    init {
        require(!(forward && backward)) { "forward 与 backward 不能同时按下" }
        require(!(left && right)) { "left 与 right 不能同时按下" }
    }
}
