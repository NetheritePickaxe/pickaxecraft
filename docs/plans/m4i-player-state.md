# M4i 实施记录：自身状态（生命/饥饿/经验）与实体装备

日期：2026-11-04
前置：M4b（实体跟踪）、M4h（背包槽位模型，装备复用 `InventoryItem`）

## 1. 目标

把“邮件里已经收到、但被丢掉的字段”接上：

- **自身状态**：生命、饥饿、饱和度、经验条、等级、总经验。两个包各管一半。
- **实体装备**：主手/副手/四个护甲槽，26.2 还有 `BODY` 与 `SADDLE`。

## 2. 为什么装备能读、而血量不能

实体元数据是一张“下标 → 值”的表，下标含义取决于实体层级（Entity / LivingEntity / Mob），
协议不携带层级信息——这是 M4d 只读下标 2 的原因。**装备不受这个限制**：26.2 有独立的
`set_equipment` 包，槽位是显式枚举。因此装备能进 `TrackedEntity`，而生物血量仍然读不到。

自身血量走另一个独立包（`set_health`），与实体元数据无关，所以也能读。

## 3. 交付内容

### 3.1 协议层

- `TrackedEntity.equipment: Map<String, InventoryItem>`：槽位名 → 物品，空槽不出现。
  包里的物品为空表示“清空这个槽”，因此删键而不是写一个数量 0 的物品。
- `ProtocolEvent.HealthUpdated(health, food, saturation)` 与
  `ProtocolEvent.ExperienceUpdated(bar, level, total)`：两个包分别上报，合并交给上层。
- 装备变化复用 `EntityUpdated`：与位置、自定义名称走同一条“写缓存、按需查询”的路径。

### 3.2 客户端

- `PlayerStatus(health, food, foodSaturation, experienceBar, level, totalExperience)`：
  **字段全部可空**。两个包各管一半，先到的先填；用 `0` 当缺省会让“还没收到”和“真的是 0”
  混在一起。
- `Session.status: StateFlow<PlayerStatus>`，与 `world` 分开——它不是世界状态，也不随维度变化。
- `ClientEvent.StatusChanged(status)`：一个包一个事件，事件里带合并后的完整快照。
- 重生时状态归零：生命与经验属于“上一个身体”，服务端会重新下发。

### 3.3 验证

`ClientSessionTest.生命、饥饿、经验与装备对客户端可见`：

- 服务端设 `7.5` 生命、`12` 饥饿、`3.5` 饱和度，客户端三项都对上；
- 设等级 `5`、经验条 `0.5`，事件与快照都对上，且**不影响生命值**（两个包互不覆盖）；
- 总经验只断言“读得到”：Minestom 恒发 0，写死数值等于把服务端实现当成协议；
- 僵尸戴上金头盔：`equipment["HELMET"]` 的 id 与数量都对上；换回空气后槽位**消失**。

## 4. 未覆盖

- 属性表（`update_attributes`）：最大生命、移动速度、攻击力。没有它，血量只能给出数值，
  给不出“占最大值的比例”。
- 状态效果（`update_mob_effect` / `remove_mob_effect`）：药水效果与持续时间。
- 生物血量：需要实体层级信息，仍被元数据下标问题挡住。
- 死亡与重生流程（`player_combat_kill`、死亡界面）。
- 玩家列表里的 `PLAYER_LIST_ORDER` 等其余列。
