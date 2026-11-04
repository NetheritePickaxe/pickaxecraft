package org.pickaxe.protocol.entity

/**
 * 一个实体身上的状态效果（药水效果）。
 *
 * [key] 由协议里的数字 id 经 [EffectKeys] 还原；[durationTicks] 是服务端给的剩余刻数，
 * -1 表示无限（原版信标效果）。[amplifier] 从 0 起算，0 即一级。
 *
 * 协议只推**变化的那一个效果**：新增、替换与移除分成两个包，完整表由会话按键合并。
 * 四个显示标志是效果本身的属性，不是渲染策略，因此原样保留。
 */
public data class MobEffect(
    public val key: String,
    public val id: Int,
    public val amplifier: Int,
    public val durationTicks: Int,
    public val ambient: Boolean,
    public val showParticles: Boolean,
    public val showIcon: Boolean,
    public val blend: Boolean,
)
