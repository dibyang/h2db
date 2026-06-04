# H2 Standalone Long-Running Stress Test Application Plan

This document is the trackable implementation plan for `longrun-test-app-design.en.md`. The goal is to deliver an independent `h2-longrun.jar` for MVStore / H2 SQL realistic access simulation, long-duration runs, pressure, failure injection, and S2 automatic space reclamation acceptance without polluting the H2 main jar or default CI.

## Confirmed Decisions

| Decision | Result |
| --- | --- |
| Source directory | Use `h2/src/longrun`, separate from `src/main` and `src/test`. |
| Jar name | Use `h2-longrun.jar`. |
| First smoke duration | Default to 5 minutes. |
| First workload | Implement MVStore first, then SQL. |
| Config format | Use `.properties` first. |
| Execution model | All longrun tasks are explicit and do not enter default `build` / `check`. |
| Commit flow | Commit locally after each LR phase passes its validation. |

## Scope

| Scope | Notes |
| --- | --- |
| In | Independent source set, independent jar, zip and tar.gz distributions, CLI, config, MVStore workload, metrics, state file, consistency checks, S2 reclamation observation, crash harness, SQL workload, external mode. |
| Out | Do not package into the H2 main jar; do not replace JUnit, legacy smoke, or `TestAll ci`; do not add 30-day runs to default CI; do not treat generated databases as production data. |

## Phase Overview

| Phase | Status | Goal | Deliverable | Validation | Commit Requirement |
| --- | --- | --- | --- | --- | --- |
| LR1 | Done | Independent jar skeleton | `src/longrun`, `longRunTestJar`, CLI, sample config | Jar builds and `--help` runs | Committed |
| LR2 | Done | 5-minute MVStore smoke workload | MVStore read/write/delete/update, checksum, state file, metrics | `runLongRunSmoke` passes with default 5 minutes | Committed |
| LR3 | Done | S2 automatic reclamation observation | `ReclamationObserver`, file size, fill rate, diagnostic metrics | Housekeeping results are recorded during workload | Committed |
| LR4 | Done | Consistency and reopen verification | ledger, counters, full scan, reopen verify | Data model is consistent after restart | Committed |
| LR5 | Done | Crash harness | Parent/child process kill / restart / resume | Multiple crash cycles recover and verify | Committed |
| LR6 | Done | SQL workload | JDBC table model, transactions, indexes, range scans, batch writes | SQL smoke passes | Committed |
| LR7 | Done | Nightly and 30-day soak configs | 12-hour and 30-day sample configs, report archival, metrics rollover | Local shortened run passes | Committed |
| LR8 | Done | External mode | `--h2-jar` targets candidate H2 jar | Smoke can run against a specified jar | Committed |
| LR9 | Done | Distribution packages | `h2-longrun.zip` and `h2-longrun.tar.gz` with jar, scripts, configs, README | Packages build and unpacked scripts run smoke | Committed |
| LR10 | Done | Report analyzer | Normal completion writes Markdown and properties summaries automatically; `report` re-analyzes existing data | Completed run can be analyzed into PASS/WARN/FAIL | Committed |

## LR1 Independent Jar Skeleton

| Item | Details |
| --- | --- |
| Goal | Establish independent longrun sources, build, and runtime entrypoint. |
| Main files | `h2/build.gradle`, `h2/src/longrun/org/h2/test/longrun/LongRunTestApp.java`, `LongRunConfig.java`, sample config. |
| Required tasks | Add `longrun` source set; add `longRunTestJar`; add placeholder `runLongRunSmoke`; implement `--help` and base `--config` parsing; print version, work dir, and config summary. |
| Validation | `.\gradlew.bat longRunTestJar` passes; `java -jar build/libs/h2-longrun.jar --help` runs; `.\gradlew.bat compileJava` remains unaffected. |
| Risk control | Do not modify the H2 main jar task; do not wire longrun into default test/check. |

## LR2 10-Minute MVStore Smoke Workload

| Item | Details |
| --- | --- |
| Goal | Build a runnable MVStore longrun loop. |
| Main files | `WorkloadRunner`, `WorkloadProfile`, `mvstore/MVStoreWorkload`, `DataInvariantChecker`, `MetricsReporter`, `LongRunState`. |
| Required tasks | Support fixed seed; implement put/get/remove/update; store checksum/version in values; write periodic state file; emit metrics; default smoke duration is 5 minutes. |
| Validation | `.\gradlew.bat runLongRunSmoke` passes with default 5 minutes; failures retain work dir; short runs with the same seed reproduce the basic operation sequence. |
| Risk control | Use small default data volume and disk limit; write state through temp file replacement. |

## LR3 S2 Automatic Reclamation Observation

| Item | Details |
| --- | --- |
| Goal | Observe S2 automatic space reclamation under realistic workload. |
| Main files | `ReclamationObserver`, `MetricsReporter`, MVStore workload config. |
| Required tasks | Periodically call or observe `runOnlineReclamationHousekeeping()`; record status, message, file size, fill rate, chunk fill rate, candidate chunks, and shrink bytes. |
| Validation | Reclamation metrics appear during smoke; no-candidate, success, backoff, and related outcomes are distinguishable. |
| Risk control | Observation and trigger frequency must be configurable; default remains low-intensity. |

## LR4 Consistency And Reopen Verification

