# h2db

[English](README.en.md)

h2db 是 H2 Java SQL 数据库引擎的独立维护 fork。它保留 H2 的嵌入式与服务端数据库模型，并使用本仓库自己的 Maven 坐标发布。

## 功能特性

* 开源、速度快，提供 JDBC API。
* 支持嵌入式和服务端模式，支持磁盘数据库和内存数据库。
* 支持事务和多版本并发控制。
* 提供基于浏览器的 Console 应用。
* 支持加密数据库。
* 支持全文搜索。
* 纯 Java 实现，jar 体积较小。
* 支持 ODBC driver。

上游 H2 文档可参考 https://h2database.com。

## Maven 坐标

h2db 发布产物使用以下坐标。请将版本号替换为你要使用的正式发布版本。

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.3.0</version>
</dependency>
```

## 文档

* [Tutorial](https://h2database.com/html/tutorial.html)
* [SQL commands](https://h2database.com/html/commands.html)
* [Functions](https://h2database.com/html/functions.html)
* [Aggregate functions](https://h2database.com/html/functions-aggregate.html)
* [Window functions](https://h2database.com/html/functions-window.html)
* [Data types](https://h2database.com/html/datatypes.html)
* [插件化使用说明](docs/plugin/plugin-usage.md)
* [插件开发者指南](docs/h2db-plugin-developer-guide.md)

## 构建

Gradle 工程位于 `h2/` 目录。

```sh
cd h2
./gradlew jar
```

Windows:

```bat
cd h2
gradlew.bat jar
```

Maven 支持仍是实验性的，详见 [h2/MAVEN.md](h2/MAVEN.md)。

## 开源发布材料

发布前请检查以下材料：

* [OPEN_SOURCE_RELEASE.md](OPEN_SOURCE_RELEASE.md)
* [MAVEN_CENTRAL_RELEASE.md](MAVEN_CENTRAL_RELEASE.md)
* [GITHUB_RELEASE.md](GITHUB_RELEASE.md)
* [RELEASE_READINESS.md](RELEASE_READINESS.md)
* [docs/plugin/plugin-release-readiness.md](docs/plugin/plugin-release-readiness.md)
* [CHANGELOG.md](CHANGELOG.md)
* [CONTRIBUTING.md](CONTRIBUTING.md)
* [SECURITY.md](SECURITY.md)
* [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
* [NOTICE.txt](NOTICE.txt)

## 许可证

本项目遵循 H2 的许可证条款，可在 MPL 2.0 或 EPL 1.0 下使用。详见 [LICENSE.txt](LICENSE.txt)。

## 支持

h2db 的 bug、回归问题、发布问题和功能请求请使用本仓库的 issue tracker。

一般 H2 使用问题如果与本 fork 无关，可以继续参考上游 H2 社区资源，例如 H2 mailing list 和 Stack Overflow 的 `h2` 标签。
