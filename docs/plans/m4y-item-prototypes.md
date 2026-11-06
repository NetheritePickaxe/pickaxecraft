# M4y：物品原型默认值与可重跑导出

状态：已完成（2026-11-06）

## 目标

把 `max_damage`、`max_stack_size` 这类**物品固有属性**从「只读补丁」改成「原型默认值 + 补丁覆盖」，
修正「钻石剑耐久读到 `null`」这个真 bug；同时把版本资产的生产方式从「手工跑一次脚本」变成
可重跑的 Gradle 任务，为后续升级协议库留出可验证的动作。

M4 收尾计划（[m4-finish-plan](m4-finish-plan.md) §1）已确定范围，本文是可执行细化：
补上三条协议事实、把表形态与三态语义定下来、列出逐文件改动与验收。

## 0. 与 M4 收尾计划的差异

| 项 | 收尾计划 §1 | 本文 | 理由 |
| --- | --- | --- | --- |
| 表形态 | 稀疏表 + 查询 | 稠密 `IntArray`（长度 = 物品数，缺省 `-1`） | 1537 项 × 2 张表约 12 KB，O(1) 查表不需要二分；生成器也不必维护排序 |
| 补丁三态 | 「按 `get` 取值即可」 | 原型派生字段区分「没提 / 带值 / 显式删除」 | 协议库的 `DataComponents` 确实保留三态（`contains` 为真但 `get` 为 null 即删除），对状态型组件两者等价，对原型派生字段不等价 |
| 导出入口 | 未定 | `Registries.vanilla()` 触发的 `VanillaRegistries` 构造 | 已在源码里确认原型绑定点，导出器不需要启动服务端 |

## 1. 协议事实（已核对源码）

1. **补丁只带与原型不同的项。** 给钻石剑设 `max_damage = 1561`（恰好是原型默认值）时，补丁里
   根本没有这个键；设成 2000 才下发。这条已在 [m4s](m4s-item-components.md) 的联调里验证过。
2. **协议库保留三态。** `org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents`
   的类注释写明：patch 模式下 `null` 既可能是「没提」也可能是「删除」，要用 `contains(type)` 区分。
   `put(type, null)` 会写入 `type.readNullDataComponent()`，`contains` 因此为真。
   所以 M4s 里「两者对外结果一致」只对**状态型**组件成立（没有就是没有），对原型派生字段不成立。
3. **原型绑定发生在注册表初始化时。** `Material#prototype()` 读的是
   `RegistryData.MaterialEntry#prototype()`，未绑定时抛 `IllegalStateException("Should have been bound")`；
   绑定动作在 `VanillaRegistries` 构造末尾（`entry.registry().bindComponents(this)`），
   而 `Registries.vanilla()` 就是这个构造的公开入口。导出器调用一次即可，不需要 `MinecraftServer`。
4. **两个组件的默认值不同。** `max_stack_size` 缺省是 64（Minestom 自己的 `Material#maxStackSize()`
   就是 `prototype().get(MAX_STACK_SIZE, 64)`）；`max_damage` 缺省是「没有上限」，即不可损坏，
   原版语义是 `getOrDefault(0)`。
5. **物品 id 与 `Material` 的 id 一致。** `ItemKeys` 已按此导出（1537 项，id 连续 0..1536），
   锚点：`stone = 1`、`diamond_sword = 964`、`stick = 974`、`ender_pearl = 1144`。

## 2. 公共 API 与语义

```kotlin
public data class InventoryItem(
    // …既有字段…
    /** 该物品能承受多少耐久；不可损坏或 id 未知时为 null。原型默认值与补丁合并后的结果。 */
    public val maxDamage: Int? = null,
    /** 该物品类型的堆叠上限（1/16/64 等）；id 未知时为 null。 */
    public val maxStackSize: Int? = null,
)
```

三态到结果的映射（`InventoryItem.maxDamage` / `maxStackSize`）：

| 补丁状态 | `maxDamage` | `maxStackSize` |
| --- | --- | --- |
| 没提（`contains` 为假） | 原型值；原型也没有 → `null` | 原型值；原型也没有 → `64`（表内的已知值，不是猜的） |
| 带值 | 补丁值（覆盖原型） | 补丁值 |
| 显式删除（`contains` 为真、`get` 为 null） | `null` | `null` |
| id 超出物品表 | `null` | `null` |

