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
| S2.0 | In progress | Close the design | New long-term architecture design, plan, Chinese/English copies | `runMvStoreSpaceReclamationCheck` |
| S2.1 | Planned | Observability and decision | `ChunkLivenessSnapshot`, candidate scoring, dry-run result, diagnostic messages | JUnit + MVStore dedicated |
| S2.2 | Planned | Govern existing partial compact | Wire `MVStoreReclamationCoordinator` into `vacuumOnline()`, budgets, no-progress, mutual exclusion | JUnit + MVStore dedicated + plugin gate |
| S2.3 | Planned | Page relocation main path | Live-page relocation for open maps, unknown map skip, long transaction skip | MVStore dedicated + concurrency |
| S2.4 | Planned | Persistent evacuation journal | Layout/meta journal, phase replay, publish marker, crash recovery | Fault injection + recovery |
| S2.5 | Planned | Relocation map | Old pos to new pos, feature flag, old-version compatibility strategy | JUnit + compatibility + recovery |
| S2.6 | Planned | Integrated tail mover | Move tail chunks after relocation, shrink file, no-shrink diagnostics | MVStore dedicated + slow tests |
| S2.7 | Planned | Background scheduling | Default off, idle budget, throttling, dry-run, mutual exclusion | Scheduler + concurrency |
| S2.8 | Planned | Operational closeout | Chinese/English usage docs, configuration, diagnostic table, long-running slow baseline | Docs + daily gate |

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
| S2.7 | Done | Scheduler facade that is disabled by default and reuses the same coordinator when enabled. |
| S2.8 | Done | Chinese and English design, plan, and operational diagnostics documentation updated. |

## Current Defaults

S2 stays conservative by default: background scheduling is disabled, journaling is disabled, relocation maps are disabled and do not change the read path, and tail compaction runs only when an explicit time budget is provided. Manual `vacuumOnline()` goes through the coordinator, analyzes candidates, runs bounded online partial relocation, and returns structured diagnostics.

## Follow-up Deepening

The following capabilities now have request/result, feature-gate, or journal-scaffold entry points, but still need deeper follow-up work:

| Capability | Current State | Follow-up |
| --- | --- | --- |
| Real relocation-map read path | Feature-gated and currently unused | Add safe old-page-position to new-page-position reads and write-open compatibility rejection. |
| Full crash-safe publish semantics | Journal scaffold exists | Add publish markers, fault injection, replay/rollback, and old-version open protection. |
| Precise unknown-map diagnostics | MVStore can lazy-open maps for rewrite today | Emit a dedicated skip reason when ownership cannot be resolved. |
| Background idle scheduling | Scheduler facade is disabled by default | Add idle budgets, rate limiting, and global mutual exclusion without affecting foreground latency. |
