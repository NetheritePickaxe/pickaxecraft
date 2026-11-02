# 上游数据许可证与来源

本目录下的 `26.2/version.json` 与 `26.2/protocol.json` 来自 PrismarineJS/minecraft-data，
按上游 README 的 License 章节以 MIT 条款引入。

## 上游声明原文

> ## License
>
> MIT
>
> Some of the data was extracted manually or automatically from wiki.vg and
> minecraft.gamepedia.com. If required by one of the sources the license might
> change to something more appropriate.

上游在该提交下未提供独立 LICENSE 文件，许可证仅以 README 声明与 npm 包元数据
（`"license": "MIT"`）形式存在。

## 来源

| 项 | 值 |
| --- | --- |
| 仓库 | https://github.com/PrismarineJS/minecraft-data |
| 分支 | `pc_26_2` |
| 提交 | `282f19b06c7cc98f055c39787acf93904b9587fa` |
| 抓取日期 | 2026-11-02 |
| 抓取路径 | `data/pc/26.2/version.json`、`data/pc/26.2/protocol.json` |

## 校验和

| 文件 | 字节数 | SHA-256 |
| --- | --- | --- |
| `version.json` | 104 | `e4a731efdc228a6dafd61eb842e8eb76f9d6b766979254d743e161b86d7c1d0c` |
| `protocol.json` | 338768 | `e19d839857e4fc9faaa829e68736eaab181f21167db1b0449d3abb284b47305d` |

`protocol.json` 的 SHA-256 由 `tools:protocol-generator` 在构建时重新计算，既写入生成的
`ProtocolVersion.SCHEMA_SHA256`，也与 `source.json` 中的 `protocolSha256` 比对。
两者不一致（数据被改动、被行尾转换破坏、或来源换了）会直接让 `generateProtocolVersion`
失败，而不是静默生成一份与原版不同的常量。

## 为什么保留副本

26.2 的数据不在 `minecraft-data` 主干（主干只到 26.1），只存在于 `pc_26_2` 分支。
上游分支可能被重写或删除，构建不能依赖网络，因此这里保留原始副本，
并在 `.gitattributes` 中禁止行尾转换，保证克隆后字节一致。
