# M4q 实施记录：容器数值属性

日期：2026-11-04

## 目标

把熔炉进度、酿造时间这类「窗口上的数值」读出来，且不把协议下标误解释成具体含义。

## 协议事实

- `container_set_data` 只带三个数：窗口 id、short 属性下标、short 值。
- 下标含义**取决于窗口类型**：熔炉的 0/1/2/3 是燃烧剩余、燃烧总量、熔炼进度、总时长，
  酿造台与附魔台则完全不同。协议不带类型信息，翻译表属于玩法层，不应放进核心库。
- 一个包只更新一个下标，因此客户端必须按下标累积，不能整表替换。

## 实现

- `ProtocolEvent.ContainerPropertyChanged(windowId, property, value)`；适配层与会话各按
  「窗口 id → 下标 → 值」合并，走无锁缓存不占事件缓冲。
- `Session.containerProperties(windowId)` 给出累积快照；窗口关闭时移除该窗口的属性表，
  重生/关闭/溢出时一并清空。
- `ClientEvent.ContainerPropertyChanged` 用于需要严格时序的调用方。
- CLI 输出 `event=container.property`。

## 验证

- 联调 `容器数值属性可读且多个下标互不覆盖`：harness 下发两个
  `WindowPropertyPacket`（进度 75、总时长 200），断言事件值、累积表里两个下标都在、
  第一个值没被第二个覆盖。

## 边界

不做容器类型 → 下标语义的翻译；熔炉 UI 的显示逻辑属于下游客户端。