| Item | Details |
| --- | --- |
| Goal | Prove longrun checks correctness continuously, not only process survival. |
| Main files | `DataInvariantChecker`, `LongRunState`, `mvstore/MVStoreModel`. |
| Required tasks | Add `ledger` and `counters`; support fast check and full scan; periodically close/reopen; verify committed data and counters after recovery. |
| Validation | Reopen verification passes; intentional checksum corruption fails and retains artifacts. |
| Risk control | Full scan interval is configurable; large data sets do not full-scan every round. |

## LR5 Crash Harness

| Item | Details |
| --- | --- |
| Goal | Cover real process-level abnormal exits and recovery. |
| Main files | `CrashHarness`, `LongRunTestApp` child-process mode, pid/state files. |
| Required tasks | Parent starts worker; randomly kills worker; worker resumes with `--resume=true`; worker verifies before continuing workload. |
| Validation | Multiple kill/restart cycles end with successful verification; failures retain database, state, recent operations, and metrics. |
| Risk control | Parent/worker roles are explicit to avoid killing the parent; Windows / Linux commands are handled separately. |

## LR6 SQL Workload

| Item | Details |
| --- | --- |
| Goal | Extend longrun coverage from MVStore to JDBC / SQL. |
| Main files | `sql/SqlWorkload`, `sql/SqlModel`, SQL schema initialization. |
| Required tasks | Implement `LONGRUN_DATA`, `LONGRUN_LEDGER`, `LONGRUN_COUNTERS`; cover transaction commit/rollback, index queries, range scans, and batch writes. |
| Validation | SQL smoke passes; rolled-back data is invisible; ledger/counters are consistent. |
| Risk control | The first SQL workload does not try to cover all SQL features. |

## LR7 Nightly And 30-Day Soak Configs

| Item | Details |
| --- | --- |
| Goal | Make the app usable for nightly and 30-day runs. |
| Main files | `src/longrun/resources` sample configs, report archival logic, metrics rollover. |
| Required tasks | Provide 10-minute, 12-hour, and 30-day configs; roll metrics by day; final report summarizes throughput, latency, errors, space trends, and reclamation results. |
| Validation | Local shortened soak passes; config can limit max DB size, max errors, and output directory. |
| Risk control | 30-day task is never started by default; long runs require explicit config. |

## LR8 External Mode

| Item | Details |
| --- | --- |
| Goal | Run longrun validation against a candidate release `h2.jar`. |
| Main files | CLI, classpath startup logic, parent/child process mode. |
| Required tasks | Support `--h2-jar`; record target jar path, version, and checksum summary; run smoke in external mode. |
| Validation | Smoke runs against a specified `h2.jar`; invalid classpath produces a clear diagnostic. |
| Risk control | Avoid classloader complexity during LR1-LR7; handle external mode as its own phase. |

## LR9 Distribution Packages

| Item | Details |
| --- | --- |
| Goal | Turn the longrun app from a developer jar into an operator-friendly package. |
| Main files | `h2/build.gradle`, `h2/src/longrun/dist/bin`, `h2/src/longrun/dist/README*.md`, `src/longrun/resources`. |
| Required tasks | Add `longRunTestDistZip`, `longRunTestDistTar`, and `longRunTestDist`; use a top-level `h2-longrun/` directory inside distribution packages; package `lib/h2-longrun.jar`, `bin` scripts, `config` properties, and bilingual README files; Linux script defaults to background `start`, supports `run/status/logs/watch/stop/restart`, and supports rotate/append/truncate startup log policies; scripts must preserve `--h2-jar` classpath behavior. |
| Validation | Build both packages, unpack them, and run a shortened smoke through the packaged script. |
| Risk control | Do not wire distribution packages into default `build` / `check`; keep long soaks explicit; packaged configs write under `work/`. |

## LR10 Report Analyzer

| Item | Details |
| --- | --- |
| Goal | Turn raw longrun artifacts into a concise PASS/WARN/FAIL report. |
| Main files | `ReportAnalyzer`, `CommandLineOptions`, `LongRunTestApp`, `bin/h2-longrun`, README files. |
| Required tasks | Generate reports automatically after normal completion; keep `report --work-dir <dir> [--log-file <file>]` for re-analysis; read `final-report.properties`, `metrics/*.csv`, and logs; print the Markdown summary to stdout; write `report/summary.md` and `report/summary.properties`; flag missing final reports, suspicious log lines, missing metrics, low throughput, throughput drop over `RUNNING` metric samples, large final size, large size per million operations, MVStore size amplification above 5x, ineffective reclamation, backoff-heavy reclamation, and reclamation event counts with moderate defaults. |
| Validation | Run smoke, generate a report, and verify summary files are written. |
| Risk control | Keep report analysis local and read-only except for the `report/` output directory. |

## Common Phase Completion Criteria

Before each phase is complete:

1. Update this document status from `Planned` to `Done`.
2. Add or update tests / smoke validation for the phase.
3. Run the minimum validation command for the phase.
4. Keep the working tree scoped to this phase.
5. Commit locally with a message that names the phase goal.

## Recommendation

LR1-LR4 form the minimum usable loop. LR5 adds abnormal-exit coverage, LR6 extends the SQL layer, LR7-LR8 turn the tool into a nightly, long-soak, and release-candidate validation asset, and LR9 makes it distributable as a standalone package.
