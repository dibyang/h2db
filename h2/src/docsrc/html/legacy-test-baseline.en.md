# Legacy Test Baseline Governance Plan

This document describes how the existing non-JUnit tests are brought under Gradle management. The goal is not to rewrite `TestAll` in one step. The first step is to provide runnable, grouped, and trackable entrypoints, then gradually move fixed tests into the must-pass set.

## Current Entrypoints

Run these commands from `h2/`:

| Task | Purpose | Gates the build |
| --- | --- | --- |
| `.\gradlew.bat runH2LegacySmoke` | Runs the current must-pass legacy smoke group | Yes |
| `.\gradlew.bat runH2LegacyBaselineReport` | Runs known failing legacy baseline groups for triage | No |
| `.\gradlew.bat runH2TestAllCi` | Runs the original `org.h2.test.TestAll ci` entrypoint | Yes |
| `.\gradlew.bat clean test check build` | Runs the current Gradle build and plugin JUnit checks | Yes |

`runH2LegacySmoke` and `runH2LegacyBaselineReport` use `org.h2.test.LegacyTestGroupRunner`. The runner reuses the `TestBase.runTest(TestAll)` lifecycle and lets Gradle manage explicit test class lists. To keep this repository's product default MySQL mode separate from upstream H2 legacy test expectations, the runner adds `MODE=REGULAR` to test URLs that do not already specify `MODE=`. Tests that explicitly request MySQL, Oracle, DB2, or another mode are not overridden.

## Current Baseline Issues

The latest full `runH2TestAllCi` run can compile and start the original suite, but many tests still fail. The main buckets are:

| Bucket | Representative failure | Direction |
| --- | --- | --- |
| SQL compatibility syntax | `Unknown data type: "IDENTITY"` | Confirmed to be primarily caused by the repository default MySQL mode conflicting with legacy tests that expect REGULAR mode; the grouped runner now uses REGULAR for URLs without an explicit mode, and remaining failures should be triaged per class |
| Script output expectations | Column names, separators, and expression rendering mismatches in `TestScript` | Decide whether output behavior changed intentionally or scripts are stale, then migrate by script directory |
| JDBC updatable result sets | `The result set is not updatable`, result set type/concurrency assertions | Confirmed to be primarily caused by metadata table type filtering depending on a non-sorted array; after restoring `BASE TABLE` filtering, related classes were moved into smoke |
| Compatibility mode assertions | Oracle/MySQL/metadata/keywords expectations differ from current behavior | Triage by compatibility mode and preserve a minimal regression set for each mode |
| Environment-sensitive assertions | Timestamp precision, Chinese locale month names, Web Console output | Fix locale/timezone or rewrite assertions around stable semantics |

## Working Rules

1. New or modified production code must run the relevant focused checks and `runH2LegacySmoke`.
2. After a legacy failing class is fixed, move it from `legacyBaselineIssueTests` to `legacySmokeTests`.
3. If a test class is still unstable, document the failure reason before making it must-pass.
4. `runH2LegacyBaselineReport` is observational only and must not be treated as build success evidence.
5. Keep `runH2TestAllCi` as the final acceptance target; make it a daily gate only after all groups are stable.

## Phases

| Phase | Goal | Done when |
| --- | --- | --- |
| L1 | Stabilize Gradle legacy smoke and baseline report entrypoints | `runH2LegacySmoke` passes and this workflow is documented |
| L2 | Resolve `IDENTITY` compatibility failures | Representative db/jdbc tests no longer fail on `IDENTITY` |
| L3 | Resolve JDBC updatable result set failures | `TestCompatibilityOracle`, `TestResultSet`, and `TestUpdatableResultSet` are moved into smoke |
| L4 | Resolve `TestScript` output baseline | Script output diffs are cataloged by directory and migrated in batches |
| L5 | Resolve compatibility mode and metadata/keywords failures | Oracle/MySQL/metadata failures are no longer mixed into the generic failure bucket |
| L6 | Stabilize environment-sensitive failures | Locale, timezone, and time precision failures are stable |
| L7 | Expand the must-pass group | Fixed baseline report classes are moved into smoke |
| L8 | Restore full `runH2TestAllCi` as optional acceptance | Full entrypoint has zero failures or only explicit waivers |
| L9 | Fold legacy grouping into the daily development workflow | Documentation, Gradle tasks, and commit checks are aligned |