实现时把「显式删除」统一收敛成 `null`（初稿表里给 `maxStackSize` 写的是 64）：删掉组件后
原版自己取什么值并不唯一（`getOrDefault` 的兜底在 64 与 1 之间有版本差异），本层不为它
挑一个默认值——这正是项目原则里「面对未知状态时不要猜测默认结果」。

明确**不**从原型补齐的字段：`damage`、`repair_cost`、`custom_name`、`lore`、`enchantments`。
它们是状态而不是固有属性，「没提」等于「没有」；用原型的 0 / 空值兜底会把「全新物品」和
「服务端没说」混成一件事。

## 3. 版本资产与导出任务

新增 `protocol-core/src/main/kotlin/org/pickaxe/protocol/item/ItemPrototypes.kt`（生成文件，
头注释写明 Minestom 版本、协议号、条目数与「不要手改」）：

```kotlin
internal object ItemPrototypes {
    /** 物品数（= ItemKeys 的条目数），用于越界判定。 */
    const val SIZE: Int = 1537

    /** 不可损坏或越界 → null。0 是合法值，原样返回。 */
    fun maxDamage(itemId: Int): Int?

    /** 越界 → null；无组件 → 64。 */
    fun maxStackSize(itemId: Int): Int?
}
```

表形态：两张 `IntArray(SIZE)`，缺省 `-1`（**不用 0**：0 与「没有组件」必须可区分），
`maxStackSize` 的无组件项同样写 `-1`、查询时回落 64；越界统一返回 `null`。

导出器放在 `testing:minestom`（唯一允许依赖 Minestom 的模块）：
`src/main/kotlin/org/pickaxe/testing/minestom/asset/ExportAssets.kt`，
带子命令的单一入口（`item-prototypes` 本期实现，后续 `items` / `attributes` / `effects` /
`living-types` 复用同一套写出逻辑，避免每张表再写一次脚手架）。

约束：

- 任务名 `exportItemPrototypes`，`group = "asset"`，**不挂到 `build`/`check`**，只在显式调用时运行；
- 输出路径作为参数传入，默认指向 `protocol-core` 里的生成文件；
- 先调 `Registries.vanilla()` 触发原型绑定，再遍历 `Material.values()`，按 id 升序写出；
- 读组件用 `has(...)` 判断存在性，不用 `get` 的默认值代替「没有这个组件」；
- 无时间戳、无随机序，二次运行零 diff；生成时断言 id 连续 `0..SIZE-1`，不连续直接失败（fail fast）。

## 4. 改动清单

| 文件 | 改动 |
| --- | --- |
| `protocol-core/.../item/ItemPrototypes.kt` | 新增（生成） |
| `protocol-core/.../internal/ItemComponents.kt` | `read` 合并原型值；新增三态判定小工具（`contains` + `get`） |
| `protocol-core/.../inventory/Container.kt` | `maxDamage` 语义改写；新增 `maxStackSize`；类注释区分「状态型组件两者等价」与「原型派生字段不等价」 |
| `testing/minestom/.../asset/ExportAssets.kt` | 新增导出器 |
| `testing/minestom/build.gradle.kts` | 注册 `JavaExec` 任务 `exportItemPrototypes` |
| `testing/minestom/.../Harness.kt` | 新增 `givePlainItem(username, material, count)`：不设任何组件 |
| `testing/minestom/.../ClientSessionTest.kt` | 新增联调用例（见 §5） |
| `protocol-core/src/test/.../item/ItemPrototypesTest.kt` | 新增锚点与越界 |
| `protocol-core/src/test/.../internal/ItemComponentsTest.kt` | 补三态用例；修订「没有补丁时全字段为默认」的断言 |
| `app/src/main/kotlin/Main.kt` | 物品摘要加 `maxStack=` |
| `docs/protocol-contract.md` | §2.27 重写（推翻「本项目不建模原型」的结论）、§6 第 4 条加入 `ItemPrototypes` |
| `docs/plans/m4s-item-components.md` | 在「实现时发现的事实」末尾加一条注记：原型补齐已由 M4y 交付 |
| `docs/roadmap.md` | M4 实际进展补 M4y；「仍未交付」段落相应收敛 |

