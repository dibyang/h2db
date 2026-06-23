# Contributing

[中文](CONTRIBUTING.md)

Thank you for contributing to h2db. This project is a database engine, so small changes can affect compatibility, durability, and security. Keep changes focused and include enough verification for reviewers to reproduce your result.

## Issue and security intake

Before opening an ordinary issue:

- Use the repository Bug report issue template for normal defects.
- For security issues, report privately first and avoid public exploit details.

See: [Bug Reporting](docs/bug-reporting.en.md).

## Development Setup

The main Gradle project is in `h2/`.

```sh
cd h2
./gradlew compileJava
```

Windows:

```bat
cd h2
gradlew.bat compileJava
```

Main sources target Java 8 compatibility. Do not use newer Java language features or APIs in main code unless the affected source set is explicitly version-specific.

## Pull Requests

Before opening a pull request:

* Search related code, documentation, and tests.
* Add or update tests for behavior changes.
* Update documentation when SQL behavior, JDBC behavior, configuration, tools, storage format, recovery behavior, or public APIs change.
* Keep unrelated formatting and refactoring out of the pull request.
* Describe compatibility impact and migration notes for user-visible changes.

## Testing

Use the narrowest useful verification for your change. At minimum, production code changes should compile.

```sh
cd h2
./gradlew compileJava
```

Some historical H2 tests are driven by H2's own test launcher rather than plain Gradle test tasks. If you cannot run a full suite locally, state exactly which commands you ran and what remains unverified.

## Licensing

By contributing, you agree that your contribution is provided under this project's license choices: MPL 2.0 or EPL 1.0, unless a file explicitly states otherwise. Do not add third-party source, generated code, datasets, or binary artifacts unless their license is compatible and their origin is documented.
