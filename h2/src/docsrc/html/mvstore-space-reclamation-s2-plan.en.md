# MVStore Space Reclamation S2 Long-Term Plan

This is the traceable task plan for the S2 long-term solution. S1, the medium-term solution, is complete and archived; S2 starts independently from this plan.

## Working Rules

1. Commit locally after every completed phase.
2. New production code must add tests in the same phase.
3. Prefer JUnit for contracts, options, scoring, and result objects when practical.
4. MVStore file, fault injection, concurrency, and compatibility scenarios are managed by `runMvStoreSpaceReclamationCheck`.
5. External docs need matching English copies.

## Phases

| Phase | Status | Goal | Main Tasks | Test Gate |
| --- | --- | --- | --- | --- |
| S2.0 | Done | Close the design | New long-term architecture design, plan, Chinese/English copies | `runMvStoreSpaceReclamationCheck` |
| S2.1 | Done | Observability and decision | `ChunkLivenessSnapshot`, candidate scoring, dry-run result, diagnostic messages | JUnit + MVStore dedicated |
| S2.2 | Done | Govern existing partial compact | Wire `MVStoreReclamationCoordinator` into `vacuumOnline()`, budgets, no-progress, mutual exclusion | JUnit + MVStore dedicated + plugin gate |
| S2.3 | Done | Page relocation main path | Live-page relocation for open maps, unknown map skip, long transaction skip | MVStore dedicated + concurrency |
| S2.4 | Done | Persistent evacuation journal | Layout/meta journal, phase replay, publish marker, crash recovery | Fault injection + recovery |
| S2.5 | Done | Relocation map | Old pos to new pos, feature flag, old-version compatibility strategy | JUnit + compatibility + recovery |
| S2.6 | Done | Integrated tail mover | Move tail chunks after relocation, shrink file, no-shrink diagnostics | MVStore dedicated + slow tests |
| S2.7 | Done | Background scheduling | Low-intensity default scheduling, idle budget, throttling, dry-run, mutual exclusion | Scheduler + concurrency |
| S2.8 | Done | Operational closeout | Chinese/English usage docs, configuration, diagnostic table, full release gates | Docs + daily gate |

## S2.1 Details

| Task | Deliverable |
| --- | --- |
| Define `ChunkLivenessSnapshot` | Chunk id, block/len, fill rate, live/dead bytes, pinning reason. |
| Define `MVStoreReclamationAnalysis` | Candidates, skipped chunks, estimated bytes, message. |
| Candidate scoring rules | Fill rate, dead bytes, tail position, unknown map, active version. |
| Dry-run entry | Analyze without writing files. |
| Tests | JUnit for scoring/result; MVStore dedicated bloat snapshot coverage. |

## S2.2 Details

| Task | Deliverable |
| --- | --- |
| Add coordinator | Unified capability, mutual exclusion, budget, result message. |
| Wire `vacuumOnline()` | Call coordinator instead of bare `compactFile(50)`. |
| No-progress diagnostics | Before/after size, fill rate, chunks fill rate. |
| Mutual exclusion | Return busy/skipped for backup, close, or existing reclaim job. |
| Tests | JUnit result coverage; MVStore dedicated entrypoint coverage. |

## S2.3 Details

| Task | Deliverable |
| --- | --- |
| Page relocation primitive | Open-map live page relocation based on `MVMap.rewritePage()`. |
| Unknown map skip | Skip and diagnose unopened or unclear ownership maps. |
| Long transaction skip | Do not force reclamation of chunks pinned by old versions. |
| Budget pause | Pause when live bytes, chunks, or millis budget is exhausted. |
| Tests | Open map relocation, unknown map, long transaction, budget pause. |

## S2.4-S2.8 Planning

| Phase | Key Risk | Prerequisite |
| --- | --- | --- |
| S2.4 | Bad journal semantics can break recovery | Stable S2.3 relocation behavior. |
| S2.5 | Relocation map changes read-path compatibility | S2.4 journal and feature flag. |
| S2.6 | Tail move touches file layout and truncate | Stable dead chunk release after S2.5 or at least S2.3. |
| S2.7 | Background work may affect user latency | Stable manual entry, budgets, mutual exclusion, recovery. |
| S2.8 | Operational docs need real diagnostics | S2.1-S2.7 messages and configuration are closed. |

## Test Commands

Minimum gate for each phase:

