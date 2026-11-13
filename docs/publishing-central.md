# 发布到 Maven Central

本项目通过 **Central Portal** 发布：本地打一个符合 Maven 仓库布局的 zip（bundle），
再用 Portal 上传。Gradle 没有官方 Portal 插件，因此不走第三方插件，避免多一个
需要维护、需要写进 `verification-metadata.xml` 的构建依赖。

## 一、一次性的准备

1. **namespace**：`io.github.netheritepickaxe` 已通过 Central Portal 验证（命名空间页显示 Verified）。
2. **发布密钥**：用一把专用 GPG key（不要复用个人 key），把公钥发到 keyserver：
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`。
3. **GitHub secrets**（`Settings → Secrets and variables → Actions`）：

   | secret | 内容 |
   | --- | --- |
   | `SIGNING_KEY` | 导出的 armored 私钥（`gpg --armor --export-secret-keys <KEY_ID>`） |
   | `SIGNING_PASSWORD` | 私钥口令；无口令则留空 |
   | `MAVEN_CENTRAL_USERNAME` | Portal 的 User Token 用户名 |
   | `MAVEN_CENTRAL_PASSWORD` | Portal 的 User Token 口令 |

   User Token 在 <https://central.sonatype.com/account> 生成，不是登录密码。

## 二、本地生成 bundle

```bash
rm -rf build/staging-repo
SIGNING_KEY="$(cat release-key.asc)" SIGNING_PASSWORD="…" \
  ./gradlew "-Pversion=0.1.0" centralBundle --console=plain
# 产物：build/pickaxecraft-0.1.0.zip
```

`centralBundle` 会先执行 `verifyCentralBundle`，在打包前断言：

- 版本不是 `-SNAPSHOT`；
- 每个 jar / pom / module 都有 `.asc`、`.md5`、`.sha1`；
- bundle 只含 `io/…` 的发布构件，不含 `maven-metadata.xml`，也不含 `.asc` 自己的校验和。

任何一条不满足都会直接失败——Central 的发布不可撤回，缺签名不应该等到上传后才发现。

## 三、通过工作流发布

`Actions → Release to Maven Central → Run workflow`，填写：

- `version`：例如 `0.1.0`；
- `publishing_type`：`USER_MANAGED`（默认，校验通过后到 Portal 手动点发布）或
  `AUTOMATIC`（校验通过后直接发布）。

工作流会：`build` → 签名 bundle → 上传 Portal → 轮询状态。`FAILED` 时把 Portal 返回的
`errors` 打到日志；`USER_MANAGED` 在 `VALIDATED` 停下，并在 job summary 里给出
[部署页面](https://central.sonatype.com/publishing/deployments)。

## 四、发布之后

- `PUBLISHED` 后到 `repo1.maven.org` 可见通常还要十几分钟到半小时；
- 校验坐标：`https://repo1.maven.org/maven2/io/github/netheritepickaxe/client-core/`；
- 版本不可变：一旦发布，`0.1.0` 不能覆盖或删除，发错了只能发新版本；
- 消费方仍需声明 `https://repo.opencollab.dev/main/`（MCProtocolLib 不在 Central）。

## 五、仓库可见性

Maven Central 不要求源码仓库公开，私有仓库同样可以发布；`url` / `scm` 只是元数据，
不会在发布时被校验。但要注意：

- 发布物包含 **sources jar**，源码实际上已经公开；
- 私有仓库下 POM 里的 GitHub 链接对匿名用户是 404；
- 本项目是 MIT 开源，若希望 issue/源码历史可访问，建议转为公开；这属于发布策略，
  不影响 Central 的校验与上传。
