# M4e 实施记录：聊天

状态：已实施（2026-11-04）
范围：[roadmap.md](../roadmap.md) 第 7 节 M4 的第五个增量
结论：会话能读聊天了。系统消息、玩家聊天、伪装聊天统一成 `ChatMessage`，保留发送者、
格式模板与删除编号；最近 200 条可回看，服务端撤回的消息会同步移除。

## 1. 起点：三个包，一种东西

协议用三个不同的包表达“说话”，字段差异比名字暗示的大：

| 包 | 发送者 | 正文 | 格式 | 可撤回 |
| --- | --- | --- | --- | --- |
| `ClientboundSystemChatPacket` | 无 | 文本组件 | 无 | 否 |
| `ClientboundPlayerChatPacket` | UUID + 显示名 | 字符串（另有可选的未签名组件） | `Holder<ChatType>` | 是（带编号） |
| `ClientboundDisguisedChatPacket` | 只有显示名 | 文本组件 | `Holder<ChatType>` | 否 |

因此“一条消息”在上层是 `ChatMessage`，用 `kind` 区分来源，其余字段按可用性填充。
把三者混成一个只带文本的 `String` 会丢掉“谁说的”和“能不能撤回”，这两件事都不可逆。

## 2. 格式：协议传模板键，不传整行

聊天包不包含拼好的 `<Alice> 你好`。它给的是一个格式引用，真正的排版在客户端：

- 引用有两种形态：注册表下标，或包内内联的完整定义（`Holder`）；
- 格式内容是 `translation_key` + 参数顺序，例如 `chat.type.text` 用 `[SENDER, CONTENT]`；
- 模板文本在语言文件里，**不在协议里**。

所以本层给出 `ChatFormat(key, parameters)`，由下游决定怎么排。这同时也决定了不做什么：
不内置翻译表、不猜“表情应该显示成 `* 名字 正文`”——那是渲染层的判断。

注册表 `minecraft:chat_type` 在配置阶段下发，条目顺序即包里的下标，与 `dimension_type`
一样不能重排。查不到时 `format` 为 `null`（服务端可能认为客户端内置了原版数据），
消息照常上报——少个模板比断线好。

## 3. 确认：观察者也有义务

收到玩家聊天后必须回 `ServerboundChatAckPacket`。它表示“我看到了这几条”，
服务端据此推进待确认窗口；不回时窗口只增不减，真实服务端会因此拒绝后续带签名的消息。

无头客户端不发消息，但仍然要维护这个窗口，所以确认在 `McplConnection` 里就地完成，
调用方看不到这个包。步长恒为 1：协议语义是“自上次确认以来又看到了几条”，逐条确认
没有攒批的必要。

Minestom 没有注册这个包的处理逻辑，日志里会出现
`ClientChatAckPacket:PLAY does not have any default listener!`——这条警告本身就是
“确认确实发出去了”的服务端侧证据。

## 4. 删除与编号

`ClientboundDeleteChatPacket` 只能撤回**带签名**的玩家消息，引用的是服务端为消息分配的
编号。本层把 `player_chat` 的 `index` 当作这个编号存进 `signatureId`，删除时按编号从
聊天记录里摘掉那一条：

- 没有签名的消息（服务端关闭安全聊天）不给编号——给了编号等于承诺“可以被撤回”；
- 系统消息与伪装消息在协议里没有编号，删不到，也不该被删到；
- 找不到对应编号时只发事件、不动记录：删除事件本身仍然是事实。

## 5. 分层与存储

| 层 | 职责 |
| --- | --- |
| `ChatTypeRegistry` | 解析 `chat_type` 注册表；解析 `Holder` 引用；未知占位符跳过 |
| `McplConnection` | 三个包 → `ChatMessage`；回送确认；删除包 → 编号 |
| `Session` | 追加到有界聊天记录（默认 200 条）并投递事件；删除按编号移除 |
| 调用方 | 读 `Session.chat()` 回看，或消费事件流做即时反应 |

聊天**走事件流**，不像区块、实体那样只写缓存：它是“发生过一次”的事实，不能被覆盖，
也不可重建。代价是刷屏服务端可能把有界缓冲打满——这是本项目一致的策略（溢出即终止），
不在这里开例外；`chatHistorySize` 只限制历史，不改变投递语义。

文本一律渲染成纯文本：颜色、粗体、点击事件不保留。样式属于渲染层，本库不引入渲染依赖。
可翻译组件（例如死亡消息）仍会得到键名而不是人话，因为客户端没有语言文件——已知限制。

## 6. 验证

`ChatTypeRegistryTest`（9 例）：翻译键与参数顺序、缺 `parameters`、未知占位符跳过、
缺 NBT / 缺 `chat` / 缺 `translation_key`、下标解析、下标越界返回 `null`、内联格式。

`ClientSessionTest.聊天消息可读且能按编号删除`（真实 Minestom）：

- 系统消息：`kind=SYSTEM`、`overlay=false`、没有发送者；
- 动作栏：同一个包 + `overlay=true`，靠 `overlay` 区分；
- 玩家消息：`sender` 是 UUID、`senderName` 是显示名、`signatureId` 是编号、`timestamp` 非空，
  格式键与参数从服务端注册表反查后一致（`chat.type.text` / `[SENDER, CONTENT]`）；
- 伪装消息：有 `senderName`、没有 `sender`，格式与普通聊天同为 `chat.type.text`；
- 聊天记录按到达顺序保存；删除后只剩被撤回那条消失，其余顺序不变；
- 关闭会话后 `chatCount == 0`。

CLI 现场证据（命令行服务端每 3 秒广播，每 2 轮补一条伪装聊天）：

```text
event=chat kind=system fmt=null text=服务器广播 #4
event=chat kind=disguised 控制台 fmt=chat.type.announcement text=控制台播报 #4
event=chat kind=system fmt=null text=服务器广播 #5
event=chat kind=system fmt=null text=服务器广播 #6
event=chat kind=disguised 控制台 fmt=chat.type.announcement text=控制台播报 #6
```

`fmt=null` 与 `fmt=chat.type.announcement` 的差别是这一行最值钱的部分：格式确实来自
注册表解析，而不是写死的默认值。

## 7. 与计划的偏差

| 计划（M4d 结束时的设想） | 实际做法 | 原因 |
| --- | --- | --- |
| 只做接收或同时做上行 | 只做接收 | 上行需要签名（在线模式）或走命令包，属于独立议题 |
| 聊天只当事件流投递 | 事件流 + 有界记录 | 事件是一次性通知，回看需要记录；两者都不昂贵 |
| 直接给拼好的整行文本 | 给键与参数 | 模板文本不在协议里，拼行需要语言表 |
| 校验消息签名 | 不校验 | 无头客户端不发消息，没有可被冒用的会话；签名只用来对删除编号 |

## 8. 已知限制与下一步

- 样式（颜色、粗体、点击/悬停事件）不保留；需要样式的下游应当自己解码组件。
- 可翻译组件显示为键名（没有语言文件）。
- 动作栏在 26.2 也有独立的 `ActionBarPacket`，本层还没处理；`SystemChatPacket.overlay`
  只是旧路径。
- 消息删除只按编号匹配，不校验签名本身；服务端若使用“全量签名”形式撤回，只能看到事件。
- 聊天上行（发送消息）未做：离线模式可以发不带签名的消息，在线模式需要会话密钥。
