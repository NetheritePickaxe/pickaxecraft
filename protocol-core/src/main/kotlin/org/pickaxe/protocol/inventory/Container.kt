package org.pickaxe.protocol.inventory

/**
 * 容器里的一个物品堆。
 *
 * [itemId] 是协议里的全局物品 id（`minecraft:item` 注册表下标），不是可读名字：
 * 名字与图标来自下游客户端的版本数据表，本库不持有。数量是服务端下发的堆叠数，
 * 空槽用 `null` 表示，而不是 `count = 0` 的物品。
 *
 * 物品组件（附魔、自定义名称、耐久）不在本层建模：它们是一张“组件 id → 值”的补丁表，
 * 解析它需要另一套注册表与类型系统，属于下游渲染与逻辑层。
 */
public data class InventoryItem(
    public val itemId: Int,
    public val count: Int,
)

/**
 * 一个窗口（容器）在某一时刻的内容。
 *
 * 不可变快照：每次服务端改动都会产生一个新对象，因此可以跨线程读取，也能直接当作缓存值。
 *
 * **槽位编号是窗口编号**，不是玩家背包编号：窗口 0 的 0 号是合成结果、1–4 是合成格、
 * 5–8 是盔甲、9–35 是主背包、36–44 是快捷栏、45 是副手（见 [PlayerInventoryLayout]）。
 * 打开箱子之类的容器时，前 N 个槽位是容器内容，其后 27 个是主背包、再后 9 个是快捷栏——
 * 这与服务端的编号一致，所以不需要任何换算。
 */
public data class Container(
    /** 协议内的窗口 id。0 恒为玩家自己的背包，其余由打开屏幕包分配。 */
    public val windowId: Int,
    /** 打开屏幕包给的容器类型名（如 `GENERIC_9X3`）；玩家背包没有这个包，为 null。 */
    public val type: String?,
    /** 窗口标题（已渲染成纯文本）；玩家背包没有标题，为 null。 */
    public val title: String?,
    /**
     * 服务端的内容状态号。
     *
     * 点击格子时原样带回（见 [SlotClick]），服务端据此判断“客户端看到的是不是最新内容”。
     * 本层不做校验，只负责随快照一起交出去：它属于内容，不属于连接。
     */
    public val stateId: Int,
    /** 按窗口槽位编号排列的内容；空槽为 null。长度由服务端下发的槽数决定。 */
    public val slots: List<InventoryItem?>,
    /**
     * 鼠标上的物品（服务端称 cursor）。
     *
     * 它属于玩家，但服务端只在当前打开的菜单上维护它，协议里也不带窗口 id，因此它记在
     * **活动窗口**的快照上：没开容器时是窗口 0，打开箱子时是那个箱子窗口。点击这个窗口的格子时，
     * 声明给服务端的光标就是这里这一份。
     */
    public val cursor: InventoryItem? = null,
) {
    /** 是否是玩家自己的背包窗口。 */
    public val isPlayerInventory: Boolean get() = windowId == PLAYER_WINDOW_ID

    /** 按窗口槽位编号取一个物品；越界或空槽返回 null。 */
    public fun slot(index: Int): InventoryItem? = slots.getOrNull(index)

    /** 当前非空的物品数量。 */
    public val usedSlots: Int get() = slots.count { it != null }

    public companion object {
        /** 玩家背包固定使用窗口 0。 */
        public const val PLAYER_WINDOW_ID: Int = 0
    }
}

/**
 * 玩家背包（窗口 0）的槽位布局。
 *
 * 这些数字来自协议的窗口编号，不是本库的发明：服务端在 `container_set_slot` 与
 * `container_set_content` 里用的就是它们。另一个编号体系（`set_player_inventory` 用的
 * “背包槽位”）与这里不同，换算由适配层负责，调用方只需要看这一套。
 */
public object PlayerInventoryLayout {
    /** 窗口 0 的槽位总数。 */
    public const val SIZE: Int = 46

    /** 主背包起始槽位（27 格，3 行 9 列）。 */
    public const val MAIN_FIRST: Int = 9

    /** 主背包槽位数。 */
    public const val MAIN_SIZE: Int = 27

    /** 快捷栏起始槽位（9 格）。 */
    public const val HOTBAR_FIRST: Int = 36

    /** 快捷栏槽位数，与 `selectHotbarSlot` 的取值范围一致。 */
    public const val HOTBAR_SIZE: Int = 9

    /** 副手槽位。 */
    public const val OFFHAND: Int = 45

    /** 快捷栏第 [index] 格（0–8）在窗口 0 里的槽位编号。 */
    public fun hotbar(index: Int): Int {
        require(index in 0 until HOTBAR_SIZE) { "快捷栏下标应在 0..${HOTBAR_SIZE - 1} 之间，实际为 $index" }
        return HOTBAR_FIRST + index
    }
}
