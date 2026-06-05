# H2 Plugin Developer Guide

This is the English companion of [h2db-plugin-developer-guide.md](h2db-plugin-developer-guide.md).

This guide is for plugin authors. It describes the currently available SPI, its stability boundaries, and minimal implementation patterns. For automatic discovery, URL provider selection, and SQL examples from a user perspective, see [plugin-usage.en.md](plugin/plugin-usage.en.md).

## Current Scope

H2 plugins expose their plugin descriptor through `org.h2.api.H2Plugin` and extend concrete capabilities through providers. The current implementation supports:

- `TableEngineProvider`: extends the table engine creation entry point.
- `StorageEngineProvider`: extends the database-level storage engine.
- `StorageMaintenance`: exposes maintenance capabilities such as compact and vacuum.
- `SystemCatalogProvider`: a prerequisite extension point for non-MVStore main paths and system catalog ownership.
- `JdbcUrlPrefixProvider`: a Driver-level URL prefix extension point for mapping custom URLs such as `jdbc:vendor:*` to `jdbc:h2:*`.
- `TransactionEventProvider`: a transaction lifecycle extension point for commit / rollback events.
- Automatic discovery: H2 reads `META-INF/services/org.h2.api.H2Plugin` from the classpath.

Plugin loading no longer depends on JDBC URL parameters. Once a plugin jar is on the application classpath and publishes the `ServiceLoader` file, it is discovered both by the Driver early path and by the database-open path.

The current plugin model is static at database-open time. After plugins are loaded, the registry is fixed; hot loading, unloading, online replacement, and multiple active versions of the same plugin are not supported.

## API Stability Commitment

The current plugin API has three layers:

| Layer | Scope | Commitment |
| --- | --- | --- |
| Stable SPI | `H2Plugin`, `PluginProvider`, `TableEngineProvider`, `StorageEngineProvider`, `SystemCatalogProvider`, `JdbcUrlPrefixProvider`, `TransactionEventProvider`, `StorageMaintenance`, capability strings | Kept source-compatible as plugin entry points. New behavior should normally be added through default methods or new capabilities. |
| Managed migration API | `TableEngineContext`, `StorageEngineContext`, `CreateTableData`, `Table` / `Index` related internal types | Usable during plugin migration periods, but contract tests must run before upgrading H2 minor versions. Long-term binary compatibility is not promised. |
| Internal implementation | parser, optimizer, JDBC server, MVStore physical structures, deep `Database` lifecycle | Not exposed as plugin APIs. Plugins must not depend on call order or field layout here. |

Non-MVStore storage engines can currently be registered, diagnosed, and managed through `StorageEngineProvider` capabilities, and `SystemCatalogProvider` is now part of the provider whitelist and diagnostics as the prerequisite SPI for system catalog ownership. Full replacement of the H2 main storage path still requires system catalog tables, LOBs, transaction logs, and temporary results to be separated from `Store`. Therefore the first stable commitment remains: production main-path storage engines must be MVStore-backed. A non-MVStore main path should enter implementation separately after system catalog table contracts are defined.

## Plugin Descriptor

A plugin class must implement `H2Plugin` and provide a stable plugin id, version, display name, and provider list. The plugin id is the primary key for dependency resolution and diagnostics, so it should not be changed casually after publication.

Version ranges support three forms:

- `*`: compatible with the current H2 version.
- Exact version, such as `2.2`.
- Ranges, such as `[2.2,3.0)` or `[2.2,2.3]`.

Dependencies are declared with `PluginDependency`. When multiple automatically discovered plugins are loaded, H2 registers them in dependency order. Missing dependencies or dependency cycles fail database startup.

Minimal plugin descriptor example:

```java
package com.acme;

import java.util.Arrays;
import org.h2.api.H2Plugin;
import org.h2.api.PluginProvider;

public final class AcmePlugin implements H2Plugin {
    public String getId() {
        return "com.acme.plugin";
    }

    public String getVersion() {
        return "1.0.0";
    }

    public String getDisplayName() {
        return "Acme Plugin";
    }

    public Iterable<? extends PluginProvider> getProviders() {
        return Arrays.asList(new AcmeTableProvider());
    }

    public String getH2VersionRange() {
        return "[2.2,3.0)";
    }
}
```

For least-privilege plugin loading, implement `getAllowedProviderTypes()` to restrict this plugin to an explicit list of provider types. If the list is empty, the global `H2` provider allowlist applies.

