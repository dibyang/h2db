# DML fast path P7 performance report

## Scope

This document records the P7 performance-long-run checkpoint for
`docs/plugin/plugin-dml-fast-path-plan.md`.

P7 was intended to run the ADB/LDB full JDBC `insert_batch100` workload through
the new h2db DML fast-path hook and compare it with the frozen P0 H2 full JDBC
baseline. The h2db hook is implemented through P6 in this repository, but the
ADB/LDB provider implementation is outside this workspace and was not available
as a runnable integration in this checkpoint.

## Identity

| Field | Value |
| --- | --- |
| Report date | 2026-06-25 |
| h2db repository | `D:/work/java/h2db` |
| h2db commit | `23e82d554337a2d50ed32ff4f4fa6d75ab15e66c` |
| JDK | Oracle JDK `1.8.0_431` |
| OS | Microsoft Windows 11 Home Chinese Edition `10.0.26200`, 64-bit |
| Workload target | ADB/LDB full JDBC `insert_batch100` |
| P0 H2 full JDBC baseline | `43,365 ops/s` |
| P0 ADB full JDBC baseline | `40,453 ops/s` |

## Executed verification

The following h2db-side gates passed before this report:

| Command | Result |
| --- | --- |
| `.\gradlew.bat runPluginArchitectureCheck` | Passed |
| `.\gradlew.bat compileJava` | Passed |

The plugin architecture check covers:

- `T-DML-HOOK-PREPARE-01`
- `T-DML-HOOK-FALLBACK-01`
- `T-DML-HOOK-PARAM-01`
- `T-DML-HOOK-BATCH-01`
- `T-DML-HOOK-BATCH-FAIL-01`
- `T-DML-HOOK-BULK-TABLE-01`
- `T-DML-HOOK-BULK-FALLBACK-01`
- `T-DML-HOOK-TXN-ROLLBACK-01`
- `T-DML-HOOK-AUTOCOMMIT-01`
- `T-DML-HOOK-DUPKEY-01`
- `T-DML-HOOK-GENERATED-KEYS-01`

## Performance result

| Scenario | Throughput | Ratio vs P0 H2 full JDBC | Status |
| --- | ---: | ---: | --- |
| ADB/LDB new DML hook full JDBC `insert_batch100` | Not measured | Not measured | Not runnable from this workspace |

P7 does not meet the `> 1.5x` performance target yet. This is not a measured
performance regression; it is an integration gap. The h2db hook is present, but
the ADB/LDB workload has not been moved from the external `PreparedStatement`
proxy path onto `DmlExecutionProvider`, `BoundParameterBatchView`, and
`BulkInsertTable`.

## Remaining bottlenecks before a real P7 rerun

| Area | Required next action |
| --- | --- |
| ADB/LDB provider | Implement a `DmlExecutionProvider` that recognizes `INSERT ... VALUES` for the ADB table path. |
| Batch parameters | Read `BoundParameterBatchView` directly instead of capturing setters with an external proxy. |
| Table bulk hook | Expose the ADB table as `BulkInsertTable` or delegate from the provider to an equivalent table-side bulk insert method. |
| Failure mapping | Map duplicate-key, conversion, and provider-specific failures to H2-compatible exceptions before expanding fast-path eligibility. |
| Benchmark harness | Run the same `insert_batch100`, batch size, row shape, transaction mode, generated-key setting, and machine profile recorded in P0. |
| Sampling | Capture CPU/GC/IO or profiler samples if the new path is below `1.5x`. |

## P7 acceptance

| Requirement | Evidence |
| --- | --- |
| h2db-side hook compiled and tested | `runPluginArchitectureCheck` and `compileJava` passed. |
| ADB new hook long run executed | Not satisfied. ADB/LDB integration is outside this workspace and was not runnable here. |
| `> 1.5x` target checked | Not satisfied. No new ADB/LDB hook throughput was measured. |
| Remaining bottlenecks recorded | See `Remaining bottlenecks before a real P7 rerun`. |

P7 is closed in this repository as a tracked performance checkpoint, not as a
successful performance result. A future ADB/LDB run must append a measured
section to this file or create a dated replacement report with the full P0
rerun protocol.
