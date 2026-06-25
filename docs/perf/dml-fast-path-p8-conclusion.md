# DML fast path P8 3x push conclusion

## Scope

This document records the P8 checkpoint for
`docs/plugin/plugin-dml-fast-path-plan.md`.

P8 was intended to optimize the remaining bottlenecks found by the P7 ADB/LDB
new-hook `insert_batch100` long run. P7 did not produce a runnable ADB/LDB
new-hook measurement in this workspace, so P8 must not apply speculative h2db
optimizations or claim a 3x result.

## Input from P7

P7 report: [dml-fast-path-p7-report.md](dml-fast-path-p7-report.md).

P7 conclusion:

- h2db-side DML hook, parameter view, batch view, table bulk hook, transaction
  gates, and fallback gates compile and pass plugin tests.
- ADB/LDB has not yet been moved onto the new `DmlExecutionProvider` /
  `BoundParameterBatchView` / `BulkInsertTable` path in this workspace.
- No new ADB/LDB full JDBC throughput number exists for `insert_batch100`.

## 3x status

| Target | Result |
| --- | --- |
| Native H2 full JDBC P0 baseline | `43,365 ops/s` |
| P8 3x threshold | `130,095 ops/s` |
| ADB/LDB new-hook measured throughput | Not measured |
| P8 status | Not achieved; measurement unavailable |

P8 does not claim success. The correct next step is an ADB/LDB integration run,
not more h2db-side speculative tuning.

## Optimization backlog after ADB/LDB integration

Run these checks only after ADB/LDB has a provider using the new hook:

| Priority | Candidate | Evidence needed before changing code |
| --- | --- | --- |
| 1 | Remove ADB external `PreparedStatement` proxy setter capture | Confirm ADB reads all values from `BoundParameterBatchView`. |
| 2 | Avoid per-row H2 `Row` materialization in ADB table path | Confirm ADB table implements `BulkInsertTable` and encodes rows directly. |
| 3 | Reduce provider-side `Value` decoding copies | CPU sample showing decode/copy cost dominates after proxy removal. |
| 4 | Batch transaction handoff | Measure commit/session overhead with `autoCommit=false` and fixed batch size 100. |
| 5 | Error mapping hot path | Only optimize if duplicate/type-error mapping appears in normal workload samples, which is unlikely. |
| 6 | h2db command/session overhead | Profile after ADB table bulk path is active; do not change command lifecycle without evidence. |

## No-go items for this checkpoint

- Do not expand V1 SQL scope to `MERGE`, `UPDATE`, `DELETE`, or `INSERT SELECT`.
- Do not bypass H2 transaction lifecycle.
- Do not skip generated-keys fallback.
- Do not enable fast path for row triggers, constraints, or delta table collection.
- Do not add ADB-specific types to `org.h2.api`.

## P8 acceptance

| Requirement | Evidence |
| --- | --- |
| Optimization list recorded | See `Optimization backlog after ADB/LDB integration`. |
| 3x result recorded | Not achieved; no ADB/LDB new-hook measurement was available. |
| Remaining boundary stated | ADB/LDB must implement and run the new hook path before h2db tuning continues. |
| Scope control recorded | See `No-go items for this checkpoint`. |

P8 is closed as a controlled no-op optimization checkpoint. It prevents
unmeasured h2db tuning and preserves the path for a later measured ADB/LDB 3x
push.
