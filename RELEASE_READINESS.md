# 发布就绪检查报告

[English](RELEASE_READINESS.en.md)

检查日期：2026-06-02

## 结论

h2db 已具备公开 GitHub Release 和 Maven Central 发布所需的基础材料。当前仓库侧已完成以下事项：

本轮待发布版本明确为 `2.3.0`。当前发布配置已将 `h2/gradle.properties` 中的 `version` 设置为 `2.3.0`。

- 开源材料：`LICENSE.txt`、`NOTICE.txt`、`README.md`、`CHANGELOG.md`、`CONTRIBUTING.md`、`SECURITY.md`、`SUPPORT.md`、`CODE_OF_CONDUCT.md`。
- 发布指南：`OPEN_SOURCE_RELEASE.md`、`MAVEN_CENTRAL_RELEASE.md`、`GITHUB_RELEASE.md`、`RELEASE_NOTES_TEMPLATE.md`。
- 第三方材料：`THIRD-PARTY-NOTICES.md`。
- 英文副本：所有面向发布的主文档均提供 `.en` 副本或英文 companion。
- Maven Central 产物：Gradle 能生成 main jar、sources jar、javadoc jar 和 POM。
- 许可证随包：main jar、sources jar、javadoc jar 均在 `META-INF/` 下包含 `LICENSE.txt` 和 `NOTICE.txt`。
- LongRun 发布包：已补齐独立长稳测试发布包、配置、脚本、报告和中英文文档；验收记录见 `docs/longrun/longrun-release-acceptance.md`。
- 凭据保护：`signing.properties`、`*.gpg`、`*.asc` 已被 Git 忽略，当前没有发布凭据或签名密钥被 Git 跟踪。

## 本地验证结果

在 `h2/` 下已执行：

```powershell
.\gradlew.bat clean jar sourceJar javadocJar generatePomFileForMavenPublication
.\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=build\test-m2-release-clean"
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat --rerun-tasks runLongRunJUnitCheck
.\gradlew.bat --rerun-tasks longRunTestDistZip
```

生成产物：

- `h2/build/libs/h2db-2.3.0.jar`
- `h2/build/libs/h2db-2.3.0-sources.jar`
- `h2/build/libs/h2db-2.3.0-javadoc.jar`
- `h2/build/publications/maven/pom-default.xml`

POM 已包含：

- `groupId`: `net.xdob.h2db`
- `artifactId`: `h2db`
- `licenses`: MPL 2.0 / EPL 1.0
- `developers`
- `scm`
- runtime dependencies

已确认 main jar、sources jar、javadoc jar 均包含：

- `META-INF/LICENSE.txt`
- `META-INF/NOTICE.txt`

LongRun 发布包相关验证：

- `runLongRunJUnitCheck` 通过。
- `longRunTestDistZip` 通过。
- Linux 真实环境 smoke 10 分钟验收通过：`PASS`，4 次 reopen，0 warnings，0 suspicious log lines。
- Linux 真实环境 crash/recovery 验收通过：`PASS`，15 个 crash cycle，29 次 recovery check，0 warnings，0 suspicious log lines。
- Linux 真实环境 fault-injection 验收通过：`PASS`，14 次 fault injection，0 unexpected，0 warnings，0 suspicious log lines。

## 发布者仍需在发布当天确认

- 确认 `h2/gradle.properties` 中的 `version` 为正式版本 `2.3.0`，不能以 `-SNAPSHOT` 发布 release。
- 确认 `net.xdob` 或实际使用的 Maven Central namespace 已在 Central Portal 验证。
- 确认 `README.md`、Release Notes 和 POM 中的版本号一致。
- 用真实 release 版本执行本地 dry run 和签名检查。
- 若本次 release 宣传 LongRun 发布包，发布前补跑 `longRunTestDistTar`，并从干净目录解压 zip/tar 包确认脚本权限和布局。
- 发布窗口允许时，补跑缩短版 nightly 或 comprehensive LongRun，以覆盖更长时间常压组合路径。
- 确认 Central Portal token、GPG 私钥和 passphrase 只存在本地安全文件或 CI secrets。
- 发布 Maven Central 后，在全新临时项目中执行 JDBC smoke test，再创建 Git tag 和 GitHub Release。

## 参考

- Maven Central Requirements: https://central.sonatype.org/publish/requirements/
- Central Portal: https://central.sonatype.org/register/central-portal/
- PGP signatures: https://central.sonatype.org/publish/requirements/gpg/
- Immutability: https://central.sonatype.org/publish/requirements/immutability/
