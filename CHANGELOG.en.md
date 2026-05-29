# Changelog

This is the English companion of [CHANGELOG.md](CHANGELOG.md). The Chinese document is the primary version.

This file records public h2db release changes for external users. Keep one section per version; move `Unreleased` entries into a concrete version before publishing.

## Unreleased

### Added

- Added open-source release materials, including README, contributing guide, security policy, support guide, GitHub Release guide, Maven Central release guide, third-party notices, and English companions.
- Added documentation for the experimental MVStore space reclamation maintenance API, including controlled maintenance windows, diagnostics, leftover cleanup, and rollback strategy.
- Added public status, entry-point introspection, and diagnostic event listener support for the MVStore space reclamation maintenance API.

### Changed

- Gradle publication artifacts now include `LICENSE.txt` and `NOTICE.txt` under `META-INF/` in the main jar, sources jar, and javadoc jar.

### Known Limitations

- MVStore space reclamation is currently an experimental maintenance API. It does not expose SQL and does not schedule itself automatically.
- If the source file changes after a prepared shadow is created, switching is rejected by default; explicit fallback performs a maintenance full-copy.
