package org.pickaxe.protocol.internal

import org.cloudburstmc.nbt.NbtList
import org.cloudburstmc.nbt.NbtMap
import org.pickaxe.protocol.world.NbtValue

/**
 * 协议库的 NBT → 本库的 [NbtValue]。
 *
 * 转换而不是透传的原因只有一条：`NbtMap` 属于第三方库，不能出现在公共 API 上。
 * 转换本身不解释字段含义——告示牌的文字、刷怪笼的实体类型都由知道方块类型的调用方去读。
 *
 * 遇到未知值类型直接失败：NBT 的类型集合是封闭的，出现别的类型说明解码已经错位，
 * 悄悄丢掉会让一个“看起来正常”的方块实体缺少关键字段。
 */
internal fun NbtMap.toNbtCompound(): NbtValue.Compound =
    NbtValue.Compound(entries.associate { entry -> entry.key to entry.value.toNbtValue() })

private fun Any?.toNbtValue(): NbtValue = when (this) {
    is NbtMap -> toNbtCompound()
    is NbtList<*> -> NbtValue.Items(map { it.toNbtValue() })
    is String -> NbtValue.Text(this)
    is Number -> NbtValue.Num(this)
    // 三种数组标签：方块实体的 NBT 很小，展开成列表省掉三条分支。
    is ByteArray -> NbtValue.Items(map { NbtValue.Num(it) })
    is IntArray -> NbtValue.Items(map { NbtValue.Num(it) })
    is LongArray -> NbtValue.Items(map { NbtValue.Num(it) })
    else -> throw IllegalArgumentException("无法转换的 NBT 值：${this?.javaClass?.name}")
}
