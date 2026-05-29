# H2 Plugin Developer Guide

This is the English companion of [h2db-plugin-developer-guide.md](h2db-plugin-developer-guide.md).

## Current Scope

H2 plugins expose their plugin descriptor through `org.h2.api.H2Plugin` and extend concrete capabilities through providers. The current implementation supports:

- `TableEngineProvider`: extends the table engine creation entry point.
- `StorageEngineProvider`: extends the database-level storage engine.
- `StorageMaintenance`: exposes maintenance capabilities such as compact and vacuum.
- Explicit class loading: `PLUGIN_CLASSES=a.b.Plugin`.
- Isolated path loading: `PLUGIN_PATHS=/path/to/plugin.jar`.
- Optional discovery: `PLUGIN_SERVICE_LOADER=TRUE` discovers `META-INF/services/org.h2.api.H2Plugin`.

ServiceLoader plugins on the classpath are not enabled by default. This keeps provider conflicts and startup behavior explicit.

## Plugin Descriptor

A plugin class must implement `H2Plugin` and provide a stable plugin id, version, display name, and provider list. The plugin id is the primary key for dependency resolution and diagnostics, so it should not be changed casually after publication.

Version ranges support three forms:

- `*`: compatible with the current H2 version.
- Exact version, such as `2.2`.
- Ranges, such as `[2.2,3.0)` or `[2.2,2.3]`.

Dependencies are declared with `PluginDependency`. When multiple explicit plugins are loaded, H2 registers them in dependency order. Missing dependencies or dependency cycles fail database startup.

## Provider Constraints

External plugins can currently register only table and storage providers. SQL parser, optimizer, wire protocol, and other core extension points are not open yet.

Provider ids must be unique within the same provider type. Built-in providers cannot be overridden by external plugins.

## Storage Engine Constraints

The storage engine id is persisted in a sidecar metadata file next to the database. When opening a database, the requested `STORAGE_ENGINE` must match the persisted id.

Missing storage providers fail by default. Read-only downgrade is allowed only when all of these conditions are met:

- The database is opened read-only.
- `MISSING_STORAGE_READ_ONLY_DOWNGRADE=TRUE` is set explicitly.
- The persisted storage id matches the requested id.

The downgrade path must not execute writes, compact, vacuum, or any other operation that changes file state.

## Diagnostics

Plugin diagnostics are available through these INFORMATION_SCHEMA tables:

- `INFORMATION_SCHEMA.PLUGINS`
- `INFORMATION_SCHEMA.PLUGIN_PROVIDERS`
- `INFORMATION_SCHEMA.PLUGIN_CAPABILITIES`

These tables are read-only and expose plugin id, provider type/id, source, and capability information.

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
