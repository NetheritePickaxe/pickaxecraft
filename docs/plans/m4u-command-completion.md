# M4u：命令树与补全

## 目标

让调用方能读到服务端下发的命令树（有哪些命令、要什么参数），并能请求一次补全。
这两件事都只能由服务端回答：命令树随权限变化，候选依赖权限、世界状态与实体，
客户端本地推不出来。

## 协议事实

- `commands` 包是一个**扁平数组**：每个节点自带 `children` 下标数组与可选的重定向下标，
  根节点下标单独下发（通常 0，但协议不保证）。下标就是数组位置，引用关系全靠它。
- 节点用一个字节的标志位混合表达四件事：类型（root / literal / argument）、可执行、
  有重定向、受限；参数节点随后带 parser 序号与 parser 专有的附加字段。
- parser 有 57 项，顺序即 wire id。部分 parser 带附加属性：数值范围（各有“是否设置”位）、
  文本模式、实体选择器标志、记分板持有者、时间下限、资源注册表键。
- 数值范围未设置时线上填哨兵值：整型 `Integer.MIN_VALUE` / `Long.MAX_VALUE`，
  浮点 `±Float.MAX_VALUE` / `±Double.MAX_VALUE`——**两种浮点的哨兵值不是同一个数**。
- 补全请求带「请求编号 + 完整输入行（含斜杠）」；回答带回编号与 `start` / `length`，
  后者是**要替换的区间**，不是匹配项的位置。每个匹配项可带一个文本组件提示。
- 服务端算不出候选时**可以完全不回包**（Minestom 在没有补全回调时就是这样）。

## 设计

| 位置 | 职责 |
| --- | --- |
| `command/CommandTree.kt` | 只读树模型 + 导航：`node` / `childrenOf` / `redirectOf` / `topLevelLiterals` / `literalPaths` |
| `command/CommandArgumentType.kt` | 57 项参数类型（按名字对齐协议库）+ `UNKNOWN` 兜底 |
| `command/CommandArgumentProperty.kt` | 参数附加属性（范围、文本模式、实体、记分板、时间、资源） |
| `command/CommandSuggestions.kt` | 一次补全回答 + `applyTo` 把第一个候选套回原文 |
| `internal/CommandTrees.kt` | 协议库结构 → 本层模型（含哨兵值还原、缺节点不抛错） |
| `client-core/CompletionResult.kt` | 补全结果：`Suggestions` 与 `NoAnswer` 分开 |
| `client-core/Session.kt` | `commands` 快照、`complete` 挂起 API、请求编号与等待表 |

三个决定：

1. **下标原样保留。** 不下标重排、不展开成嵌套结构：协议里的引用是下标，任何“整理”
   都会让子节点与重定向指错地方。需要嵌套视图的调用方可以自己走 `childrenOf`。
2. **按名字对齐枚举。** 参数类型不按序号映射，上游新增类型不会让既有值整体错位；
   同时 `CommandTreeParsingTest` 直接对账两边集合，漏同步会在测试里先失败，而不是
   运行期悄悄退化成 `UNKNOWN`。
3. **`NoAnswer` 不是空候选。** 服务端不回包说明这条路径没有答案；回空列表说明它认可了
   请求但没有可选值。混成一个会让调用方误判“这里没有可选值”。等待用的 deferred 由
   网络线程直接完成，不经事件队列，消费者慢一点也不影响拿到答案；会话结束时以 `null`
   结束等待，调用方拿到 `NoAnswer`，而不是被取消。

## 测试门控

- `CommandTreeParsingTest`（13 例）：参数类型表与协议库取值双向对账、节点下标与数组位置一致、
  参数类型与范围还原、整型/浮点哨兵值各一侧为 null、文本模式与实体属性、
  重定向/受限/可执行/补全类型保留、字面量路径遇到参数就停（含上限与 0）、
  缺失下标不抛异常、按区间替换原文、越界时返回 null、提示渲染成纯文本、
  提示数量不一致时拒绝构造。
- 联调 `命令树与补全由服务端给出`：
  1. 出生后 `commands` 非空，harness 注册的命令在顶层字面量里；
  2. 逐层走到唯一的参数节点，类型是 `STRING`、属性是 `SINGLE_WORD`；
  3. `complete("/pickaxecolor r")` 返回的候选只有 `red`（服务端按前缀过滤过）；
  4. 回读 `text.substring(start, start + length)` 等于 `r`，`applyTo` 得到完整命令；
  5. `CommandSuggestionsReceived` 事件与挂起 API 拿到同一份数据；
  6. 服务端侧 `PlayerPacketEvent` 里有补全请求原文；
  7. 用候选拼出的命令能被服务端真正执行；
  8. 没有补全回调的命令在 800ms 内返回 `NoAnswer`。

## 实现时发现的事实

- Minestom 的 `TabCompleteListener` **会去掉前导斜杠**再交给命令解析器，但它的 `start` 仍按
  `lastSpace + 2` 算（隐含一个斜杠字符）。结果恰好与“带斜杠的原文”对齐：客户端发
  `/pickaxecolor r` 收到 `start=14, length=1`，正好指向那个 `r`。这依赖服务端的具体实现，
  所以联调用例断言的是“区间取出来等于已输入的前缀”，而不是硬编码下标。
- 补全回调里**不要用 `suggestion.start` 取前缀**：它的口径由服务端自己定。按
  `input.substringAfterLast(' ')` 切最后一个词更稳，也顺便处理了「输入以空格结尾」时
  Minestom 塞进来的 `\0` 占位符。
- 命令树在 `Player#spawn` 里随其他包一起下发，不需要客户端主动请求；权限变化时服务端会重发，
  所以会话里的快照是**替换**语义。
- `Session.complete` 在 `events.collect` 里直接调用是安全的：等待发生在调用方协程，
  Actor 照常推进事件，回答由网络线程交付。
- 一个只在真实运行时才暴露的问题：Git Bash 会把以 `/` 开头的命令行参数当成路径改写成
  `C:/Program Files/...`，实跑 CLI 验证补全时必须 `MSYS_NO_PATHCONV=1`，否则测的是
  一段被改写过的文本（表现为服务端永远 `NoAnswer`）。