```java
package com.acme;

import java.util.Collections;
import org.h2.api.H2Plugin;
import org.h2.api.TableEngineProvider;

public final class LeastPrivilegePlugin implements H2Plugin {
    public String getId() {
        return "com.acme.privileged";
    }

    public String getVersion() {
        return "1.0.0";
    }

    public String getDisplayName() {
        return "Least Privilege Plugin";
    }

    public Iterable<String> getAllowedProviderTypes() {
        return Collections.singletonList(TableEngineProvider.TYPE);
    }
}
```

Plugin classes must provide a public no-argument constructor. Plugin id, version, provider type, and provider id must not be empty. The provider list must not be null and must contain at least one provider.

## Provider Constraints

External plugins can currently register only table, storage, system catalog, JDBC URL prefix, and transaction event providers. SQL parser, function, auth, optimizer, wire protocol, and other core extension points are not in the current plan.

Provider ids must be unique within the same provider type. Built-in providers cannot be overridden by external plugins.

Use a reverse-DNS or clear product prefix for plugin ids, such as `com.acme.plugin`. Keep provider ids short and stable, such as `acme_table` and `acme_storage`. Built-in provider ids such as `mvstore` and `mvstore_secondary` are reserved names.

`PluginProvider.supports(String capability)` must be a side-effect-free query. It should not open files, start threads, or mutate database state. Known capabilities in the current implementation are:

| Capability | Meaning |
| --- | --- |
| `table.create` | Provider can create tables |
| `system.catalog` | Provider can own the system catalog |
| `transaction.events` | Provider can observe transaction commit / rollback events |
| `storage.persistent` | Storage supports persistent databases |
| `storage.transactional` | Storage supports transactions |
| `storage.mvcc` | Storage supports MVCC |
| `storage.backup` | Storage supports consistent backup |
| `storage.compact.closed` | Storage supports closed-database compact |
| `storage.compact.online.maintenance` | Storage supports maintenance-mode online compact |
| `storage.vacuum.online` | Storage supports online space reclamation |
| `storage.publish.crashSafe` | Storage supports crash-safe metadata publish |
| `storage.truncate.safe` | Storage supports safe physical truncation |

New capabilities should use stable strings and should normally stay under the `table.*` or `storage.*` namespaces.

## Driver-Level Plugin Loading

`JdbcUrlPrefixProvider` registers JDBC URL prefixes before a database is opened, for example by mapping a custom `jdbc:vendor:*` URL to a regular `jdbc:h2:*` URL. Because `Driver.acceptsURL()` and `Driver.connect()` run before `Database` is created, these providers cannot rely on plugin-loading parameters inside the database URL.

Driver-level plugins are discovered only through `ServiceLoader`. If the same plugin also provides table or storage providers, Driver URL resolution and Database provider registration use the same classpath service file.

`JdbcUrlPrefixProvider.toH2Url()` must return a URL that starts with `jdbc:h2:`; other prefixes fail the connection. The mapped URL then uses the existing H2 connection, authorization, storage, and table provider flow.

## Transaction Event Provider Constraints

A transaction event provider implements `TransactionEventProvider`; its provider type is `transaction`. H2 invokes callbacks around real transaction commit / rollback boundaries:

- `beforeCommit(TransactionContext)`
- `afterCommit(TransactionContext)`
- `beforeRollback(TransactionContext)`
- `afterRollback(TransactionContext)`

`TransactionContext` exposes the current `Database`, session id, whether the boundary is DDL-related, current auto-commit state, and whether a transaction existed when the event was created. It does not expose the underlying `Transaction` object and does not open parser, optimizer, session lifecycle, or wire protocol internals.

Providers should follow these rules:

- Callbacks must be fast, reentrant, and avoid holding external locks for a long time.
- A `beforeCommit()` failure blocks the commit; external pre-commit validation must keep failure paths retryable.
- An `afterCommit()` failure does not roll back the already committed H2 transaction; it only returns provider diagnostics to the caller.
- Rollback callbacks should not perform business writes; failures are reported with provider/event/session diagnostics.

## Table Provider Constraints

A table engine provider implements `TableEngineProvider`. The SQL `ENGINE` name maps to the provider id:

