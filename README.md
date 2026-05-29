# h2db

h2db is a fork of H2, the Java SQL database engine. It keeps the H2 embedded
and server database model while publishing under this repository's Maven
coordinates.

## Features

* Very fast, open source, JDBC API
* Embedded and server modes; disk-based or in-memory databases
* Transaction support, multi-version concurrency
* Browser based Console application
* Encrypted databases
* Fulltext search
* Pure Java with small footprint: around 2.5 MB jar file size
* ODBC driver

Upstream H2 documentation is available at https://h2database.com.

## Maven Coordinates

Published h2db artifacts use the following coordinates. Replace the version
with the release you intend to consume:

```XML
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.2.10</version>
</dependency>
```

## Documentation

* [Tutorial](https://h2database.com/html/tutorial.html)
* [SQL commands](https://h2database.com/html/commands.html)
* [Functions](https://h2database.com/html/functions.html), [aggregate functions](https://h2database.com/html/functions-aggregate.html), [window functions](https://h2database.com/html/functions-window.html)
* [Data types](https://h2database.com/html/datatypes.html)

## Build

The Gradle project lives in `h2/`.

```sh
cd h2
./gradlew jar
```

On Windows:

```bat
cd h2
gradlew.bat jar
```

Maven support is experimental; see [h2/MAVEN.md](h2/MAVEN.md).

## Open Source Release Materials

Before publishing a release, review:

* [OPEN_SOURCE_RELEASE.md](OPEN_SOURCE_RELEASE.md)
* [MAVEN_CENTRAL_RELEASE.md](MAVEN_CENTRAL_RELEASE.md)
* [GITHUB_RELEASE.md](GITHUB_RELEASE.md)
* [CONTRIBUTING.md](CONTRIBUTING.md)
* [SECURITY.md](SECURITY.md)
* [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
* [NOTICE.txt](NOTICE.txt)

## License

This project follows the H2 license terms and is available under MPL 2.0 or
EPL 1.0. See [LICENSE.txt](LICENSE.txt).

## Support

Use this repository's issue tracker for h2db bug reports and feature requests.
For general H2 usage questions, the upstream mailing list and the `h2` tag on
Stack Overflow remain useful community resources.
