# Changelog

This is the English companion of [CHANGELOG.md](CHANGELOG.md). The Chinese document is the primary version.

This file records public h2db release changes for external users. Keep one section per version.

## 2.3.0 (Pending Release)

### Added

- Added the static H2 plugin foundation: plugins are discovered through `ServiceLoader`, support table/storage/system catalog/JDBC URL prefix/transaction event/database lifecycle providers, and share one plugin registry path.
- Added plugin version coexistence and dependency resolution. Multiple versions of the same plugin id may coexist when provider ids do not conflict; dependency versions support exact versions, `*`, and interval ranges.
- Added plugin diagnostic views: `INFORMATION_SCHEMA.PLUGINS`, `PLUGIN_PROVIDERS`, `PLUGIN_CAPABILITIES`, and `PLUGIN_DEPENDENCIES` expose descriptors, providers, capabilities, dependencies, sources, and multi-version attribution.
- Added plugin release-readiness documentation with the release scope, non-goals, required gates, and rules for adding future provider types.
- Added open-source release materials, including README, contributing guide, security policy, support guide, GitHub Release guide, Maven Central release guide, third-party notices, and English companions.
- Added documentation for the experimental MVStore space reclamation maintenance API, including controlled maintenance windows, diagnostics, leftover cleanup, and rollback strategy.
- Added public status, entry-point introspection, and diagnostic event listener support for the MVStore space reclamation maintenance API.
- Added the standalone LongRun stress-test distribution package with smoke, reopen, crash/recovery, fault-injection, nightly, comprehensive, and 30-day soak profiles.
- Added LongRun Linux/macOS `watch` mode to start or reuse a background instance and follow its log. New background starts rotate old logs by default and support `--append-log`, `--truncate-log`, and `H2_LONGRUN_LOG_POLICY`.
- LongRun report generation now prints the Markdown summary to stdout while still writing `report/summary.md` and `report/summary.properties`.
- LongRun metrics now include a lifecycle `phase`; throughput-drop warnings use only `RUNNING` samples so crash/recovery windows do not produce false throughput WARNs.
- Added a copy-based file corruption injection profile with `truncate`, `bit-flip`, `zero-range`, `random-range`, and `partial-page` damage classification.

### Changed

- Plugin class loading through JDBC URL settings is no longer supported. Plugin jars are discovered automatically after they are on the classpath and publish `META-INF/services/org.h2.api.H2Plugin`; URLs only select already discovered providers.
- Hardened plugin permission and isolation boundaries. Forbidden provider types, plugin-level allowed provider type violations, ServiceLoader discovery failures, invalid descriptors, missing dependencies, and dependency cycles now produce diagnosable errors.
- Gradle publication artifacts now include `LICENSE.txt` and `NOTICE.txt` under `META-INF/` in the main jar, sources jar, and javadoc jar.
- LongRun distribution README, design documents, single-instance policy, and profile timing documents now have synchronized Chinese and English release-facing guidance.

### Verification

- Plugin release gate passed: `runPluginArchitectureCheck`.
- H2 legacy smoke passed: `runH2LegacySmoke`.
- LongRun 10-minute smoke acceptance passed: `PASS`, about 14.09 million operations, 4 reopen checks, 60 reclamation success events, and 0 suspicious log lines.
- LongRun 3-minute pre-release performance comparisons showed moderate throughput overhead with online reclamation enabled, while significantly reducing final file size and MVStore size amplification.
- LongRun 30-minute crash/recovery acceptance passed: `PASS`, 15 crash cycles, 29 recovery checks, 0 warnings, and 0 suspicious log lines.
- LongRun 30-minute fault-injection acceptance passed: `PASS`, 14 fault injection events, 11 recovered, 3 detected or detected by verify, 0 unexpected, and 0 suspicious log lines.

### Known Limitations

- The current plugin model is static. Hot loading, unloading, online replacement, plugin manifest/signing, dedicated sandboxing, parser/function/auth/optimizer/wire protocol extension points, and non-MVStore production main paths are outside this release scope.
- MVStore space reclamation is currently an experimental maintenance API. It does not expose SQL and does not schedule itself automatically.
- If the source file changes after a prepared shadow is created, switching is rejected by default; explicit fallback performs a maintenance full-copy.
- LongRun live write-order, torn-write, and FilePath-level chaos injection are not enabled yet. The current fault-injection profile damages database copies only, not the active database.