```powershell
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

When maintenance SPI or plugin capabilities change:

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

Higher-risk phases:

```powershell
.\gradlew.bat clean test check build runH2LegacySmoke
```

Run `runH2TestAllCi` when full acceptance is needed. If a known localhost network flake appears, rerun the matching phase report and record it.

## Current Implementation Status

| Phase | Status | Implemented |
| --- | --- | --- |
| S2.1 | Done | `MVStoreReclamationAnalyzer`, chunk liveness snapshots, candidate scoring, and dry-run analysis. |
| S2.2 | Done | `MVStoreReclamationCoordinator`, request/result/status objects, and `vacuumOnline()` maintenance integration. |
| S2.3 | Done | Online page relocation diagnostics over the existing `MVMap.rewritePage()` path, including estimated reclaimed bytes. |
| S2.4 | Done | Opt-in evacuation journal scaffold, phase recording, completion cleanup, and recovery entry point. |
| S2.5 | Done | Relocation-map feature gate and result diagnostics; read-path redirection remains disabled by default. |
| S2.6 | Done | Explicit-budget tail compaction invocation and diagnostics. |
| S2.7 | Done | Low-intensity scheduler enabled by default; it reuses the same coordinator and supports disable, minimum interval, and failure backoff. |
| S2.8 | Done | Chinese and English design, plan, and operational diagnostics documentation updated. |

## Current Defaults

S2 now has a low-intensity default-start mode: MVStore housekeeping enables the online reclamation scheduler by default, but it is limited by minimum interval, failure backoff, rewrite budget, and run-time budget; it can be disabled with `onlineReclamationEnabled(false)`. Journaling remains disabled by default. The relocation map participates in page-position resolution only when explicit mappings exist. Tail compaction runs only when an explicit time budget is provided. Manual `vacuumOnline()` still goes through the same coordinator, analyzes candidates, runs bounded online partial relocation, and returns structured diagnostics.

## Follow-up Deepening

The following capabilities now have request/result, feature-gate, or journal-scaffold entry points, but still need deeper follow-up work:

| Capability | Current State | Follow-up |
| --- | --- | --- |
| Real relocation-map read path | Feature-gated and currently unused | Add safe old-page-position to new-page-position reads and write-open compatibility rejection. |
| Full crash-safe publish semantics | Journal scaffold exists | Add publish markers, fault injection, replay/rollback, and old-version open protection. |
| Precise unknown-map diagnostics | MVStore can lazy-open maps for rewrite today | Emit a dedicated skip reason when ownership cannot be resolved. |
| Background idle scheduling | Scheduler is low-intensity enabled by default | Continue observing rate limiting, backoff, and global mutual exclusion under real workloads. |

## Operational Diagnostic Codes

| Code | Meaning | Recommended Action |
| --- | --- | --- |
| `RECLAMATION_ROUND_FINISHED` | One online reclamation round completed successfully. | Inspect `estimatedReclaimedBytes`, fill rates, and file-size changes. |
| `RECLAMATION_PAUSED_BY_TIME_BUDGET` | The round paused after reaching its run-time budget. | Keep default backoff; increase the budget only under sustained space pressure. |
| `NO_RECLAMATION_CANDIDATE` | No reclaimable chunk candidate is available now. | Normal skip; if the file is still large, inspect retention, long transactions, or relocation-map state. |
| `NO_OPEN_MAP_RELOCATION_PROGRESS` | Candidates existed, but no page relocation completed in this round. | Inspect map ownership, old-version pinning, and write contention. |
| `DRY_RUN` | Analysis-only mode, no writes. | Use for diagnostics and pre-release assessment. |
| `RECLAMATION_SCHEDULER_DISABLED` | The scheduler is configured off. | Enable `onlineReclamationEnabled(true)` or remove the disabling configuration if background reclamation is desired. |
| `RECLAMATION_SCHEDULER_BACKOFF` | The scheduler is in its minimum-interval or failure-backoff window. | Normal throttling; it avoids frequent foreground IO contention. |
| `RECLAMATION_STORE_CLOSED` | The store is already closed, so background housekeeping skips this online reclamation round. | Normal close-race protection; inspect close ordering if application code still calls maintenance entrypoints. |
| `RECLAMATION_FAILED` | This round failed with an exception. | Check the exception message, preserve files and journal state, and let the next round recover stale journal state first. |

## Formal Release Default Strategy

The formal-release default strategy for S2 online reclamation is:

| Item | Strategy | Reason |
| --- | --- | --- |
| Scheduler | Enabled by default in low-intensity mode | It reuses MVStore housekeeping and is limited by minimum interval, failure backoff, rewrite budget, and run-time budget. |
| Disable switch | Keep `onlineReclamationEnabled(false)` | Operators can immediately disable it if latency, compatibility, or debugging issues appear. |
| Journal | Disabled by default, enabled per request | Durable journaling expands the recovery and compatibility surface, so the default path avoids journal writes. |
| Relocation map | Used only when explicit mappings exist | Avoids extra behavior complexity without mappings; stores with feature metadata can be rejected when the gate is disabled. |
| Tail compaction | Triggered only with an explicit time budget | Physical tail move / truncate has higher IO impact and should not run implicitly. |

Release decision: if `runMvStoreSpaceReclamationCheck`, `runPluginArchitectureCheck`, recovery/corruption checks, and full CI pass, and the performance baseline does not show a meaningful write-latency regression, S2 can ship with the strategy above.

## Release Gate Record

Latest local release-gate results:

| Command | Result | Notes |
| --- | --- | --- |
| `.\gradlew.bat runMvStoreSpaceReclamationCheck` | PASS | Covers S2 candidate analysis, journal recovery, relocation map, scheduler, concurrent writes, and performance baselines. |
| `.\gradlew.bat runPluginArchitectureCheck` | PASS | Maintenance SPI and plugin capability gates are healthy. |
| `.\gradlew.bat runMvStoreRecoveryCheck` | PASS | MVStore recovery/corruption checks passed. |
| `.\gradlew.bat runH2LegacySmoke` | PASS | Legacy smoke passed. |
| `.\gradlew.bat runH2TestAllCi` | PASS | The 2026-05-31 rerun passed; old-jar fetch noise is gone, TestAll network phases now use dynamic TCP ports, and `TestTools` shutdown assertions tolerate connection aborts during rejection. |
| `.\gradlew.bat runH2TestAllCiPhaseReport -Ph2CiPhase=additional` | PASS | Rechecked the `TestTools` / external-tool phase. |
| `.\gradlew.bat runH2TestAllCiPhaseReport -Ph2CiPhase=network-lazy` | PASS | Rechecked the localhost-network phase. |
| `.\gradlew.bat runH2TestAllCiPhaseReport -Ph2CiPhase=lazy-memory` | PASS | Rechecked the `TestXA` lazy-memory phase. |

Release conclusion: all S2-specific gates and the full local TestAll acceptance now pass. Old-H2-jar fetching uses cache first, direct download with cache write-back, and Maven / Maven Wrapper only as fallback. TestAll network phases now use dynamic ports to reduce localhost port cross-talk, and background online reclamation now skips closed stores safely.
