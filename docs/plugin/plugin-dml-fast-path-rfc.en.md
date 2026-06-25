# DML Execution Fast Path Plugin RFC

[中文](plugin-dml-fast-path-rfc.md)

## Background

This RFC freezes the P1 design decisions for [plugin-dml-fast-path-plan.en.md](plugin-dml-fast-path-plan.en.md). P1 does not implement the fast path. It fixes the V1 SPI boundary, capability names, SQL scope, fallback behavior, and JDBC compatibility requirements so later implementation stages do not keep expanding scope.

P0 baseline: [dml-fast-path-p0-baseline.md](../perf/dml-fast-path-p0-baseline.md).

## Goals

| Goal | Decision |
| --- | --- |
| SPI package | Put the first DML fast-path SPI in `org.h2.api`, documented as experimental. |
| V1 SQL scope | Only simple `INSERT ... VALUES` and `PreparedStatement` batch. |
| Parameter view | Expose read-only `Value` views; prefer cursor/view over copying large arrays. |
| Bulk table entrypoint | Plugin tables may optionally implement a bulk insert capability. Normal tables are unaffected. |
| Fallback | If provider declines or capability is missing, continue through native H2 `Insert`. |
| Transaction boundary | H2 `SessionLocal` / transaction lifecycle still owns commit and rollback. |

## Non-Goals

- V1 does not cover `INSERT SELECT`, `MERGE`, `UPDATE`, or `DELETE`.
- V1 does not take over paths with triggers, delta change collectors, or complex generated-key requirements.
- V1 does not expose ADB-private types.
- V1 does not change disk format, JDBC public APIs, or wire protocol.
- V1 does not allow plugins to bypass H2 permissions, constraints, transactions, or errors.

## New SPI Draft

### `DmlExecutionProvider`

```java
package org.h2.api;

public interface DmlExecutionProvider extends PluginProvider {

    String TYPE = "dml-execution";

    DmlExecutionPlan prepareDml(DmlPrepareContext context);
}
```

Rules:

- `prepareDml()` only matches plans and checks lightweight metadata. It must not write data.
- Unsupported plans return `DmlExecutionPlan.none()` or an equivalent empty plan.
- Providers must not cache request-scoped mutable objects such as `SessionLocal`, `Command`, or `Parameter`.

### `DmlExecutionPlan`

```java
package org.h2.api;

public interface DmlExecutionPlan {

    boolean isSupported();

    long execute(DmlExecutionContext context);
}
```

Rules:

- `execute()` returns update count.
- `execute()` must run inside the current H2 session/transaction.
- Provider exceptions must be wrapped or mapped into the current H2 error family.

### `DmlPrepareContext`

```java
package org.h2.api;

public interface DmlPrepareContext {

    String getStatementType();

    String getTableName();

    String getTableEngineProviderId();

    int getColumnCount();

    boolean isBatchCapable();

    boolean requestsGeneratedKeys();
}
```

In V1, `getStatementType()` only needs to identify `INSERT_VALUES`.

### `DmlExecutionContext`

```java
package org.h2.api;

public interface DmlExecutionContext {

    Object getSession();

    Object getTable();

    BoundParameterView getParameters();

    BoundParameterBatchView getBatchParameters();

    boolean isBatch();

    boolean isAutoCommit();
}
```

`getSession()` and `getTable()` may initially return H2 internal objects as managed migration APIs. Documentation must state that long-term binary stability is not promised. If multiple providers need stable behavior, add narrower stable methods later.

### Parameter Views

```java
package org.h2.api;

public interface BoundParameterView {

    int size();

    Value getValue(int index);
}

public interface BoundParameterBatchView {

    int size();

    BoundParameterView get(int rowIndex);
}
```

Rules:

- `index` and `rowIndex` are 0-based.
- Views are read-only.
- View lifetime is limited to the current `executeUpdate()` / `executeBatch()` call.
- Plugins must not retain views for asynchronous use.

### `BulkInsertTable`

