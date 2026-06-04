# Changelog

This is the English companion of [CHANGELOG.md](CHANGELOG.md). The Chinese document is the primary version.

This file records public h2db release changes for external users. Keep one section per version.

## 2.3.0 (Pending Release)

### Added

- Added open-source release materials, including README, contributing guide, security policy, support guide, GitHub Release guide, Maven Central release guide, third-party notices, and English companions.
- Added documentation for the experimental MVStore space reclamation maintenance API, including controlled maintenance windows, diagnostics, leftover cleanup, and rollback strategy.
- Added public status, entry-point introspection, and diagnostic event listener support for the MVStore space reclamation maintenance API.
- Added the standalone LongRun stress-test distribution package with smoke, reopen, crash/recovery, fault-injection, nightly, comprehensive, and 30-day soak profiles.
- Added LongRun Linux/macOS `watch` mode to start or reuse a background instance and follow its log. New background starts rotate old logs by default and support `--append-log`, `--truncate-log`, and `H2_LONGRUN_LOG_POLICY`.
- LongRun report generation now prints the Markdown summary to stdout while still writing `report/summary.md` and `report/summary.properties`.
- LongRun metrics now include a lifecycle `phase`; throughput-drop warnings use only `RUNNING` samples so crash/recovery windows do not produce false throughput WARNs.
- Added a copy-based file corruption injection profile with `truncate`, `bit-flip`, `zero-range`, `random-range`, and `partial-page` damage classification.

### Changed

- Gradle publication artifacts now include `LICENSE.txt` and `NOTICE.txt` under `META-INF/` in the main jar, sources jar, and javadoc jar.
- LongRun distribution README, design documents, single-instance policy, and profile timing documents now have synchronized Chinese and English release-facing guidance.

### Verification

- LongRun 10-minute smoke acceptance passed: `PASS`, about 14.09 million operations, 4 reopen checks, 60 reclamation success events, and 0 suspicious log lines.
- LongRun 30-minute crash/recovery acceptance passed: `PASS`, 15 crash cycles, 29 recovery checks, 0 warnings, and 0 suspicious log lines.
- LongRun 30-minute fault-injection acceptance passed: `PASS`, 14 fault injection events, 11 recovered, 3 detected or detected by verify, 0 unexpected, and 0 suspicious log lines.

### Known Limitations

- MVStore space reclamation is currently an experimental maintenance API. It does not expose SQL and does not schedule itself automatically.
- If the source file changes after a prepared shadow is created, switching is rejected by default; explicit fallback performs a maintenance full-copy.
- LongRun live write-order, torn-write, and FilePath-level chaos injection are not enabled yet. The current fault-injection profile damages database copies only, not the active database.
