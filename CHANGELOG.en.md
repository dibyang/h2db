# Changelog

This is the English companion of [CHANGELOG.md](CHANGELOG.md). The Chinese document is the primary version.

This file records public h2db release changes for external users. Keep one section per version.

## 2.4.1 (2026-08-19)

### Fixed

- Fixed an MVStore startup failure where a file carried a clean-shutdown marker but its layout metadata still contained overlapping physical ranges for allocated chunks. Startup could rebuild the free-space bitmap directly, report `Double mark`, and refuse to open the database. Startup now detects overlaps and performs full chunk-set validation; when an overlap involves only abandoned chunks with no live data, a valid version can be selected for recovery.
- Fixed a lifecycle race between online space reclamation and `MVStore.close()`. A complete reclamation round now runs under the store lifecycle lock, an already closed store returns `SKIPPED / RECLAMATION_STORE_CLOSED`, and failure cleanup no longer accesses closed reclamation metadata.
- The clean-shutdown marker is now cleared before chunks are moved and the store header is rewritten, so an interruption during the move is not incorrectly treated as a trustworthy clean shutdown.

### Compatibility

- This release does not change SQL, the JDBC API, or the MVStore disk format. Valid 2.4.0 database files can be upgraded directly.
- Automatic recovery is limited to overlapping ranges that are determined to belong to abandoned chunks with no live data. Any physical-range conflict involving live data is still rejected as corruption to prevent silent data loss.

### Verification

- Added deterministic regression coverage for abandoned-chunk overlap under a clean-shutdown marker, reclamation requests on closed stores, and concurrent online reclamation and store close.
- `runMvStoreRecoveryCheck`, `runMvStoreSpaceReclamationCheck`, and `runMvStoreReclamationJUnitCheck` passed on `v2.4.x`.

## 2.4.0 (2026-07-30)

### Added

- Added coordinated online backup and shadow restore. An operation gate, persistent database identity and schema epoch, MVStore prepared snapshots, participant coordination, atomic bundle manifests, shadow validation, and a generation activation fence provide consistent backup and restore across the database and external persistence participants.
- Added remote online-backup control with TCP protocol v21/v22 compatibility. Older clients can continue to use regular JDBC and legacy backup paths.
- Added the online-backup participant SPI, provider certification specification, fault matrix, performance report, and bilingual operations guides.
- Completed experimental DML fast path V1 for simple `INSERT ... VALUES` and JDBC batches, including plan recognition, read-only parameter views, batch parameter views, table bulk-write interfaces, and transaction/error semantic safeguards.
- Added clearer GitHub issue, contribution, and security intake paths for external bug and vulnerability reports.

### Changed

- Coordinated online backup is disabled by default and fixed when a database is opened; the disabled path preserves existing behavior. Enabling it writes internal database identity metadata.
- Hardened interruption semantics across Executor, Session, MVStore, Server, authentication, connection pools, file locks, networking, and disk retries to avoid swallowed interrupts, incorrect restoration, and caller-thread contamination.
- Hardened SourceCompiler concurrent output, immediate LOB close, tool process shutdown, GUI Console shutdown, and FilePathDisk retry convergence.

### Compatibility

- Verified 2.3 database files, legacy zip backups, 2.3 API plugins, and regular JDBC / legacy backup paths in both version directions.
- After coordinated online backup writes database identity metadata, the database must not be opened for writes by 2.3.x. Legacy databases can continue with legacy backup or use clone onboarding for the new workflow.
- The DML fast path remains experimental and falls back safely. Generated keys, triggers, constraints, delta tables, `INSERT SELECT`, `MERGE`, `UPDATE`, and `DELETE` continue on the native execution path.

### Verification

- Online-backup acceptance passed 82/82 checks, and plugin architecture acceptance passed 140/140 checks.
- The 2.3 compatibility matrix, online-backup fault matrix, shadow restore, and generation activation fence checks passed.
- In online-backup performance acceptance, prepare latency p99/max was 2ms/2ms under continuous DML and 21ms/21ms under mixed DML, DDL, and long transactions.

### Known Limitations

- The first production rollout certifies one real `adb_ldb` participant. The deployment/router layer owns the generation registry, active pointer, CAS switch, and crash recovery.
- No in-repository ADB/LDB integration throughput result is available for the DML fast path, so this release makes no 1.5x or 3x performance claim.

## 2.3.0 (2026-06-05)

### Added

- Added the static H2 plugin foundation: plugins are discovered through `ServiceLoader`, support table/storage/system catalog/JDBC URL prefix/transaction event/database lifecycle providers, and share one plugin registry path.
- Added plugin version coexistence and dependency resolution. Multiple versions of the same plugin id may coexist when provider ids do not conflict; dependency versions support exact versions, `*`, and interval ranges.
- Added plugin diagnostic views: `INFORMATION_SCHEMA.PLUGINS`, `PLUGIN_PROVIDERS`, `PLUGIN_CAPABILITIES`, and `PLUGIN_DEPENDENCIES` expose descriptors, providers, capabilities, dependencies, sources, and multi-version attribution.
- Added plugin release-readiness documentation with the release scope, non-goals, required gates, and rules for adding future provider types.
- Added the experimental DML execution fast-path SPI: `DmlExecutionProvider`, read-only parameter views, JDBC batch parameter views, and `BulkInsertTable` for guarded plugin takeover of simple `INSERT ... VALUES` / batch writes.
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
- The DML execution fast path remains experimental SPI. V1 covers only simple `INSERT ... VALUES` / JDBC batch; generated keys, triggers, constraints, delta tables, `INSERT SELECT`, `MERGE`, `UPDATE`, and `DELETE` fall back to the native path. The ADB/LDB new-hook long run has not completed in this repository, so 1.5x or 3x performance targets are not claimed.
- MVStore space reclamation is currently an experimental maintenance API. It does not expose SQL and does not schedule itself automatically.
- If the source file changes after a prepared shadow is created, switching is rejected by default; explicit fallback performs a maintenance full-copy.
- LongRun live write-order, torn-write, and FilePath-level chaos injection are not enabled yet. The current fault-injection profile damages database copies only, not the active database.
