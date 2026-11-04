package org.pickaxe.client

/**
 * 本端玩家的生命、饥饿与经验。
 *
 * 字段可空，因为两个包各管一半：生命/饥饿一个包，经验条/等级/总经验一个包。先到的先填，
 * 另一半要等下一个包——用 `0` 之类的中性值代替会让“还没收到”和“真的是 0”混在一起。
 *
 * 这是**服务端下发的状态**，不是本端计算的结果：客户端不做伤害与饥饿模拟。
 */
public data class PlayerStatus(
    /** 当前生命值（点）。最大值的来源是属性表，本层不解析属性，因此不给上限。 */
    public val health: Float? = null,
    /** 饥饿值（0–20）。 */
    public val food: Int? = null,
    /** 饱和度，影响饥饿消耗速度。 */
    public val foodSaturation: Float? = null,
    /** 经验条进度（0–1）。 */
    public val experienceBar: Float? = null,
    /** 经验等级。 */
    public val level: Int? = null,
    /** 累计经验总量。 */
    public val totalExperience: Int? = null,
)
