# DML Execution Fast Path Plugin Plan

[中文](plugin-dml-fast-path-plan.md)

## Background

ADB already shows high throughput potential at the lower store and transaction layers, but the full h2db JDBC path consumes much of that advantage through `PreparedStatement` parameter binding, external dynamic proxies, H2 command/session/commit paths, per-row `Row` materialization, and the `Table.addRow()` boundary.

Known long-run measurements:

| Scenario | Throughput |
| --- | ---: |
| ADB store | 250,000 ops/s |
| ADB txn | 172,413 ops/s |
| ADB full JDBC `insert_batch100` long run | 40,453 ops/s |
| H2 full JDBC `insert_batch100` long run | 43,365 ops/s |
| Current full JDBC ratio | 0.93x |

Conclusion: ADB-side optimization around the public JDBC boundary may recover the full JDBC path into the 1.x/2.x range, but a stable 3x target over native H2 full JDBC needs new h2db plugin execution hooks. Plugins need a controlled way to take over high-frequency DML paths without wrapping `PreparedStatement` externally.

## Goals

| Goal | Description |
| --- | --- |
| DML plugin fast path | Allow plugins to identify and take over selected `INSERT` plans, starting with `INSERT ... VALUES` and JDBC batch. |
| Direct parameter access | Let plugins read already-bound H2 parameters during execution, removing the need for external setter capture. |
| Bulk table entrypoint | Let `TableEngineProvider` / plugin tables receive batch parameter views and reduce per-row `Row` materialization. |
| Preserve JDBC semantics | Keep `autoCommit`, `rollback`, duplicate-key errors, update counts, generated keys, and batch failure behavior compatible. |
| Observable and reversible | Unsupported SQL, missing capability, or provider failure must be diagnosable and must fall back or fail clearly. |
| Measurable performance | Track staged overhead reduction and try to reach a stable 3x target over native H2 full JDBC. |

## Non-Goals

- Do not rewrite the H2 SQL parser or optimizer.
- V1 does not cover `INSERT SELECT`, `MERGE`, `UPDATE`, `DELETE`, complex trigger paths, or all generated-key combinations.
- Do not change public JDBC API behavior or compatibility commitments.
- Do not let plugins control commit or rollback boundaries.
- Do not expose ADB-specific types as h2db public SPI.
- Do not promise that every third-party table provider can use the bulk path in the first stage.

## Current Flow

| Path | Current classes | Current issue |
| --- | --- | --- |
| JDBC prepare | `JdbcConnection#prepareStatement()`、`JdbcPreparedStatement` | Plugins cannot identify takeover candidates during prepare. |
| Parameter binding | `JdbcPreparedStatement#setParameter()`、`CommandInterface` parameter list | Plugins cannot directly read bound parameters, so ADB currently uses external proxy setter capture. |
| Execution entry | `JdbcPreparedStatement#executeUpdateInternal()`、`executeBatch()` | Batch is executed element by element and cannot be passed to a plugin as one view. |
| INSERT execution | `org.h2.command.dml.Insert` | Current path evaluates expressions and calls `table.addRow(session, row)` row by row. |
| Table write | `org.h2.table.Table#addRow()`、`MVTable#addRow()` | Plugin tables do not have a bulk insert hook. |
| Plugin capabilities | `TableEngineProvider`、`StorageEngineProvider`、`TransactionEventProvider` | Existing plugin entrypoints do not cover statement / prepare / DML execution. |

## Core Constraints

| Constraint | Requirement |
| --- | --- |
| Java 8 | New SPI must not use post-Java-8 language features or APIs. |
| Compatibility first | Unsupported SQL must keep using the current execution path. |
| Transaction consistency | Plugins may take over execution implementation, but not transaction boundaries. |
| Error consistency | Duplicate keys, conversion errors, constraint failures, and batch partial failures must keep caller-visible semantics. |
| Read-only parameters | Parameter views must be read-only. Plugins must not mutate H2 parameter state. |
| Explicit capabilities | Capability checks must decide support; do not infer support from class names. |
| Testability | Each phase must have traceable unit, integration, and long-run outputs. |

## Interface Direction

Initial interface candidates:

| Interface | Role | Stability |
| --- | --- | --- |
| `DmlExecutionProvider` | Plugin-level DML plan matching and execution entrypoint. | Experimental SPI |
| `DmlExecutionContext` | Execution context with session, table, columns, mode, trace, and transaction state. | Experimental SPI |
| `BoundParameterView` / `BoundParameterBatchView` | Read-only `Value` parameter views for single-row and batch execution. | Experimental SPI |
| `BulkInsertTable` | Optional bulk write capability for plugin tables. | Managed migration API |

