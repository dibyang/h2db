# 发布就绪检查报告

[English](RELEASE_READINESS.en.md)

检查日期：2026-05-29

## 结论

h2db 已具备公开 GitHub Release 和 Maven Central 发布所需的基础材料。当前仓库侧已完成以下事项：

- 开源材料：`LICENSE.txt`、`NOTICE.txt`、`README.md`、`CHANGELOG.md`、`CONTRIBUTING.md`、`SECURITY.md`、`SUPPORT.md`、`CODE_OF_CONDUCT.md`。
- 发布指南：`OPEN_SOURCE_RELEASE.md`、`MAVEN_CENTRAL_RELEASE.md`、`GITHUB_RELEASE.md`、`RELEASE_NOTES_TEMPLATE.md`。
- 第三方材料：`THIRD-PARTY-NOTICES.md`。
- 英文副本：所有面向发布的主文档均提供 `.en` 副本或英文 companion。
- Maven Central 产物：Gradle 能生成 main jar、sources jar、javadoc jar 和 POM。
- 许可证随包：main jar、sources jar、javadoc jar 均在 `META-INF/` 下包含 `LICENSE.txt` 和 `NOTICE.txt`。
- 凭据保护：`signing.properties`、`*.gpg`、`*.asc` 已被 Git 忽略，当前没有发布凭据或签名密钥被 Git 跟踪。

## 本地验证结果

在 `h2/` 下已执行：

```powershell
.\gradlew.bat clean jar sourceJar javadocJar generatePomFileForMavenPublication
.\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=build\test-m2-release-clean"
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
```

生成产物：

- `h2/build/libs/h2db-2.2.10-SNAPSHOT.jar`
- `h2/build/libs/h2db-2.2.10-SNAPSHOT-sources.jar`
- `h2/build/libs/h2db-2.2.10-SNAPSHOT-javadoc.jar`
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

## 发布者仍需在发布当天确认

- 将 `h2/gradle.properties` 中的 `version` 改为正式版本，不能以 `-SNAPSHOT` 发布 release。
- 确认 `net.xdob` 或实际使用的 Maven Central namespace 已在 Central Portal 验证。
- 确认 `README.md`、Release Notes 和 POM 中的版本号一致。
- 用真实 release 版本执行本地 dry run 和签名检查。
- 确认 Central Portal token、GPG 私钥和 passphrase 只存在本地安全文件或 CI secrets。
- 发布 Maven Central 后，在全新临时项目中执行 JDBC smoke test，再创建 Git tag 和 GitHub Release。

## 参考

- Maven Central Requirements: https://central.sonatype.org/publish/requirements/
- Central Portal: https://central.sonatype.org/register/central-portal/
- PGP signatures: https://central.sonatype.org/publish/requirements/gpg/
- Immutability: https://central.sonatype.org/publish/requirements/immutability/
