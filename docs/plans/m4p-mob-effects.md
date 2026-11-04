# M4p 实施记录：状态效果

日期：2026-11-04

## 目标

让调用方能按实体查询「现在身上有哪些药水效果」，并让新增、替换与移除都可见。

## 协议事实

- `update_mob_effect` / `remove_mob_effect` 每次只带**一个**效果，前者给数字 id、放大器、
  剩余刻数与四个显示标志（ambient / showParticles / showIcon / blend），后者只给 id。
- 数字 id = `Effect` 枚举 ordinal（协议库 `readEffect` 直接 `Effect.from(varint)`）。
  `minecraft:mob_effect` 注册表**不随登录下发**，键名只能按版本维护：本仓库固化为
  `EffectKeys`（协议 776 共 40 项，`minecraft:speed` → `breath_of_the_nautilus`），
  升级协议库必须重新核对。
- `duration = -1` 是无限时长（信标）；放大器从 0 起算（0 即一级）。
- 四个标志属于效果本身，不是渲染策略，原样保留；Minestom 的 `Potion` 用同一字节布局，
  可直接用于联调。

## 实现

- `MobEffect`（不可变快照）+ `EffectKeys`（版本资产）+ `ProtocolEvent.EffectsUpdated`
  （`changed` 与 `removed` 两个短列表）。
- 按键合并放在两处：适配层 `effects` 表与会话 `entityEffects` 缓存；都不占用有界事件队列。
- 移除实体、重生、关闭、溢出时清理对应表；移除效果只删被点名的键。
- `Session.effects(entityId)` / `ownEffects()` / `effect(entityId, key)` 与
  `ClientEvent.EntityEffectsChanged`。

## 验证

- `EffectKeysTest`（2 例）：表长度 40、四个锚点键、未知 id 返回 null。
- 联调 `状态效果可按键合并且移除只影响被点名的效果`：harness 直接下发两个
  `EntityEffectPacket` 与一个 `RemoveEntityEffectPacket`，断言放大器、时长、粒子位，
  合并后两个键都在，移除 speed 后 haste 仍在。

## 边界

不实现效果倒计时（服务端会重新下发或移除），不解释效果数值对移动/伤害的影响，
也不做效果图标渲染。