Suggested capabilities:

| Capability | Meaning |
| --- | --- |
| `dml.insert.values.fastPath` | Supports `INSERT ... VALUES` fast path. |
| `dml.insert.batch.fastPath` | Supports JDBC batch insert fast path. |
| `table.bulkInsert` | Table implementation supports bulk insert. |
| `parameters.bound.view` | Provider can read H2 bound-parameter views. |

## Semantic Boundaries

| Semantic area | Requirement |
| --- | --- |
| `autoCommit=true` | H2 still owns statement or batch commit semantics. |
| `autoCommit=false` | Plugin writes must join the current session transaction and be rollbackable. |
| update count | Successful row counts must match native H2; failed batch elements follow JDBC behavior. |
| duplicate key | Plugins must map conflicts to the current H2 unique-constraint exception family. |
| generated keys | V1 may only support fallback when generated keys are requested. |
| trigger / delta table | V1 should not take over tables with triggers, delta collectors, or complex constraints. |
| fallback | If the provider declines, H2 continues through the native `Insert` path. |

## Phased Plan

| Phase | Target | Deliverable | Acceptance |
| --- | --- | --- | --- |
| P0 Baseline | Freeze current H2/ADB/JDBC long-run methodology. | Baseline report, fixed config, result template, long-run commands. | Reproducible H2 and current ADB JDBC measurements with recorded variance. |
| P1 Design Freeze | Confirm SPI package, capability names, fallback, and V1 SQL scope. | RFC update, interface draft, compatibility matrix, risk register. | Review complete; V1 limited to simple INSERT VALUES / JDBC batch. |
| P2 Prepare Match Hook | Identify takeover-capable `Insert` plans without changing execution. | Provider registration, plan matching prototype, diagnostics, tests. | Plugin can identify target INSERT; unrelated SQL is unaffected. |
| P3 Parameter View | Expose read-only single-row bound parameters during execution. | `BoundParameterView`, index/type/NULL tests, read-only checks. | Plugin can read `executeUpdate()` parameters; normal PreparedStatement behavior is unchanged. |
| P4 Batch Parameter View | Expose JDBC batch parameters as a read-only batch view. | `BoundParameterBatchView`, empty/partial-failure/clearBatch tests. | `executeBatch()` can provide all batch parameters in order. |
| P5 Table Bulk Insert | Add an optional plugin-table bulk insert hook. | `BulkInsertTable` prototype, plugin-table tests, fallback path. | Plugin table receives batch data and returns update count; normal tables unaffected. |
| P6 Transaction and Errors | Complete autoCommit, rollback, unique constraint, conversion, and read-only gates. | Compatibility tests, error mapping, rollback tests. | Fast path and native path match the scoped semantic matrix. |
| P7 Performance Long Run | Run ADB through the new hook with full JDBC `insert_batch100`. | Performance report, sampling data, bottleneck list. | First target is above native H2 1.5x; otherwise report remaining bottlenecks. |
| P8 3x Push | Optimize remaining P7 bottlenecks without expanding SQL scope. | Optimization list, second long-run report, 3x conclusion. | Reach native H2 3x or state which h2db/ADB boundaries still need work. |
| P9 Documentation and Release | Freeze SPI usage, unsupported cases, fallback, and diagnostics. | Developer docs, release notes, sample plugin. | Docs are enough for ADB to remove external `PreparedStatement` proxy usage. |

## Phase Acceptance Details

| Phase | Output | Goal | Exit condition |
| --- | --- | --- | --- |
| P0 | [dml-fast-path-p0-baseline.md](../perf/dml-fast-path-p0-baseline.md) | Use one machine, JDK, config, data scale, and batch size for future comparisons. | Current 0.93x ratio is reproducible and tied to commit/config/command. |
| P1 | [plugin-dml-fast-path-rfc.en.md](plugin-dml-fast-path-rfc.en.md) | Prevent scope creep into all SQL execution. | V1 SQL scope, fallback, and generated-key handling are confirmed. |
| P2 | Plan matching code | Build identification only, no behavior change. | Native `INSERT`, `SELECT`, `UPDATE`, and legacy table behavior unchanged. |
| P3/P4 | Single-row and batch parameter views | Remove ADB external setter-capture proxy need. | NULL, conversion, unbound parameters, clearParameters, and clearBatch match native behavior. |
| P5 | Bulk table hook | Let plugin tables receive batch data without per-row `Row` overhead. | Plugin table update count matches native path; normal tables unaffected. |
| P6 | Compatibility gates | Preserve JDBC semantics. | Scoped compatibility matrix passes. |
| P7/P8 | Performance reports | Measure whether hooks remove enough overhead. | Reach target or explain remaining bottlenecks by layer. |

