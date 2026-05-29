# H2 MVStore Space Reclamation Implementation Plan (Draft)

This is the English companion of
[h2db-space-reclamation-implementation-plan.md](h2db-space-reclamation-implementation-plan.md).
The Chinese document is the primary version.

## Intent

This plan drives `.mv.db` space reclamation optimization. It does not replace the
current file-corruption recovery work. The medium-term path is to make
`shadow compact + short switch` an internal capability that is verifiable and
recoverable. The long-term `online chunk vacuum` work remains a separate storage
layer optimization.

## Scope

| Scope | Content |
| --- | --- |
| In | Space bloat reproduction, shadow compact, maintenance mode, manifest, crash recovery, TCP server behavior, test matrix, and rollout plan. |
| Out | Current `.mv.db` corruption root-cause fixes, unsupported multi-writer scenarios, application-level backup/restore redesign, and immediate implementation of long-term online chunk vacuum. |

## Milestones

| Milestone | Estimate | Goal |
| --- | --- | --- |
| P0 Documents and review | 2-3 days | Clarify design boundaries, test ids, and acceptance gates. |
| P1 Test foundation | 1-2 weeks | Add a dedicated test entry point and crash/fault injection. |
| P2 Medium-term prototype | 2-3 weeks | Prove shadow compact, maintenance mode, and basic switch. |
| P3 Medium-term hardening | 2-3 weeks | Add manifest, recovery, concurrency, and TCP server behavior. |
| P4 Rollout validation | 1-2+ weeks | Validate large stores, slow disks, long-running stress, and operations integration. |
| P5 Long-term research | 3-6 months | Design, prototype, and test online chunk vacuum. |

The medium-term path is expected to become internally usable in 4-8 weeks. A
production-ready version should be planned more conservatively at 6-10 weeks.
The long-term path is estimated at 3-6 months for research and 6-9 months for a
production-grade implementation.

## Gates

| Gate | Requirement |
| --- | --- |
| G0 Documentation | Update the RFC and this plan before changing `h2/src/main`. |
| G1 Test ids | Register a test id before implementing each new risk area. |
| G2 Read-only proof | Logic that affects recovery, switching, or file replacement must first be characterized by tests. |
| G3 Crash coverage | File replacement, manifest, and truncate changes require crash injection tests. |
| G4 Data verification | Tests must verify marker or model data, not only that a file can be opened. |
| G5 Rollback | New functionality must be off by default or have an explicit gate; the old store must remain usable after failures. |

## Task List

- [ ] Review [h2db-space-reclamation-optimization-rfc.md](h2db-space-reclamation-optimization-rfc.md) and confirm S1 as the medium-term path and S2 as the long-term optimization.
- [x] Add a space reclamation test registry without expanding the corruption investigation plan further.
- [x] Add `TestMVStoreSpaceReclamation` or an equivalent test class.
- [x] Add a dedicated Gradle entry point such as `runMvStoreSpaceReclamationCheck`.
- [x] Add fault injection for copy failure, manifest write failure, verify failure, crash before switch, crash during switch, and cleanup failure.
- [x] Design and implement maintenance manifest fields and recovery rules.
- [x] Implement the internal S1 prototype: create a shadow file, verify it, block new write transactions in maintenance mode, and perform a basic switch.
- [x] Add the conservative safety gate for writes during copy: reject switching if the source file changes after shadow creation.
- [ ] Complete the real write catch-up strategy during copy, preferably by validating version-scan catch-up.
- [ ] Add TCP server, backup, long transaction, slow disk, and large file tests.
- [x] Add internal diagnostics and this English companion plan before rollout; public settings and user-visible failure logging still require API review.
- [ ] Start the detailed S2 `online chunk vacuum` design and invariant tests after S1 stabilizes.

## Current Implementation Status

This branch has completed internal phases 1 through 8. The scope is still a
controlled maintenance entry point: no SQL exposure, no automatic scheduling,
and no `.mv.db` format change.

