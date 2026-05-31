# MVStore Space Reclamation S2 Readiness

This document records readiness for the S2 long-term solution. The phase boundary is explicit: S1, the medium-term solution, is complete; S2 is the long-term solution itself, and following space reclamation work is tracked as S2.0-S2.8.

## Readiness Conclusion

S2 is implemented through the current release gate. Pluginization prerequisites are closed, the MVStore storage engine exposes maintenance capabilities through the built-in provider path, and the dedicated and full legacy gates are runnable. The S2 main path is chunk/page-level online reclamation inside MVStore, not full-store shadow copy and not a simple wrapper around `compactFile()`.

## Existing Foundation

| Foundation | Status |
| --- | --- |
| Maintenance entrypoint | `StorageMaintenance.vacuumOnline()` exists and can serve as the S2 external entrypoint. |
| Local compact base | `MVStore.compact()`, `MVStore.compactFile()`, `FileStore.rewriteChunks()`, and `RandomAccessStore.compactMoveChunks()` exist. |
| Offline/fallback scaffolding | `MVStoreSpaceReclamation` provides closed-store shadow compact, manifest, recover, and fault harness basics. |
| Diagnostics base | `MVStoreSpaceReclamationResult`, listener, phases, and dedicated tests exist. |
| Test gate | `runMvStoreSpaceReclamationCheck` can run independently. |
| Pluginization prerequisite | Storage/table providers, maintenance capabilities, diagnostic tables, and JUnit plugin gates are complete. |

## S2 Phases

| Phase | Goal | Main Deliverable |
| --- | --- | --- |
| S2.0 | Close the design | Chinese/English long-term design, phase plan, confirmed test gates. |
| S2.1 | Observability and decision | Chunk liveness snapshot, candidate scoring, dry-run result. |
| S2.2 | Govern existing partial compact | Coordinator wiring, budgets, no-progress diagnostics. |
| S2.3 | Page relocation main path | Live-page relocation for open maps, long transaction skip, unknown map skip. |
| S2.4 | Persistent evacuation journal | Crash recovery, continuation, or cleanup of unfinished jobs. |
| S2.5 | Relocation map | Old-version reads for moved pages and long-retention pinning. |
| S2.6 | Integrated tail mover | Move tail chunks and shrink file after relocation. |
| S2.7 | Background scheduling | Low-intensity default scheduling, with idle budget, throttling, dry-run, and disable switch. |
| S2.8 | Operationalization | Chinese/English docs, diagnostic table, configuration guide, long-running slow tests. |

## Test Strategy

| Level | Requirement |
| --- | --- |
| JUnit | Request/result defaults, candidate scoring, budgets, messages, feature flags. |
| MVStore dedicated | Chunk bloat, page relocation, unknown map, long transaction, tail shrink, no-progress. |
| Fault injection | Before/after journal publish, interruption during free/shrink, missing relocation map. |
| Concurrency | Writes during reclamation, long read transactions, close/backup/compact mutual exclusion. |
| Compatibility | Old database open, new feature flag, unfinished journal recovery, read-only downgrade. |

Minimum command for each phase:

```powershell
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

When `StorageMaintenance` or plugin capabilities change, also run:

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

Higher-risk phases should also run the daily gate or related `TestAll ci` phase.

## Non-Main Path

| Capability | Handling |
| --- | --- |
| Full-store shadow publish | Keep as offline compact or fallback capability, not the S2 online main path. |
| SQL command | Not an S2 starting point; revisit after the Java maintenance API and diagnostics are stable. |
| Plugin hot loading/signing/permission sandbox | Later pluginization work, not part of S2. |

## Decisions Needed

| Question | Recommendation |
| --- | --- |
| Should relocation map be allowed? | Yes, but behind a feature flag, and old versions must reject write-open. |
| Should reclamation work without pre-opening all maps? | Yes as the S2 final goal; start with open maps, then add lazy open / unknown-map diagnostics. |
| Should background execution be default? | Yes, after stabilization. It now runs in low-intensity mode by default and can be disabled with `onlineReclamationEnabled(false)`. |
| Should long transactions be forced to wait? | No. Skip pinned chunks by default; handle old-version reads after relocation map is mature. |

## Working Rules

Commit locally after every completed S2 phase. New production code must add tests in the same phase; use JUnit for contract coverage when practical, and keep MVStore file, fault injection, and concurrency scenarios in the dedicated legacy test gate. External docs need matching English copies.
