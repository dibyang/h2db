# Plugin Usage Guide

This document is for users and developers who want to extend H2 storage engines or table engines. The current plugin mechanism uses static loading: plugins are loaded when a database is opened. Hot loading, unloading, and online replacement are not supported yet.

## Settings

| Setting | Default | Description |
| --- | --- | --- |
| `PLUGIN_CLASSES` | empty | Comma-separated `org.h2.api.H2Plugin` implementation class names loaded explicitly during database open |
| `PLUGIN_PATHS` | empty | Comma-separated jar or directory paths used to create an isolated classloader for explicit plugin classes |
| `PLUGIN_SERVICE_LOADER` | `FALSE` | Whether to discover `H2Plugin` implementations through `ServiceLoader`; disabled by default |
| `STORAGE_ENGINE` | `mvstore` | Database-level storage engine provider id |
| `MISSING_STORAGE_READ_ONLY_DOWNGRADE` | `FALSE` | Whether a missing storage provider may be opened through a read-only downgrade path; the database must also be opened read-only |

Examples:

```sql
jdbc:h2:./data/demo;PLUGIN_CLASSES=com.acme.AcmePlugin
jdbc:h2:./data/demo;PLUGIN_CLASSES=com.acme.AcmePlugin;PLUGIN_PATHS=plugins/acme.jar
jdbc:h2:./data/demo;PLUGIN_SERVICE_LOADER=TRUE
jdbc:h2:./data/demo;STORAGE_ENGINE=acme_storage
```

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
| Plugin manifest and signing | Discovery currently uses class names or `ServiceLoader` |
| Multiple plugin versions at the same time | Dependencies currently check plugin ids; complex version resolution is deferred |
| Parser/function/auth extension points | The current provider whitelist only allows storage and table providers |
| Dedicated permission sandbox | Current boundaries are provider type whitelist, classloader usage, and configuration diagnostics |
