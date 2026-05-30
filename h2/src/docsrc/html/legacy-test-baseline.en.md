# Legacy Test Baseline Governance Plan

This document describes how the existing non-JUnit tests are brought under Gradle management. The goal is not to rewrite `TestAll` in one step. The first step is to provide runnable, grouped, migratable, and reviewable entrypoints so later pluginization work has a stable test foundation for every phase.

## Current Entrypoints

Run these commands from `h2/`:

| Task | Purpose | Gates the build |
| --- | --- | --- |
| `.\gradlew.bat runH2LegacySmoke` | Runs the current must-pass legacy smoke group | Yes |
| `.\gradlew.bat runH2LegacyBaselineReport` | Runs legacy baseline groups that still need observation; the current list is empty and reserved for future regression governance | No |
| `.\gradlew.bat runH2LegacyBaselineReport "-Ph2TestScript=other/conditions.sql"` | Runs one `TestScript` script for focused SQL output triage | No |
| `.\gradlew.bat runH2TestAllCiPhaseReport "-Ph2CiPhase=memory"` | Runs one original `TestAll ci` phase; supported phases are `memory`, `additional`, `utils`, `lazy-memory`, `disk`, `disk-additional`, `network-memory`, `network-lazy`, and `encrypted-disk` | No |
| `.\gradlew.bat runH2TestAllCi` | Runs the original `org.h2.test.TestAll ci` entrypoint | Yes, as full acceptance |
| `.\gradlew.bat listH2LegacySlowTests` | Lists slow/performance tests that are tracked but intentionally kept out of the daily gate | No |
| `.\gradlew.bat clean test check build` | Runs the current Gradle build and plugin JUnit checks | Yes |

`runH2LegacySmoke`, `runH2LegacyBaselineReport`, and the Gradle `TestAll ci` entrypoints keep this repository's product default MySQL mode separate from upstream H2 legacy test expectations. They add `MODE=REGULAR` to test URLs that do not already specify `MODE=`. Tests that explicitly request MySQL, Oracle, DB2, or another mode are not overridden.

## Current Baseline Status

All named phases pass: `memory`, `additional`, `utils`, `lazy-memory`, `disk`, `disk-additional`, `network-memory`, `network-lazy`, and `encrypted-disk`. The full `runH2TestAllCi` entrypoint has passed local acceptance before, but long full-suite localhost network flakes are recorded: a rerun on 2026-05-30 at 15:17 failed once in `net memory` when `TestMultiThreadedKernel` timed out connecting to `localhost:9092`, and the focused `network-memory` phase rerun passed afterwards; a rerun on 2026-05-30 at 16:42 failed once in `additional` when `TestTools.testServer` hit a TCP shutdown connection abort, and the focused `additional` phase rerun passed afterwards; a rerun on 2026-05-31 at 07:29 failed once in `lazy net` when `TestConnectionPool.testPerformance` timed out connecting to `localhost:9092`, and the focused `network-lazy` phase rerun passed afterwards. These are tracked as intermittent network items in the test foundation.

Historical issues that have been fixed or brought under management:

| Bucket | Representative issue | Current status |
| --- | --- | --- |
| SQL compatibility syntax | `Unknown data type: "IDENTITY"` | Isolated from the repository default MySQL mode by the REGULAR legacy mode policy |
| Script output expectations | Column names, separators, and expression rendering mismatches in `TestScript` | Related script baselines were updated to current behavior and `TestScript` was moved into smoke |
| JDBC updatable result sets | `The result set is not updatable`, result set type/concurrency assertions | `BASE TABLE` filtering semantics were restored and related classes were moved into smoke |
| Compatibility mode assertions | Oracle/MySQL/metadata/keywords expectations differed from current behavior | Metadata table type ordering is aligned and `TestMetaData` was moved into smoke |
| Environment-sensitive assertions | Timestamp precision, Chinese locale month names, Web Console output, network phase port connection timeouts, TCP server shutdown connection aborts | Phase-level entrypoints make these failures isolatable; focused `network-memory` and `additional` reruns passed, while intermittent full-suite network issues remain tracked |
| Full `TestAll ci` runtime | A local full run takes about 16 minutes | Kept as full acceptance, not as the default fast feedback loop for every small change |

`TestUpgrade` now uses a quieter old-H2-jar fetch path: it reads the local `.m2` cache first, downloads directly from Maven Central and writes the artifact back to the cache when missing, and only tries Maven / Maven Wrapper as a fallback when direct download fails. On 2026-05-31 the `utils` phase was verified to populate the cache on first run and avoid missing-`mvn`, Maven plugin-resolution, and repeated-download noise on rerun.

