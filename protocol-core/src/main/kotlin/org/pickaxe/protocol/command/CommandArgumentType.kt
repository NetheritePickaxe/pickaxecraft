package org.pickaxe.protocol.command

/**
 * 参数节点的参数类型（brigadier parser）。
 *
 * 这是**版本资产**：枚举项与协议库的 `CommandParser` 一一对应，靠名字而不是序号对齐
 * （所以新增项不会打乱既有值）。`CommandTreeParsingTest` 会断言两边集合一致——
 * 协议库升级后新增了类型而本层没跟上时，测试先失败，而不是运行时把参数当成未知。
 *
 * 名字刻意保持协议里的叫法（`BLOCK_POS` 而不是 `BLOCK_POSITION`），
 * 让日志里的类型名能直接与服务端/原版对账。
 */
public enum class CommandArgumentType {
    BOOL,
    FLOAT,
    DOUBLE,
    INTEGER,
    LONG,
    STRING,
    ENTITY,
    GAME_PROFILE,
    BLOCK_POS,
    COLUMN_POS,
    VEC3,
    VEC2,
    BLOCK_STATE,
    BLOCK_PREDICATE,
    ITEM_STACK,
    ITEM_PREDICATE,
    TEAM_COLOR,
    HEX_COLOR,
    COMPONENT,
    STYLE,
    MESSAGE,
    NBT_COMPOUND_TAG,
    NBT_TAG,
    NBT_PATH,
    OBJECTIVE,
    OBJECTIVE_CRITERIA,
    OPERATION,
    PARTICLE,
    ANGLE,
    ROTATION,
    SCOREBOARD_SLOT,
    SCORE_HOLDER,
    SWIZZLE,
    TEAM,
    ITEM_SLOT,
    ITEM_SLOTS,
    RESOURCE_LOCATION,
    FUNCTION,
    ENTITY_ANCHOR,
    INT_RANGE,
    FLOAT_RANGE,
    DIMENSION,
    GAMEMODE,
    TIME,
    RESOURCE_OR_TAG,
    RESOURCE_OR_TAG_KEY,
    RESOURCE,
    RESOURCE_KEY,
    RESOURCE_SELECTOR,
    TEMPLATE_MIRROR,
    TEMPLATE_ROTATION,
    HEIGHTMAP,
    LOOT_TABLE,
    LOOT_PREDICATE,
    LOOT_MODIFIER,
    DIALOG,
    UUID,

    /**
     * 协议库新增了本层还没有的参数类型。
     *
     * 只可能在升级协议库、忘了同步本枚举时出现（正常情况下测试会先失败）。
     * 它不参与序列化，也不该被用来构造节点——客户端只读树，不写树。
     */
    UNKNOWN,
}