## 5. 测试门控

单测：

- `ItemPrototypesTest`：锚点 `stone(1)` → `maxDamage = null`、`maxStackSize = 64`；
  `diamond_sword(964)` → `1561` / `1`；`stick(974)` → `null` / `64`；
  `ender_pearl(1144)` → `null` / `16`；越界 `-1`、`SIZE` → 两者皆 `null`。
- `ItemComponentsTest` 三态：普通方块（原型无 `max_damage`）读到 `null`；带附魔的钻石剑
  （补丁不含 `max_damage`）读到 `1561`；补丁显式删除 `max_damage` 时仍读到 `null` 而**不**回落原型；
  补丁带 `max_damage = 2000` 时读到 2000。

联调（Minestom）：

- `物品原型默认值可读`：harness 用 `givePlainItem` 塞入钻石剑（不设组件）、石头、末影珍珠，
  客户端分别断言 `1561/1`、`null/64`、`null/16`——这条用例在改动前会失败，正好钉住 bug。
- 既有 `物品组件可读且附魔键名来自动态注册表` 保持通过（补丁值 2000 仍覆盖原型）。

工具链：

- 连续运行 `exportItemPrototypes` 两次，`git diff` 为空（可重复性）；
- 生成文件条目数、物品数与 `ItemKeys` 一致（导出器内部断言）。

CLI：`connect ... --inventory` 摘要行出现 `maxDamage=1561 maxStack=1` 这类输出，肉眼可核。

## 6. 风险与遗留

- **`max_damage = 0` 的条目**：按「有组件就写 0」原样保留，不与缺失合并；`maxDamage = 0`
  表示「可损坏但没有耐久」，与 `null`（不可损坏）是两件事。
- **显式删除只做单测**：Minestom 的 `ItemStack.Builder` 没有构造「删除组件」的公开入口，
  联调无法覆盖，文档写明证据在单测一层。
- **原型数据的权威性来自 Minestom**：它若与真原版有差异，本表跟着错。M5 的本地原版服 smoke
  是这条假设的最终判据；在此之前 `docs/protocol-contract.md` 只声明「与 Minestom 数据对齐」。
- **其余四张表仍是手工导出**（`ItemKeys`、`AttributeTable`、`EffectKeys`、`LivingEntityTypes`）。
  本期只建立导出器与任务形态，统一迁移属于后续清理，不在 M4y 内扩张。

## 7. 交付顺序

1. 导出器 + Gradle 任务 + 生成 `ItemPrototypes.kt`，先证明可重复（本节 §3、§5 工具链部分）。
2. `ItemComponents` 合并逻辑与三态处理 + 单测。
3. `givePlainItem` + 联调用例 + CLI 摘要。
4. 文档同步（`protocol-contract` §2.27/§6、`m4s` 注记、`roadmap` 进度、工作记忆）。

四项完成后 M4 收尾计划只剩 M4z（插件消息）、M4aa（拖拽与创造模式）、M4ab（破坏进度）。

## 8. 实现时发现的事实

- **`Registries.vanilla()` 单跑就够**：不需要 `MinecraftServer.init()`，导出任务是一个普通
  `JavaExec`，整轮约 44 秒（含 Gradle 与首次加载原版数据）。
- **`max_stack_size` 是全集**：1537 件物品的**每一件**都在原型里显式声明了堆叠上限，
  所以「只存非 64 的项」的稀疏设想没有意义，表里写的是全部真实值（1 / 16 / 64 三种）；
  `max_damage` 只有 84 项。
- **旧用例反而盖住了 bug**：`giveComponentItem` 设的是非默认值 `max_damage = 2000`，
  补丁因此必然带上该键，`assertNull` 那条又用的是本来就没有耐久的石头——两条都测不到
  「普通钻石剑」。新用例改用不设任何组件的物品才暴露问题：把合并逻辑退回旧实现后，
  `物品原型默认值可读` 立即失败（已实测），恢复后通过。
- **生成文件的稠密数组没有超限**：两张 1537 元素的 `intArrayOf` 各约 110 行、方法体远小于
  64 KB，不需要拆成多个数组。