```sql
CREATE TABLE TEST(ID INT) ENGINE "acme_table";
CREATE TABLE TEST(ID INT) ENGINE "acme_table" WITH "param1", "param2";
```

When `ENGINE "provider_id"` is intended to route to an H2 table provider, validate it under `MODE=REGULAR`. The MySQL-compatible table option `ENGINE` is handled with MySQL semantics and does not represent H2 plugin table provider routing.

Minimal table provider example:

```java
package com.acme;

import org.h2.api.PluginCapability;
import org.h2.api.TableEngineContext;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.mvstore.db.MVStoreBackedStorageEngine;
import org.h2.table.Table;

public final class AcmeTableProvider implements TableEngineProvider {
    public String getType() {
        return TYPE;
    }

    public String getId() {
        return "acme_table";
    }

    public boolean supports(String capability) {
        return PluginCapability.TABLE_CREATE.equals(capability);
    }

    public Table createTable(CreateTableData data, TableEngineContext context) {
        MVStoreBackedStorageEngine storage = (MVStoreBackedStorageEngine) context.getStorageEngine();
        return storage.getStore().createTable(data);
    }
}
```

`TableEngineContext` provides the current `Database`, target `Schema`, current `StorageEngine`, storage engine id, trace, `WITH` parameters, persistence state, and read-only state. If a table does not specify `WITH` parameters, schema default params are used.

`TableProviderSupport` provides thin helper methods for table providers:

- `requireWritable(...)`: rejects external table resource creation or mutation in read-only databases and emits provider/table/params diagnostics.
- `requireStorageEngine(...)`: checks the current storage engine type so providers do not need unchecked casts without diagnostics.
- `createTableException(...)`: wraps `createTable()` failures, preserves the original cause, and includes provider id, table name, and parameter summary in the message.

The old `org.h2.api.TableEngine` class-name path remains available for compatibility. New extensions should prefer `TableEngineProvider`.

### Custom Table / Index Stability During Migration

The public `TableEngineProvider` SPI is stable only up to the boundary where it receives `CreateTableData` and returns a `Table`. Custom `Table` / `Index` implementations still depend on H2 internal objects. These objects are managed migration APIs for now; they are not a long-term binary compatibility promise.

| Type | Migration status | Guidance |
| --- | --- | --- |
| `TableEngineProvider` | SPI | Use it as the plugin entry point; keep provider ids and capabilities stable. |
| `TableEngineContext` | SPI | Prefer it for schema, storage, trace, parameters, read-only, and persistence state. |
| `CreateTableData` | Managed internal input | Read table creation metadata from it; do not retain it as a long-lived reference. |
| `Table` / `TableBase` | Managed internal API | Suitable for custom table provider migration prototypes; run contract tests before upgrading H2 minor versions. |
| `Index` / `IndexType` / `IndexColumn` | Managed internal API | Custom indexes must cover scan, cost, row count, drop, and rebuild semantics. |
| `Row` / `SearchRow` / `Value` | Managed internal API | Suitable for row encoding boundaries; do not assume stable internal layout or object reuse. |
| `SessionLocal` | High-risk internal API | Use only when transactions, locks, or permissions require it; do not expose it through plugin public APIs. |
| parser / optimizer / JDBC server internals | Out of scope | These extension points are not open in this pluginization round. |

Custom table providers should follow these rules:

- `createTable()` failures must release external resources already opened and leave the same DDL retryable.
- `supports()` must be side-effect-free; diagnostic tables may call it repeatedly.
- Providers should not cache request-scoped objects such as `SessionLocal`, `CreateTableData`, or `TableEngineContext`.
- Read-only databases must not perform actions that mutate external storage or index metadata.
- If more context is needed, prefer proposing a `TableEngineContext` field instead of reaching into deep `Database` internals.

H2-side table SPI contract tests live in `h2/src/test-plugin/org/h2/test/plugin/TableSpiContractTest.java`. Migration-period plugins should align with its registration, table creation, parameter propagation, schema context, basic DML, failure diagnostics, read-only gate, and diagnostics cases.

### Provider Prototype Feedback and Diagnostics Rules

If a custom table provider prototype shows that `TableEngineContext` does not expose enough information, handle it in this order:

1. First add contract tests for read-only, persistence, schema, trace, storage, and parameter context.
2. Add a public method to `TableEngineContext` only when multiple providers need the same information.
3. Do not expose parser, optimizer, session lifecycle, or JDBC server internals through `Database`.
4. `createTable()` failures should preserve the original cause and include provider id, table name, and a short parameter summary in the message.

