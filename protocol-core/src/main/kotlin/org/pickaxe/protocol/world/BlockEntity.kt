package org.pickaxe.protocol.world

/**
 * 一个方块实体：箱子、告示牌、熔炉、刷怪笼……
 *
 * [type] 是注册表键名（`minecraft:chest`）。协议里它只是一个数字 id，本库按
 * `BlockEntityType` 枚举还原成名字——这张表是随协议号固定的版本资产，和物品键名一样，
 * 升级协议库时必须核对（见 `docs/protocol-contract.md`）。
 *
 * [data] 是服务端下发的 NBT：告示牌的文字、刷怪笼的实体类型都在里面。
 * `null` 表示**这个包没有携带数据**，与服务端明确下发一个空复合标签不同；沿用
 * “空 ≠ 缺”的约定，不替服务端补一个空表。
 *
 * 方块被换成非方块实体时服务端不发“删除”包：`block_update` 到同一格就足以说明旧实体
 * 已经不存在，本库据此清掉旧条目（见 `ChunkColumn.withBlock`）。
 */
public data class BlockEntity(
    public val position: BlockPosition,
    public val type: String,
    public val data: NbtValue.Compound? = null,
)