| Phase | Status | Delivery |
| --- | --- | --- |
| Phase 1 | Done | Space bloat samples, dedicated test entry point, and fault injection matrix. |
| Phase 2 | Done | `compactClosedStore()` supports closed-store maintenance compact, verify, backup, and replace. |
| Phase 3 | Done | `compactToShadow()` creates and verifies a shadow file without replacing the source store. |
| Phase 4 | Done | `.reclaim.manifest` records preparing, verifying, shadow-ready, and switching states; `recover()` can clean up or roll back incomplete operations. |
| Phase 5 | Done | `switchToShadow()` promotes a prepared shadow file and restores from backup on failure when possible. |
| Phase 6 | Done | `MVStoreSpaceReclamationResult` exposes saved bytes, saved percent, and a diagnostic summary; rollout diagnostics are covered by tests. |
| Phase 7 | Done | The manifest records source size and last-modified time; `switchToShadow()` rejects switching when the source file changed; `T-ONLINE-COMPACT-CATCHUP-WRITES-01` fixes this conservative safety behavior. |
| Phase 8 | Done | The manifest adds a SHA-256 source-content fingerprint; `switchToShadow()` validates size, last-modified time, and digest to reduce false switches from same-size changes or timestamp granularity. |

Remaining productization work after phase 8 includes public entry-point review,
real incremental catch-up, TCP server behavior, backup/restore exclusion, long
transaction and slow-disk stress tests, user-visible logging rules, and the
separate S2 online chunk vacuum RFC.
After phase 8, writes during copy are no longer silently overwritten. This is a
safe rejection policy, not true incremental catch-up. A short-blocking online
compact still needs version-scan catch-up or a dual-write/change-log design.

## P0: Documents and Review

Goal: separate optimization work from corruption recovery work and avoid storage
core changes before risks are understood.

Deliverables:

- `docs/h2db-space-reclamation-optimization-rfc.md`
- `docs/h2db-space-reclamation-implementation-plan.md`
- S1/S2 trade-off conclusion.
- Initial test ids.

Acceptance:

- The current work is explicitly an optimization path, not corruption repair.
- The medium-term path does not change the `.mv.db` file format.
- The long-term path requires a separate design and is not mixed into the S1 implementation.

## P1: Test Foundation

Goal: make the risks reproducible, verifiable, and suitable for regression
testing.

Status: phase 1 is complete. `TestMVStoreSpaceReclamation` and
`runMvStoreSpaceReclamationCheck` cover the bloat baseline, shadow compact
shrink behavior, maintenance write-blocking contract, verify failure, crash
before switch, crash during switch, and a fault injection matrix for copy,
manifest, verify, switch, and cleanup. This phase does not change production
logic in `h2/src/main`.

Test ids:

| Id | Goal |
| --- | --- |
| `T-SPACE-BLOAT-BASELINE-01` | Fix the bloat sample when auto compact is disabled. |
| `T-SHADOW-COMPACT-SHRINK-01` | Verify shadow compact shrinks the file and preserves marker data. |
| `T-ONLINE-COMPACT-BLOCKS-WRITES-01` | Verify maintenance mode blocks new writes. |
| `T-ONLINE-COMPACT-VERIFY-FAIL-01` | Verify failed validation does not replace the old store. |
| `T-ONLINE-COMPACT-CATCHUP-WRITES-01` | Before real catch-up exists, verify switching is rejected when the source changes, avoiding silent data loss. |
| `T-ONLINE-COMPACT-SOURCE-FINGERPRINT-01` | Verify the manifest records source size, last-modified time, and a SHA-256 content fingerprint. |
| `T-ONLINE-COMPACT-CRASH-BEFORE-SWITCH-01` | Verify crash before switch keeps the old store recoverable. |
| `T-ONLINE-COMPACT-CRASH-DURING-SWITCH-01` | Verify crash during switch can recover to the old or new store. |
| `T-ONLINE-COMPACT-DIAGNOSTICS-01` | Verify saved bytes, saved percent, and diagnostic summary output. |

Acceptance commands:

