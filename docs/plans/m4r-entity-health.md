# M4r：生物血量读取

## 目标

让 `Session.health(entityId)` 能读到僵尸、玩家等生物的生命值，同时保证掉落物、船、
展示实体这类**非生物**不会被同一下标的无关字段误伤。

## 协议事实

- `set_entity_data` 是一张「下标 → 值」的表。下标由协议版本固定，但**含义取决于实体类层级**：
  `Entity` 层占 0–7（自定义名称是 2），`LivingEntity` 层从 8 开始，血量是该层的第 1 项，
  即绝对下标 9（Minestom `MetadataDef.LivingEntity.HEALTH`）。
- 协议包只带实体 id、下标和值，**不带层级**。同一个下标 9 在 `Interaction` / `ItemDisplay`
  上是 `view_range` 之类的 float，直接当成血量会读出看似合理的错误值。
- 玩家**自己**的血量不经元数据：那是 `set_health` 包，落在 `Session.status`（M4i）。
  元数据里的 health 描述的是视野中的其它生物。
- 服务端只在血量真的变化时下发该字段，出生时可能是默认值而不发；因此查询接口必须允许
  `null` = 「还没收到过 / 这类实体没有血量」，不能拿 0 或 1 兜底。

## 设计

- `LivingEntityTypes`（`protocol-core/.../entity/`）是版本资产：由 Minestom
  `2026.10.07-26.2` 的 `entity_type` 注册表导出，判据是
  `RegistryData.EntityEntry#shouldSendAttributes()`（`packetType` 为 `LIVING` 或 `PLAYER`）。
  协议 776 共 158 种实体类型，其中 93 种属于生物层级；导出时已与 MCPL 的 `EntityType`
  枚举名逐项核对，两边 158 项完全一致。升级协议库 / Minestom 必须重导整表。
- 名字约定：注册表键去掉命名空间后转大写（`minecraft:zombie` → `ZOMBIE`），与
  `TrackedEntity.type` 直接对齐，不需要第二张映射表。
- 不在表内的类型一律返回 `false`——不猜层级，血量留空（fail fast，不做静默兜底）。
- `TrackedEntity.health: Float?` 与其它实体事实一起放进不可变快照；`Session.health(id)`
  只是它的便捷入口。
- 血量变化额外发 `ClientEvent.EntityHealthChanged`：位置增量每秒上百条不进事件流，但血量
  是低频里程碑。适配层先发 `EntityUpdated`（写缓存），再发 `EntityHealthChanged`，
  消费者收到事件时 `session.entity(id)?.health` 已是新值。

## 测试门控

- 单测 `LivingEntityTypesTest`：生物/玩家/盔甲架/末影龙/人偶为真；掉落物、船、箭、
  `ItemDisplay`、`Interaction`、未知类型与空串为假。
- 联调 `生物血量按实体层级读取而非只看元数据下标`：
  1. harness 生成僵尸，客户端按 `entityId` 等它出现；
  2. 服务端 `setEntityHealth(zombie, 7.5f)` → `LivingEntityMeta` → `set_entity_data`，
     客户端 `session.health(id) == 7.5f` 且事件流收到同一条 `EntityHealthChanged`；
  3. 再生成一块掉落物 `ItemEntity`，断言 `session.health(id) == null` 且没有血量事件。
- 服务端侧日志 `event=server.health id=... health=7.5` 是「服务端确实改了」的独立证据。

## 实现时发现的事实

- `EntityCreature` 与 `ItemEntity` 都有 `getInstance()`；在
  `ItemEntity(...).apply { setInstance(instance, ...) }` 里，`instance` 会解析成**实体自己**
  的 null 属性而不是测试类的世界字段，Minestom 随即抛
  `Cannot invoke "Instance.isRegistered()" because "instance" is null`。
  把世界先存进局部变量可以避免这个隐式接收者遮蔽（harness 的 `spawnZombie` 用函数参数则无此问题）。
- Minestom 的 `LivingEntity#setHealth` 会用 `max_health` 属性截断目标值，联调取值必须小于
  默认上限 20；设 0 会直接触发死亡流程，不适合作为「血量变化」的样本。
- 新增实体暴露了两处既有的测试脆弱性，已一并修掉：
  - 测试类的 `instance` 由所有用例共享，`攻击与交互` 用例原来用
    `entities().values.first { type == "ZOMBIE" }` 取目标，会拿到本增量留下的另一只僵尸，
    导致属性断言超时；改为按服务端给的 `zombie.entityId` 精确定位，本增量用完也主动 `remove()`。
  - `放置方块` 用例对事件流仍是「查一次快照就断言」，但方块缓存由网络线程旁路先写、
    `BlocksChanged` 随后才排进 Actor，CI 负载下会抢跑；改成 `awaitCondition` 等待式。
    这与 `0c140a1` 修挖掘用例是同一个竞态。
