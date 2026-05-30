# MVStore Space Reclamation S2 Design Index

This document fixes the S2 phase boundary so future work does not drift.

## Phase Definition

| Phase | Status | Definition |
| --- | --- | --- |
| S1 | Done | Medium-term solution. Existing maintenance entrypoints, partial compact base, shadow/closed-store scaffolding, dedicated test gates, and pluginized maintenance boundaries are in place. |
| S2 | Current work | Long-term solution. The goal is chunk/page-level online reclamation inside MVStore, not full-store shadow copy and not a simple wrapper around `compactFile()`. |

The primary S2 design document is `mvstore-space-reclamation-long-term-design.md`. Future development plans, phase tracking, and test gates follow that document.

## S2 Main Path

S2 must advance these long-term capabilities:

| Capability | Description |
| --- | --- |
| `MVStoreReclamationCoordinator` | Unified maintenance entrypoint, budgets, mutual exclusion, recovery, and result aggregation. |
| `ChunkLivenessAnalyzer` | Analyze chunk fill rate, live/dead bytes, pinned versions, and map/page ownership. |
| `ReclamationCandidateSelector` | Select candidate chunks by benefit, risk, position, and budget. |
| `PageRelocator` | Move live pages out of candidate chunks to new locations. |
| `ChunkEvacuationJournal` | Persist job, phase, candidate chunks, migration progress, and publish markers. |
| `RelocationMap` | Support old-version reads of moved pages and solve long-retention-pinned chunks. |
| `TailCompactor` | Move tail chunks and shrink the file after chunks become dead. |

## Non-Main Path

| Capability | S2 Position |
| --- | --- |
| Full-store shadow publish | Keep only as offline compact or fallback capability, not the online main path. |
| Simple `compactFile()` wrapper | Only an early implementation path in S2.2; it does not complete S2. |
| Automatic background scheduling | S2.7, default off, after manual flow and recovery semantics are stable. |
| SQL command | Not the S2 starting point; revisit after Java maintenance API and diagnostics are stable. |

## Phase Plan

| Phase | Goal |
| --- | --- |
| S2.0 | Close the design. |
| S2.1 | Observability and decision: chunk liveness snapshot, candidate scoring, dry-run result. |
| S2.2 | Govern existing partial compact: coordinator wiring, budgets, no-progress diagnostics. |
| S2.3 | Page relocation main path. |
| S2.4 | Persistent evacuation journal. |
| S2.5 | Relocation map. |
| S2.6 | Integrated tail mover. |
| S2.7 | Background scheduling. |
| S2.8 | Operationalization, docs, and long-running slow tests. |

## Test Gates

Minimum command for each phase:

```powershell
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

When `StorageMaintenance` or plugin capabilities change, also run:

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

Higher-risk phases should also run the daily gate or related `TestAll ci` phase, with focused phase rerun results recorded for network flakes.
