# h2db

[中文](README.md)

h2db is an independently maintained fork of H2, the Java SQL database engine. It keeps H2's embedded and server database model and publishes under this repository's own Maven coordinates.

## Features

* Fast, open source, with JDBC API support.
* Embedded and server modes; disk-based or in-memory databases.
* Transaction support and multi-version concurrency.
* Browser-based Console application.
* Encrypted databases.
* Full-text search.
* Pure Java with a small jar footprint.
* ODBC driver support.

Upstream H2 documentation is available at https://h2database.com.

## Maven Coordinates

Published h2db artifacts use the following coordinates. Replace the version with the release you intend to consume.

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.2.10</version>
</dependency>
```

## Documentation

* [Tutorial](https://h2database.com/html/tutorial.html)
* [SQL commands](https://h2database.com/html/commands.html)
* [Functions](https://h2database.com/html/functions.html)
* [Aggregate functions](https://h2database.com/html/functions-aggregate.html)
* [Window functions](https://h2database.com/html/functions-window.html)
* [Data types](https://h2database.com/html/datatypes.html)

## Build

The Gradle project lives in `h2/`.

```sh
cd h2
./gradlew jar
```

Windows:

```bat
cd h2
gradlew.bat jar
```

Maven support is experimental; see [h2/MAVEN.md](h2/MAVEN.md).

## Open Source Release Materials

Before publishing a release, review:

* [OPEN_SOURCE_RELEASE.en.md](OPEN_SOURCE_RELEASE.en.md)
* [MAVEN_CENTRAL_RELEASE.en.md](MAVEN_CENTRAL_RELEASE.en.md)
* [GITHUB_RELEASE.en.md](GITHUB_RELEASE.en.md)
* [RELEASE_READINESS.en.md](RELEASE_READINESS.en.md)
* [CHANGELOG.en.md](CHANGELOG.en.md)
* [CONTRIBUTING.en.md](CONTRIBUTING.en.md)
* [SECURITY.en.md](SECURITY.en.md)
* [THIRD-PARTY-NOTICES.en.md](THIRD-PARTY-NOTICES.en.md)
* [NOTICE.en.txt](NOTICE.en.txt)

## License

This project follows the H2 license terms and is available under MPL 2.0 or EPL 1.0. See [LICENSE.txt](LICENSE.txt).

## Support

Use this repository's issue tracker for h2db bugs, regressions, release questions, and feature requests.

For general H2 usage questions that are not specific to this fork, upstream H2 community resources such as the H2 mailing list and the `h2` tag on Stack Overflow remain useful.