## Test Matrix

| ID | Coverage | Phase |
| --- | --- | --- |
| `T-DML-HOOK-PREPARE-01` | Plugin identifies simple `INSERT ... VALUES` plans. | P2 |
| `T-DML-HOOK-FALLBACK-01` | Unsupported SQL falls back to native execution. | P2 |
| `T-DML-HOOK-PARAM-01` | executeUpdate parameter view matches H2 bound values. | P3 |
| `T-DML-HOOK-BATCH-01` | executeBatch parameter view preserves addBatch order. | P4 |
| `T-DML-HOOK-BATCH-FAIL-01` | Batch failure update count / exception behavior matches native path. | P4 |
| `T-DML-HOOK-BULK-TABLE-01` | Plugin table bulk hook receives batch writes. | P5 |
| `T-DML-HOOK-BULK-FALLBACK-01` | Tables without bulk support fall back safely. | P5 |
| `T-DML-HOOK-TXN-ROLLBACK-01` | rollback undoes fast-path writes with autoCommit=false. | P6 |
| `T-DML-HOOK-AUTOCOMMIT-01` | autoCommit=true semantics match native path. | P6 |
| `T-DML-HOOK-DUPKEY-01` | Duplicate-key error mapping matches native path. | P6 |
| `T-DML-HOOK-GENERATED-KEYS-01` | generated keys either fall back or match after support is added. | P6/P9 |
| `T-DML-HOOK-PERF-01` | ADB new-hook path runs `insert_batch100` long run and produces a report. | P7 |

## Performance Targets

| Phase | Metric | Target |
| --- | --- | --- |
| P0 | ADB current full JDBC / H2 full JDBC | Freeze current approx. 0.93x baseline. |
| P3 | After removing proxy setter capture | Observe CPU overhead reduction and enter the 1.x range. |
| P5 | After table bulk insert hook | Reach or exceed native H2 1.5x. |
| P7 | Full long run with new hook | Stay above native H2 1.5x. |
| P8 | Limited optimization | Try to reach native H2 3x. |

Performance reports must include h2db commit, ADB commit, JDK, machine, disk, OS, JDBC URL, batch size, row width, primary-key strategy, transaction mode, generated-key mode, autoCommit, indexes, constraints, CPU, GC, IO, lock waits, and sampling hotspots.

## Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Fast path breaks JDBC semantics | User-visible regression | P6 must pass before release candidate. |
| SPI becomes ADB-specific | h2db plugin ecosystem cannot reuse it | Expose only generic H2 `Value` / parameter view / table capability. |
| Batch failure semantics are complex | update count mismatch | V1 can fall back on complex failure modes. |
| generated keys are expensive | result mismatch | V1 falls back; later design separately. |
| Row materialization remains hot | 3x target missed | P7 must split Row, Value, encoding, transaction, and IO costs. |
| Plugin bypasses permissions or triggers | database semantic breakage | V1 declines tables with triggers, delta collectors, or complex constraints. |

## Rollback Strategy

- All fast paths must be guarded by capability checks.
- Provider decline must continue through native execution.
- Add a database or system switch to disable DML fast path.
- Without disk-format changes, code rollback does not affect old database opening.
- If semantic mismatch appears, disable the fast path first and keep the plugin infrastructure.

## Open Questions

| Question | Initial recommendation |
| --- | --- |
| Should SPI live in `org.h2.api` or an internal experimental package? | Start as experimental SPI and document that binary stability is not promised yet. |
| Should parameter view expose `Value[][]` or a cursor? | Use cursor/view first to avoid copying large batches. |
| Should TCP server path be supported? | Yes; after server-side binding, the same command fast path should apply. |
| When should generated keys be supported? | V1 fallback; design separately in P9 or later. |
| Is `INSERT SELECT` in scope? | Not in V1. Evaluate separately. |
| Can fast-path errors be plugin-defined? | Prefer no; map to current H2 error families. |
