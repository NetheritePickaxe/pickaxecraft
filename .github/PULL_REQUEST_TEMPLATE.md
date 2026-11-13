## 这个 PR 做了什么

<!-- 一两句话说清问题与结果，不要只贴 AI 生成的摘要。 -->

## 为什么这样做

<!-- 问题背景、取舍、被放弃的方案；如果只是机械改动，写清依据。 -->

## 修改范围

- [ ] `protocol-core`
- [ ] `client-core`
- [ ] `app`（CLI）
- [ ] `testing:minestom`（联调/测试）
- [ ] `samples:libgdx-viewer`
- [ ] 构建约定（`buildSrc`）、CI 或依赖版本
- [ ] 文档

## 验证

<!-- 写你实际跑过的命令与结果，而不是“应该能过”。 -->

```text
.\gradlew.bat build --console=plain
```

- [ ] `build` 通过（含测试与模块边界检查）
- [ ] 涉及会话/协议时：`:testing:minestom:test` 通过
- [ ] 涉及发布产物时：`tools/release-smoke/run.ps1` 通过
- [ ] 改动不适用时说明原因

## 兼容与风险

- [ ] 公共 API 变更（0.x 阶段允许，但必须在 `CHANGELOG.md` 记录）
- [ ] 依赖新增/升级（同步更新 `gradle/verification-metadata.xml` 与 `THIRD-PARTY-NOTICES.md`）
- [ ] 行为或性能变化，以及如何回滚

## Checklist

- [ ] 我已读过 `CONTRIBUTING.md` 并以自己的话说明了本次修改
- [ ] 文档（README / `docs/`）与实现一致
- [ ] 新增测试只覆盖可观察行为、非平凡不变式或已修复缺陷