```powershell
.\gradlew.bat compileJava
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

## P2: Medium-Term Prototype

Goal: prove the shortest path for `shadow compact + maintenance mode + basic
switch`.

Work items:

- Add internal options and result objects.
- Add shadow file naming and cleanup rules.
- Reuse `MVStoreTool.compact(source, target)` to create a shadow file under a controlled entry point.
- Add minimal maintenance mode that blocks new write transactions.
- Switch after shadow verification.

Non-goals:

- No new SQL exposure.
- No automatic scheduling.
- No Query/SQL post-execution hook for space reclamation.
- No long-term chunk vacuum implementation.

Acceptance:

- The bloat sample shrinks.
- Marker data is preserved.
- Verification failure does not replace the source store.
- Failure before switch does not affect the old store.

## P3: Medium-Term Hardening

Goal: make S1 internally usable.

Work items:

- Complete the manifest.
- Add startup recovery.
- Define write handling during copy.
- Define read behavior in maintenance mode.
- Add TCP server wait, timeout, busy error, and logging behavior.
- Add backup/restore exclusion rules.
- Add Windows replacement and leftover cleanup tests.

Acceptance:

- `T-ONLINE-COMPACT-CATCHUP-WRITES-01` passes.
- `T-ONLINE-COMPACT-TCP-BEHAVIOR-01` passes.
- `T-ONLINE-COMPACT-BACKUP-INTERACTION-01` passes.
- The crash injection matrix passes.

## P4: Rollout Validation

Goal: prove S1 is reliable under realistic operating characteristics.

Scenarios:

- Large stores: tens of MB, hundreds of MB, and real user-sized samples.
- Slow disks: longer copy, fsync, and rename.
- High delete rate: sustained low fill rate.
- Long transactions: long read-only and write transactions interleaved.
- TCP server: concurrent reads and writes across multiple connections.
- Backup: periodic `SCRIPT DROP TO` interleaved with compact.

Exit criteria:

- Any crash/fault scenario produces an unrecoverable file.
- Any marker or model data mismatch occurs.
- Maintenance blocking time exceeds configuration and cannot be diagnosed.
- A disk format change is required without a separate RFC.

## P5: Long-Term Online Chunk Vacuum

Goal: research and implement a more elegant online reclamation path without
affecting S1 delivery.

| Subphase | Goal |
| --- | --- |
| P5.1 Invariant document | Define hard constraints for root, page, chunk, version, and truncate. |
| P5.2 White-box tests | Add tests for chunk relocation, old version protection, and metadata publication. |
| P5.3 Prototype | Move low-utilization chunks only through a test entry point. |
| P5.4 Crash injection | Cover relocation, publish, free, and truncate persistence points. |
| P5.5 Randomized model | Compare random put/remove/commit/rollback/vacuum/crash with an in-memory model. |
| P5.6 Long stress | Run multi-hour to multi-day loops to evaluate reclamation and corruption risk. |

Prerequisite tests:

- `T-ONLINE-VACUUM-RELOCATE-CHUNK-01`
- `T-ONLINE-VACUUM-LONG-TRANSACTION-01`
- `T-ONLINE-VACUUM-CRASH-PUBLISH-01`
- `T-ONLINE-VACUUM-TRUNCATE-01`
- `T-ONLINE-VACUUM-RANDOMIZED-01`

## Verification Commands

```powershell
.\gradlew.bat compileJava
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat "-Dh2.test.mvStoreRecoveryCorruption.characterize=true" runMvStoreRecoveryCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

After production code changes, run at least `compileJava` and the relevant
dedicated test task. Changes involving file replacement, recovery, manifest, or
truncate must also run the crash/fault injection matrix.

## Risk Register

| Risk | Stage | Test requirement |
| --- | --- | --- |
| Writes during copy are lost | S1 | `T-ONLINE-COMPACT-CATCHUP-WRITES-01` |
| Shadow verification is insufficient | S1 | `T-ONLINE-COMPACT-VERIFY-FAIL-01` |
| Crash during switch | S1 | `T-ONLINE-COMPACT-CRASH-DURING-SWITCH-01` |
| Maintenance-mode deadlock | S1 | Concurrency and timeout tests with lock-order diagnostics. |
| Backup observes a partial file | S1 | `T-ONLINE-COMPACT-BACKUP-INTERACTION-01` |
| Long-transaction old versions are released too early | S1/S2 | `T-ONLINE-COMPACT-LONG-TRANSACTION-01`, `T-ONLINE-VACUUM-LONG-TRANSACTION-01` |
| Truncate removes a still-visible chunk | S2 | `T-ONLINE-VACUUM-TRUNCATE-01` |

## Open Questions

- Can S1 catch-up be implemented reliably by scanning MVStore versions, or does it require dual-write/change logging?
- Should read requests remain allowed in maintenance mode?
- Should the final capability be exposed as SQL, a tool API, a database setting, or only an internal operations entry point?
- Can S2 avoid disk format changes completely? This must be proven in the detailed design.
