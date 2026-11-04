# M4l：实体属性表

## 目标

服务端会在生物生成与属性变化时下发 `update_attributes`。本增量把它变成可读数据：客户端能查
任意实体的属性（最大生命、移动速度……），属性变化时收到事件。

## 协议事实

- `update_attributes`：实体 id + 一组「属性 id → 值 + 修改器列表」。**每次只带被更新的那些**，
  不是全量快照——出生时发全量，之后改动只发改动的项，客户端必须按键合并。
- 属性 id 是 varint，协议库内置注册表能转成 `minecraft:max_health` 这样的键名。
- 修改器：资源键 + 数值 + 操作（ADD / ADD_MULTIPLIED_BASE / ADD_MULTIPLIED_TOTAL）。
  本库只透传，不代算加成后的值——服务端下发的 value 已经是算好的结果。

## 设计

- `EntityAttribute(key, value, modifiers)` 公共类型；修改器只带 id / amount / operation 名。
- 适配层按实体 id 累积：`Map<entityId, Map<key, EntityAttribute>>`，整键替换不改其它键。
- 事件 `AttributesUpdated(entityId, changed)` 只带本包出现的项；`Session` 合并后另发
  `EntityAttributesUpdated`。
- 读接口：`Session.attributes(entityId)`（整表快照）与 `Session.attribute(entityId, key)`。
- 会话关闭时清空。

## 测试门控

- 单测：修改器操作名映射、按键合并不丢其它属性。
- 联调：出生后自查 `minecraft:max_health == 20.0`；harness 把自己的 max_health 基值改成 40，
  客户端等事件后读 40。服务端属性变化会**主动推给自己**（Minestom `onAttributeChanged`
  在 PLAY 阶段直接发给本人），不需要别的观察者。

## 6. 实现时发现的事实

### 6.1 Minestom 的属性实例按需创建

出生时服务端确实会发属性包（`Player#placeNewPlayer` 里 `sendPacket(getPropertiesPacket())`），
但属性实例是**touch 时才注册**的——刚出生的玩家属性表为空，这个包里一个属性都没有。
「出生即读到默认值」在 Minestom 上不成立（真实原版服务端会带全量），联调用例只断言
「改基值后读得到」。僵尸同理。

### 6.2 协议库的属性内置表会错位

MCPL 26.2 的 `AttributeType.Builtin.BUILTIN` 按**枚举 ordinal** 反查 wire id
（`BUILTIN.put(attribute.ordinal(), ...)`），但枚举构造参数里记录的 id 是旧版本的：
构造参数说 armor=0、air_drag=1，26.2 的真实 wire id 是 air_drag=0、armor=1
（恰好整表按字母序排列）。直接用 `attribute.type.identifier` 会把 `minecraft:max_health`
读成别的键。解法：wire id 可以无损还原——`BUILTIN.get(wireId)` 返回的就是
「ordinal == wireId」的枚举实例，所以 `type.id`（返回 ordinal）就是 wire id；
再用本仓库从 Minestom 注册表导出的 `AttributeTable`（40 项，协议 776）查键名。
协议库升级时必须重跑导出脚本核对这张表。
