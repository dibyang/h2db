# Plugin Usage Guide

This document is for users and developers who want to extend H2 storage engines or table engines. The current plugin mechanism uses static loading: plugins are discovered through `ServiceLoader` and loaded when the Driver resolves custom JDBC URL prefixes and when a database is opened. Hot loading, unloading, and online replacement are not supported yet.

## Automatic Discovery

Plugin jars need to provide the standard service file on the classpath:

```text
META-INF/services/org.h2.api.H2Plugin
```

The file contains plugin implementation class names, one per line:

```text
com.acme.AcmePlugin
```

H2 no longer loads plugin classes from the JDBC URL. URL settings only select already discovered providers, for example:

```sql
jdbc:h2:./data/demo;STORAGE_ENGINE=acme_storage
```

| Setting | Default | Description |
| --- | --- | --- |
| `STORAGE_ENGINE` | `mvstore` | Database-level storage engine provider id |
| `MISSING_STORAGE_READ_ONLY_DOWNGRADE` | `FALSE` | Whether a missing storage provider may be opened through a read-only downgrade path; the database must also be opened read-only |

## Minimal Plugin

A plugin class implements `H2Plugin` and returns one or more providers. A single plugin may provide both storage providers and table providers.

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
        return "1";
    }

    public String getDisplayName() {
        return "Acme Plugin";
    }

    public Iterable<? extends PluginProvider> getProviders() {
        return Arrays.asList(new AcmeTableProvider());
    }
}
```

Plugin id, version, provider type, and provider id must not be empty. A plugin must provide at least one provider. Duplicate provider ids, forbidden provider types, missing dependencies, and dependency cycles fail during database open with diagnostic messages.

## Table Engine Plugins

A table engine provider implements `TableEngineProvider`. The SQL `ENGINE` name maps to the provider id.

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

Usage examples:

```sql
CREATE TABLE TEST(ID INT) ENGINE "acme_table";
CREATE TABLE TEST(ID INT) ENGINE "acme_table" WITH "param1", "param2";
CREATE SCHEMA S WITH "schema_param";
CREATE TABLE S.TEST(ID INT) ENGINE "acme_table";
```

`WITH` parameters are available through `TableEngineContext.getTableEngineParams()`. If a table does not specify parameters, schema default params are used.

The old `org.h2.api.TableEngine` class-name path remains available for compatibility. New plugins should prefer `TableEngineProvider`.

## Storage Engine Plugins

A storage engine provider implements `StorageEngineProvider`. The database URL setting `STORAGE_ENGINE` maps to the provider id.

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

After a database is created, the storage engine id is persisted beside the database metadata. Later opens of the same database must request a matching `STORAGE_ENGINE`; otherwise the open is rejected to avoid reading data through the wrong storage engine.

Read-only downgrade is only for explicitly configured compatibility or rescue scenarios:

```sql
jdbc:h2:./data/demo;ACCESS_MODE_DATA=r;STORAGE_ENGINE=missing;MISSING_STORAGE_READ_ONLY_DOWNGRADE=TRUE
```

## Database Lifecycle Plugins

A database lifecycle provider implements `DatabaseLifecycleProvider`. It receives database close callbacks without using URL-level `DATABASE_EVENT_LISTENER` settings.

```java
package com.acme;

import org.h2.api.DatabaseLifecycleContext;
import org.h2.api.DatabaseLifecycleProvider;
import org.h2.api.PluginCapability;

public final class AcmeLifecycleProvider implements DatabaseLifecycleProvider {
    public String getType() {
        return TYPE;
    }

    public String getId() {
        return "acme_lifecycle";
    }

    public boolean supports(String capability) {
        return PluginCapability.DATABASE_LIFECYCLE.equals(capability);
    }

    public void beforeClose(DatabaseLifecycleContext context) {
        // release plugin-owned resources that must close before storage files
    }

    public void afterClose(DatabaseLifecycleContext context) {
        // publish diagnostics after storage resources are closed
    }
}
```

Callback failures are reported after H2 finishes the close path, with provider id, lifecycle event, database name, storage engine id, and cause in the diagnostic message.

Existing `DATABASE_EVENT_LISTENER` usage remains supported for application-level listener compatibility. New storage plugins should use `DatabaseLifecycleProvider` for close handling instead of injecting listener settings into rewritten JDBC URLs.

## Diagnostic Queries

Registered plugins and providers can be inspected through information schema:

```sql
SELECT * FROM INFORMATION_SCHEMA.PLUGINS;
SELECT * FROM INFORMATION_SCHEMA.PLUGIN_PROVIDERS;
SELECT * FROM INFORMATION_SCHEMA.PLUGIN_CAPABILITIES;
```

These views expose plugin id, version, source, provider type/id, whether the plugin is built in, and provider capabilities.

## Current Boundaries

The current phase does not support:

| Capability | Notes |
| --- | --- |
| Hot loading, unloading, or online replacement | Plugins are loaded only during database open |
| Plugin manifest and signing | Discovery currently uses `ServiceLoader` |
| Multiple plugin versions at the same time | Dependencies currently check plugin ids; complex version resolution is deferred |
| Parser/function/auth/optimizer/wire protocol extension points | Not in the current plan; the provider whitelist only allows table, storage, system catalog, JDBC URL prefix, transaction event, and database lifecycle providers |
| Dedicated permission sandbox | Current boundaries include the global provider type whitelist and optional per-plugin allowlists (`H2Plugin#getAllowedProviderTypes`) |
