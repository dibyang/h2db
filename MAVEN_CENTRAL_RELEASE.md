# Maven Central 发布指南

[English](MAVEN_CENTRAL_RELEASE.en.md)

本文用于指导 h2db 通过 Gradle `maven-publish` 发布到 Maven Central，供外部用户使用。

Maven Central release 基本不可变。如果发布了有问题的版本，通常只能发布新的修复版本，而不是修改或删除旧版本。

## 当前项目状态

本仓库使用 Gradle 标准 `maven-publish` 插件发布。Gradle 工程已经能生成 Maven Central 通常要求的 main jar、sources jar、javadoc jar 和 POM。

```sh
cd h2
./gradlew clean jar sourceJar javadocJar generatePomFileForMavenPublication
```

当前生成的 POM 声明：

* `groupId`: `net.xdob.h2db`
* `artifactId`: `h2db`
* `version`: 来自 `h2/gradle.properties`
* licenses: `MPL 2.0` 和 `EPL 1.0`
* SCM URL: `https://github.com/dibyang/h2db`
* developer: `dib.yang`

首次公开发布前请确认这些值。

## Maven Central 要求

发布前请对生成产物确认：

* 版本是正式 release 版本，不是 `-SNAPSHOT`。
* 每个 binary artifact 都有对应 sources jar 和 javadoc jar。
* 每个部署文件都有所需 checksums。
* 每个部署文件都用 GPG/PGP 签名，并有对应 `.asc` 文件。
* POM 包含有效坐标、项目名、描述、URL、许可证、developer 信息和 SCM 信息。
* 所选 `groupId` 的 namespace 已在 Sonatype Central Portal 验证。

官方参考：

* https://central.sonatype.org/publish/requirements/
* https://central.sonatype.org/register/central-portal/
* https://central.sonatype.org/publish/requirements/gpg/
* https://central.sonatype.org/publish/requirements/immutability/

## 维护者一次性准备

1. 创建或确认 h2db 的公开 GitHub 仓库。
2. 验证计划使用的 `groupId` 对应 Maven Central namespace。
3. 创建用于发布签名的 GPG/PGP key。
4. 发布 public key，方便 Central 用户验证签名。
5. 创建 Maven Central user token 或 repository credentials。
6. 在本地配置 `h2/signing.properties`。
7. 凭据只放在本地受保护文件或 CI secrets 中。

不要提交 signing key、passphrase、Central token 或 repository credentials。

`h2/signing.properties` 已被 Git 忽略。请只保留在本地，文档中使用占位值。

```properties
releasesRepository=https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/
snapshotsRepository=https://ossrh-staging-api.central.sonatype.com/content/repositories/snapshots/

signing.keyId=YOUR_KEY_ID
signing.password=YOUR_GPG_PASSPHRASE
signing.secretKeyRingFile=/path/to/secret-keyring.gpg

ossrhUsername=YOUR_CENTRAL_TOKEN_USERNAME
ossrhPassword=YOUR_CENTRAL_TOKEN_PASSWORD
```

## 发布前检查

在 `h2/` 下执行：

```sh
./gradlew clean compileJava jar sourceJar javadocJar generatePomFileForMavenPublication
```

然后检查：

* `build/publications/maven/pom-default.xml`
* `build/libs/*`
* 生成的 source 和 javadoc jar

确认：

* POM 许可证元数据与 `LICENSE.txt` 一致。
* POM SCM URL 指向本 fork。
* POM developer 联系方式适合外部用户。
* README dependency snippet 与 release version 一致。
* `THIRD-PARTY-NOTICES.md` 与已解析依赖图一致。
* 没有 release credential、signing key 或 passphrase 被 Git 跟踪。

## 本地 Dry Run

发布到外部仓库前，先发布到本地 Maven 仓库。

```sh
./gradlew clean publishToMavenLocal "-Pversion=2.2.10" "-Dmaven.repo.local=build/test-m2-release-clean"
```

检查本地 Maven 仓库中的文件：

* `h2db-<version>.jar`
* `h2db-<version>-sources.jar`
* `h2db-<version>-javadoc.jar`
* `h2db-<version>.pom`
* 正式版本应有对应 `.asc` 签名。

本构建禁用 Gradle Module Metadata 生成，因为当前发布路径面向 Maven Central 的标准 Maven 产物。

## 使用 maven-publish 发布

构建会从 properties 读取仓库 URL 和凭据：

* `releasesRepository`
* `snapshotsRepository`
* `ossrhUsername`
* `ossrhPassword`

`-SNAPSHOT` 版本发布到 `snapshotsRepository`；正式版本发布到 `releasesRepository`。

snapshot 构建默认不挂载签名产物，以避免旧本地产物或 Gradle 6 module metadata 签名问题。正式 release 版本会挂载并生成签名。如果 snapshot 仓库要求签名，可使用 `-PsignSnapshots`。

正式发布命令：

```sh
./gradlew clean publish "-Pversion=2.2.10"
```

只有在本地 dry run 已确认仓库布局、签名和 POM 元数据都通过检查后，才执行外部发布。

## 发布后验证

Maven Central 校验并发布后：

1. 创建一个全新的临时项目。
2. 从 Maven Central 依赖已发布坐标和版本。
3. 使用内存数据库运行一个 JDBC smoke test。
4. 检查下载到的 POM 是否包含预期 license、SCM 和 developer 元数据。
5. 创建匹配的 Git tag。
6. 发布 GitHub Release。

## 回滚策略

不要尝试覆盖已经发布到 Maven Central 的版本。如果 release 有问题，请发布新的 patch 版本，并在 GitHub Release notes 中说明。
