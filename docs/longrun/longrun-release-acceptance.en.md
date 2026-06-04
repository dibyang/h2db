# LongRun Release Acceptance Record

[中文](longrun-release-acceptance.md)

This document records manual pre-release acceptance results for the standalone LongRun distribution package. The goal is to confirm the packaged scripts, report output, MVStore S2 reclamation, reopen, crash/recovery, and copy-based fault injection paths.

## Passed Acceptance Runs

| Profile | Command | Result | Key Metrics |
| --- | --- | --- | --- |
| smoke | `./bin/h2-longrun watch --duration 10m --config config/longrun-smoke.properties` | PASS | About 14.09 million operations, 4 reopen checks, 60 reclamation success events, 0 warnings, and 0 suspicious log lines. |
| crash/recovery | `./bin/h2-longrun watch --config config/longrun-crash.properties` | PASS | 15 crash cycles, 29 recovery checks, about 36.14 million operations, 165 reclamation success events, 0 warnings, and 0 suspicious log lines. |
| fault-injection | `./bin/h2-longrun watch --config config/longrun-fault-injection.properties` | PASS | 14 fault injection events, 11 recovered, 3 detected or detected by verify, 0 unexpected, 0 warnings, and 0 suspicious log lines. |

## Confirmed Behavior

- `watch` starts the background longrun process and follows the selected instance log.
- On normal completion, the report is printed to the terminal and written to `report/summary.md` and `report/summary.properties`.
- Crash/recovery reports record `STARTUP`, `RUNNING`, and `RECOVERY` phases; throughput warnings use only `RUNNING` samples.
- The fault-injection profile covers `TRUNCATE`, `BIT_FLIP`, `ZERO_RANGE`, `RANDOM_RANGE`, and `PARTIAL_PAGE`, and distinguishes `RECOVERED`, `DETECTED`, and `DETECTED_BY_VERIFY`.
- S2 reclamation produced success events in the accepted profiles without backoff-heavy or ineffective-success WARNs.

## Recommended Remaining Pre-Release Runs

| Profile | Recommendation |
| --- | --- |
| nightly | Run at least one shortened pre-release run, for example `./bin/h2-longrun watch --duration 2h --config config/longrun-nightly.properties`. |
| comprehensive | If the release window allows it, run a shortened comprehensive profile to cover combined pressure, reopen, crash/recovery, and S2 paths. |
| dist tar/zip | Unpack `h2-longrun.zip` and `h2-longrun.tar.gz` in a clean directory and confirm script permissions, config files, README files, and jar layout. |

## Acceptance Criteria

Before release, accepted profiles should satisfy:

- `Status: PASS`.
- `Failures: None`.
- `Warnings: None`, or documented in release notes.
- `Suspicious Log Lines: 0`.
- `MVStore Size Amplification` below the default threshold `5.0`.
- Crash profile has no recovery failure.
- Fault-injection profile has no `UNEXPECTED_*` events.
