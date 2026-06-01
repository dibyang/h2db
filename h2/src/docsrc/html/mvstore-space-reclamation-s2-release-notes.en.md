# MVStore Space Reclamation S2 Release Notes

> Current status: release is held. This file remains the conservative S2.0-S2.8 release-note draft; the new release target is complete automatic space organization, tracked in `mvstore-space-reclamation-s2-complete-auto-plan.md`.

This document records the user-visible behavior, default strategy, diagnostics, and deferred capabilities for the S2 online space reclamation release. S2 is chunk/page-level online partial reclamation inside MVStore. It is not whole-store shadow copy and not a simple wrapper around `compactFile()`.

## Default Strategy

| Item | Formal-release strategy |
| --- | --- |
| Background scheduling | Enabled by default in low-intensity mode through MVStore housekeeping. |
| Budgets | Limited by minimum interval, failure backoff, rewrite-byte budget, and per-round run-time budget. |
| Disable switch | Background online reclamation can be disabled immediately with `onlineReclamationEnabled(false)`. |
| Journal | Disabled by default and enabled only by explicit request. |
| Relocation map | Used only when explicit mappings exist; stores with related metadata are rejected when the gate is disabled. |
| Tail compaction | Triggered only with an explicit time budget; physical tail move / truncate is not implicit. |

## Usage And Rollback

The default configuration lets MVStore housekeeping try low-intensity online reclamation. It analyzes candidate chunks first, runs bounded online partial relocation within the configured budget, and returns structured diagnostics. If an application is latency-sensitive, needs to isolate an issue, or suspects a compatibility risk, disable it when opening MVStore:

```java
new MVStore.Builder()
        .fileName(fileName)
        .onlineReclamationEnabled(false)
        .open();
```

The manual maintenance entrypoint uses the same coordinator. The pluginized maintenance path can call `StorageMaintenance.vacuumOnline()` from the storage engine provider capability.

## Diagnostic Codes

| Code | Meaning | Recommended action |
| --- | --- | --- |
| `RECLAMATION_ROUND_FINISHED` | One online reclamation round completed. | Inspect `estimatedReclaimedBytes`, fill rates, and file-size changes. |
| `RECLAMATION_PAUSED_BY_TIME_BUDGET` | The round paused after reaching the run-time budget. | Keep default backoff; raise the budget only under sustained space pressure. |
| `NO_RECLAMATION_CANDIDATE` | No reclaimable chunk candidate is available now. | Normal skip; if the file is still large, inspect retention, long transactions, or relocation-map state. |
| `NO_OPEN_MAP_RELOCATION_PROGRESS` | Candidates existed, but no page relocation completed in this round. | Inspect map ownership, old-version pinning, and write contention. |
| `DRY_RUN` | Analysis-only mode, no writes. | Use for diagnostics and pre-release assessment. |
| `RECLAMATION_SCHEDULER_DISABLED` | The scheduler is configured off. | Enable `onlineReclamationEnabled(true)` or remove the disabling configuration if background reclamation is desired. |
| `RECLAMATION_SCHEDULER_BACKOFF` | The scheduler is in its minimum-interval or failure-backoff window. | Normal throttling to avoid frequent foreground IO contention. |
| `RECLAMATION_STORE_CLOSED` | The store is already closed, so background housekeeping skips this round. | Normal close-race protection; inspect close ordering if application code still calls maintenance entrypoints. |
| `RECLAMATION_FAILED` | This round failed with an exception. | Preserve files and journal state, inspect the exception, and let the next round recover stale journal state first. |

## Pre-Release Gates

Run the following gates again before the formal release:

```powershell
.\gradlew.bat runMvStoreReclamationJUnitCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runH2LegacySmoke
.\gradlew.bat runH2TestAllCi
```

## Deferred Capabilities

| Capability | State | Follow-up |
| --- | --- | --- |
| Real relocation-map read path | Feature-gated and currently unused. | Add safe old-page-position to new-page-position reads and write-open compatibility rejection. |
| Full crash-safe publish semantics | Journal scaffold exists. | Add publish markers, fault injection, replay/rollback, and old-version open protection. |
| More precise unknown-map diagnostics | MVStore can lazy-open maps for rewrite today. | Emit a dedicated skip reason when ownership cannot be resolved. |
| Real-workload background scheduling observation | Scheduler is enabled by default in low-intensity mode. | Continue observing rate limiting, backoff, global mutual exclusion, and foreground write latency. |
| SQL maintenance command | Not an S2 starting point. | Revisit after the Java maintenance API and diagnostics are stable. |
