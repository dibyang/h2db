# Plugin Release Readiness

This document records the release readiness state for the current H2 plugin foundation.
It is a checklist for deciding whether the pluginization work can be published as a stable baseline.

## Release Scope

The current release is ready to publish the static plugin foundation:

| Area | Release commitment |
| --- | --- |
| Discovery | Plugins are discovered automatically through `ServiceLoader`; JDBC URL plugin class loading is not supported. |
| Registry | Plugin descriptors, providers, capabilities, dependencies, source, and diagnostics are registered through one registry path. |
| Versioning | Multiple versions of the same plugin id may coexist when provider ids do not conflict; dependencies support exact, `*`, and interval ranges. |
| Table engines | External `TableEngineProvider` implementations can create tables through SQL `ENGINE` ids and receive table/schema params. |
| Storage engines | Storage providers can be resolved, persisted, diagnosed, downgraded read-only for rescue, and gated through maintenance capabilities. Production main-path storage engines must remain MVStore-backed. |
| System catalog | `SystemCatalogProvider` is available as the prerequisite ownership boundary for future non-MVStore main paths. |
| JDBC URL prefixes | `JdbcUrlPrefixProvider` can map custom Driver-level prefixes such as `jdbc:vendor:*` to H2 URLs after automatic discovery. |
| Transaction events | `TransactionEventProvider` can observe commit / rollback boundary events with failure diagnostics. |
| Database lifecycle | `DatabaseLifecycleProvider` can observe database close events without URL listener injection. |
| Observability | `INFORMATION_SCHEMA.PLUGINS`, `PLUGIN_PROVIDERS`, `PLUGIN_CAPABILITIES`, and `PLUGIN_DEPENDENCIES` expose release diagnostics. |

## Explicit Non-Goals

These are not release blockers because they are intentionally outside this version:

| Capability | Release position |
| --- | --- |
| Hot loading, unloading, or online replacement | Deferred. Plugins are loaded only during Driver prefix resolution and database open. |
| Plugin manifest, signing, and packaging policy | Deferred to external distribution design. The supported discovery contract is `META-INF/services/org.h2.api.H2Plugin`. |
| Dedicated sandbox | Deferred. Current boundaries are provider type whitelist, per-plugin allowed provider types, and diagnostic failure on forbidden types. |
| Parser, function, auth, optimizer, and wire protocol extension points | Not in the current plugin plan. |
| Non-MVStore production main path | Deferred until system catalog tables, LOBs, transaction logs, and temporary results are separated from `Store`. |
| Automated performance baselines and large-resource slow tests | Deferred to slow-test infrastructure. |

## Required Gates

Before publishing this baseline, run:

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runH2LegacySmoke
```

For a full project release, the owner may additionally run the full H2 CI gate, but it is not required for every pluginization-only checkpoint in this thread.

## Current Readiness

The pluginization baseline is release-ready when:

- The two required gates pass.
- Working tree is clean after the release-readiness commit.
- Deferred capabilities remain documented as non-goals.
- No plugin provider type is added without updating the whitelist, diagnostics, tests, and both Chinese and English documentation.
