# 上游探针

回答一个问题：**MCProtocolLib 发新版后，已发布的 `client-core` 还能不能工作？**

它不属于根构建（不在 `settings.gradle.kts` 里），像外部工程一样从 staging repo 解析依赖，
再用 `-PmcplVersion` 强制覆盖 MCProtocolLib 的版本——这是探针唯一改变的变量。
因此失败通常意味着上游协议号或 API 前移，需要本仓库跟进，而不是本仓库代码回归。

## 覆盖的三步

1. `status`：握手与状态包仍能编码/解码；
2. 离线登录：登录状态机与配置阶段注册表仍能走通；
3. 进入世界并收到首批区块：适配层解码路径仍能工作。

## 本地运行

```bash
# 1. 发布产物
./gradlew -Pversion=0.1.0 publishAllPublicationsToStagingRepository :testing:minestom:installDist
# 2. 起测试服务端（另开一个终端）
./testing/minestom/build/install/minestom/bin/minestom --port 25565
# 3. 用指定 MCPL 版本探测
./gradlew -p tools/upstream-probe run -PmcplVersion=26.3-4 -PprobePort=25565
```

输出以 `probe=upstream` 开头：成功时 `ok=true` 并带区块数与修订号；失败时 `ok=false`
并带错误分类与消息。工作流（`.github/workflows/upstream.yml`）每周用最新版本跑一次，
失败只记录 issue 与摘要，不影响主分支 CI。

## 已知边界

探针只验证“当前协议号的离线链路”，不验证新版本的协议语义（例如新增字段的解析）；
那需要在升级适配层时由 E2E 与版本资产导出覆盖。