## Test Layers

Daily development gate:

```powershell
.\gradlew.bat clean test check build runH2LegacySmoke
```

Use this for every small pluginization phase, production-code change, and test-foundation change. It covers the main Gradle build, plugin JUnit checks, and the legacy smoke group that is already must-pass.

Full acceptance:

```powershell
.\gradlew.bat runH2TestAllCi
```

Use this when finishing a phase or before local commits that need to prove the original H2 legacy suite still passes. This task is intentionally slower and should be treated as phase acceptance rather than fast edit feedback. If a failure is concentrated in a network phase, rerun the matching `runH2TestAllCiPhaseReport` phase first and record whether it is intermittent.

Phase triage:

```powershell
.\gradlew.bat runH2TestAllCiPhaseReport "-Ph2CiPhase=memory"
```

Use this when full acceptance fails, times out, or needs narrowing. This is an observational report entrypoint and does not gate the Gradle build.

Slow/performance test management:

```powershell
.\gradlew.bat listH2LegacySlowTests
```

Tests currently tracked but not included in the daily gate:

| Test class | Reason | Follow-up |
| --- | --- | --- |
| `org.h2.test.db.TestLargeBlob` | Large-object boundary test with high resource usage | Move into a dedicated large-resource acceptance task later |
| `org.h2.test.store.TestDefrag` | Depends on `big` and non-`ci` configuration | Move into a dedicated storage slow-test task later |
| `org.h2.test.store.TestMVStoreBenchmark` | Benchmark test, unsuitable as a daily stability signal | Move into a performance baseline task later |
| `org.h2.test.db.TestSubqueryPerformanceOnLazyExecutionMode` | Performance/lazy-execution path validation | Move into a performance regression task later |

## Working Rules

1. New or modified production code must run the relevant focused checks and `runH2LegacySmoke`.
2. Each pluginization phase must run the daily development gate before commit.
3. Phase-complete or higher-risk storage, table-engine, or SQL-behavior changes must run full `runH2TestAllCi` before commit.
4. After a legacy failing class is fixed, move it from `legacyBaselineIssueTests` to `legacySmokeTests`.
5. If a test class is still unstable, document the failure reason before making it must-pass.
6. `runH2LegacyBaselineReport` and `runH2TestAllCiPhaseReport` are observational only and must not be treated as build success evidence.
7. Slow/performance tests are first tracked through `listH2LegacySlowTests`; later phases can split them into configurable, resource-bounded dedicated tasks.

## Phases

Currently passing named `TestAll ci` phases: `memory`, `additional`, `utils`, `lazy-memory`, `disk`, `disk-additional`, `network-memory`, `network-lazy`, and `encrypted-disk`. The full `runH2TestAllCi` entrypoint remains the acceptance entrypoint; intermittent full-suite localhost network issues are currently recorded for `network-memory`, `additional`, and `network-lazy`, with focused phase reruns passing.

| Phase | Goal | Done when |
| --- | --- | --- |
| L1 | Stabilize Gradle legacy smoke and baseline report entrypoints | `runH2LegacySmoke` passes and this workflow is documented |
| L2 | Resolve `IDENTITY` compatibility failures | Representative db/jdbc tests no longer fail on `IDENTITY` |
| L3 | Resolve JDBC updatable result set failures | `TestCompatibilityOracle`, `TestResultSet`, and `TestUpdatableResultSet` are moved into smoke |
| L4 | Resolve `TestScript` output baseline | `TestScript` is moved into smoke, with `-Ph2TestScript=...` support for single-script triage |
| L5 | Resolve compatibility mode and metadata/keywords failures | `TestMetaData` is moved into smoke and the baseline report currently has no remaining classes |
| L6 | Stabilize environment-sensitive failures | `TestAll ci` can be run by named phase so locale/timezone/time precision failures can be isolated |
| L7 | Expand the must-pass group | Fixed baseline report classes are moved into smoke and the `TestAll ci` `memory` phase passes |
| L8 | Restore full `runH2TestAllCi` as acceptance | All named `TestAll ci` phases pass; the full entrypoint remains phase acceptance, and network timeout flakes must be rerun by focused phase and recorded |
| L9 | Fold legacy grouping into the daily development workflow | Daily gate, full acceptance, slow/performance management, and environment-noise rules are documented with discoverable Gradle entrypoints |