## Storage Engine Constraints

## System Catalog Provider Constraints

`SystemCatalogProvider` is the prerequisite SPI for non-MVStore main paths. Its provider type is `system_catalog`. It is intended to move ownership of system catalog tables, LOBs, transaction logs, and temporary results away from the MVStore `Store` dependency in the `Database` main path.

The current version stabilizes these boundaries:

- External plugins may register a `system_catalog` provider.
- Diagnostic tables expose the provider and the `system.catalog` capability.
- When a database opens, the selected storage provider id must have a matching `system_catalog` provider id; otherwise open fails.
- Providers can use `validate(SystemCatalogContext)` to declare whether they can serve the current database context.

The current version does not yet promise:

- Automatic ownership of H2 system table creation.
- Replacement of `Database.getStore()`.
- Running arbitrary non-MVStore storage engines as the production main path.

Therefore the next non-MVStore main-path implementation must first add contract tests for system tables, LOBs, transaction logs, and temporary results before removing the hard dependency on the MVStore `Store` from `Database`.

The storage engine id is persisted in a sidecar metadata file next to the database. When opening a database, the requested `STORAGE_ENGINE` must match the persisted id.

Missing storage providers fail by default. Read-only downgrade is allowed only when all of these conditions are met:

- The database is opened read-only.
- `MISSING_STORAGE_READ_ONLY_DOWNGRADE=TRUE` is set explicitly.
- The persisted storage id matches the requested id.

The downgrade path must not execute writes, compact, vacuum, or any other operation that changes file state.

Minimal storage provider example:

```java
package com.acme;

import org.h2.api.PluginCapability;
import org.h2.api.StorageEngine;
import org.h2.api.StorageEngineContext;
import org.h2.api.StorageEngineProvider;

public final class AcmeStorageProvider implements StorageEngineProvider {
    public String getType() {
        return TYPE;
    }

    public String getId() {
        return "acme_storage";
    }

    public boolean supports(String capability) {
        return PluginCapability.STORAGE_PERSISTENT.equals(capability);
    }

    public StorageEngine open(StorageEngineContext context) {
        return new AcmeStorageEngine(context);
    }
}
```

`StorageEngineContext` provides the current `Database`, database path, file password key, database settings, read-only state, and trace. If `StorageEngine.open()` fails, database open fails; implementations should release files, threads, and other resources that were already opened on the failure path.

`StorageEngine.close()` defaults to `flush()` followed by `closeImmediately()`. If a storage engine needs a finer-grained shutdown order, override `close()`. `StorageMaintenance` compact / vacuum methods must first pass a capability gate and must not perform maintenance when the capability is unsupported.

The current main database path still requires storage engines to be MVStore-backed. The second built-in provider, `mvstore_secondary`, reuses the MVStore physical implementation and validates storage provider selection, persisted ids, and table creation paths.

The sidecar metadata file name is the database path plus the `.storage` suffix. Backup, restore, rename, and manual database migration flows should treat this file as part of the same database metadata.

## Loading Modes

Plugins are discovered automatically through `ServiceLoader`. Add this file to the plugin jar:

```text
META-INF/services/org.h2.api.H2Plugin
```

The file contains the plugin class name:

```text
com.acme.AcmePlugin
```

After the plugin jar is on the application classpath, no JDBC URL plugin-loading parameter is needed. URL settings may still select already discovered providers with `STORAGE_ENGINE`, `DEFAULT_TABLE_ENGINE`, or SQL `ENGINE`.

## Diagnostics

Plugin diagnostics are available through these INFORMATION_SCHEMA tables:

- `INFORMATION_SCHEMA.PLUGINS`
- `INFORMATION_SCHEMA.PLUGIN_PROVIDERS`
- `INFORMATION_SCHEMA.PLUGIN_CAPABILITIES`

These tables are read-only and expose plugin id, provider type/id, source, and capability information.

Plugin load failures, version mismatches, missing dependencies, provider conflicts, and forbidden provider types fail during database open. Error messages should include plugin id, provider type/id, or class name to make configuration issues diagnosable.

## Testing

Prefer JUnit tests for plugin logic and run:

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat runPluginArchitectureCheck
```

For MVStore recovery or space reclamation changes, also run:

```powershell
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
```