```java
package org.h2.api;

public interface BulkInsertTable {

    long addRows(DmlExecutionContext context, BoundParameterBatchView rows);
}
```

Rules:

- Optional capability for plugin tables.
- Unsupported tables use normal `Table.addRow()` or native `Insert`.
- Does not own commit or rollback.

## Capability Decisions

| Capability | Phase | Meaning |
| --- | --- | --- |
| `dml.insert.values.fastPath` | P2 | Supports simple `INSERT ... VALUES` plan matching. |
| `parameters.bound.view` | P3 | Supports reading bound-parameter views. |
| `dml.insert.batch.fastPath` | P4 | Supports JDBC batch insert parameter views. |
| `table.bulkInsert` | P5 | Table implementation supports bulk insert. |

These capabilities should be added to `PluginCapability`. Callers must check capabilities explicitly and must not infer support from `instanceof` or provider class names.

## V1 SQL Scope

| SQL shape | V1 behavior |
| --- | --- |
| `INSERT INTO T VALUES (?, ?)` | May take over. |
| `INSERT INTO T(C1, C2) VALUES (?, ?)` | May take over. |
| `PreparedStatement#addBatch()` + `executeBatch()` | May take over. |
| Mixed multi-values constants and parameters | P2 may identify; P3/P4 decide based on parameter-view capability. |
| `INSERT SELECT` | fallback. |
| `MERGE` | fallback. |
| `UPDATE` / `DELETE` | fallback. |
| generated keys requested | V1 fallback. |
| triggers / delta collector involved | V1 fallback. |

## Compatibility Matrix

| Scenario | V1 requirement | Test ID |
| --- | --- | --- |
| ordinary INSERT not matched | Native path unchanged | `T-DML-HOOK-FALLBACK-01` |
| unbound parameter | Same error as native PreparedStatement | `T-DML-HOOK-PARAM-01` |
| execute after `clearParameters()` | Same behavior as native path | `T-DML-HOOK-PARAM-01` |
| execute after `clearBatch()` | Empty batch behavior unchanged | `T-DML-HOOK-BATCH-01` |
| duplicate key inside batch | update count / exception behavior unchanged | `T-DML-HOOK-BATCH-FAIL-01` |
| `autoCommit=false` rollback | Fast-path writes are rollbackable | `T-DML-HOOK-TXN-ROLLBACK-01` |
| `autoCommit=true` | Commit behavior unchanged | `T-DML-HOOK-AUTOCOMMIT-01` |
| generated keys requested | V1 fallback, result unchanged | `T-DML-HOOK-GENERATED-KEYS-01` |

## Fallback Strategy

| Case | Behavior |
| --- | --- |
| provider not registered | Native path. |
| provider lacks capability | Native path. |
| SQL outside V1 scope | Native path. |
| table lacks bulk support | Native path or row-by-row path. |
| generated keys requested | Native path. |
| provider fails after takeover | Throw via current H2 error mapping; do not silently retry native path, to avoid duplicate writes. |

## Risk Register

| Risk | Mitigation |
| --- | --- |
| SPI becomes stable too early | Mark as experimental; decide stability in P9. |
| plugin keeps parameter views asynchronously | Documentation forbids it; test provider validates lifetime assumptions; implementation may invalidate views after execution. |
| batch partial failure is complex | Cover in P4/P6; complex modes may fallback first. |
| generated keys change silently | V1 always falls back when generated keys are requested. |
| plugin write bypasses H2 constraints | V1 only allows clearly eligible plugin tables; normal MVTable does not default to provider writes. |

## P1 Acceptance

| Requirement | Evidence |
| --- | --- |
| SPI package confirmed | `SPI package` decision in this document. |
| capability names confirmed | `Capability Decisions` table. |
| V1 SQL scope confirmed | `V1 SQL Scope` table. |
| fallback strategy confirmed | `Fallback Strategy` table. |
| generated keys strategy confirmed | `V1 SQL Scope` and `Fallback Strategy`. |
| compatibility matrix confirmed | `Compatibility Matrix` table. |

P2 production changes can start after P1 is committed.
