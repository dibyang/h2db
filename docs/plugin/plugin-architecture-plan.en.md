# Plugin Architecture Implementation Plan

This document tracks the next implementation tasks for H2 pluginization. The current goal is to stabilize the existing plugin skeleton into an infrastructure that can keep evolving, then gradually make storage engines and table engines extensible through the same plugin mechanism. Built-in capabilities should continue to use the same provider and registry path.

## Scope

Included in this round:

| Area | Content |
| --- | --- |
| Plugin entrypoints | `H2Plugin`, `PluginProvider`, default `ServiceLoader` automatic discovery |
| Registry | Provider type/id uniqueness, source, capabilities, diagnostic snapshots |
| Storage engines | `StorageEngineProvider`, `StorageEngine`, persisted storage engine id, read-only downgrade policy |
| Table engines | `TableEngineProvider`, `TableEngineContext`, built-in MVStore table engine provider path |
| Transaction events | `TransactionEventProvider`, `TransactionContext`, commit / rollback event diagnostics |
| Database lifecycle | `DatabaseLifecycleProvider`, `DatabaseLifecycleContext`, close event diagnostics |
| Test foundation | JUnit `pluginTest`, legacy smoke, full `TestAll ci` phase rerun rules |
| External docs | Chinese documentation and English copy kept in sync |

Not included in this round:

| Capability | Later phase |
| --- | --- |
| Hot loading, unloading, or online plugin replacement | Design after the plugin lifecycle is stable |
| Plugin package manifest, signing, and permission sandbox | Design during external plugin distribution |
| Multiple plugin versions and version resolution | Design during dependency/version semantic expansion |
| New extension points beyond storage/table/system catalog/JDBC URL prefix/transaction event | Parser, function, auth, optimizer, wire protocol, and similar extension points are not in the current plan |
| Automated performance baselines and large-resource slow tests | Add during the slow-test foundation phase |

## Test Gates

Every pluginization phase must at least run:

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

For production-code changes, storage/table-engine behavior changes, or higher confidence before commit, run:

```powershell
.\gradlew.bat clean test check build runH2LegacySmoke
```

For phase completion or higher-risk changes, run full acceptance:

```powershell
.\gradlew.bat runH2TestAllCi
```

If full acceptance fails in a network phase with a connection timeout, rerun the matching `runH2TestAllCiPhaseReport` phase and record the result.

## Phases

| Phase | Status | Goal | Main tasks | Verification |
| --- | --- | --- | --- | --- |
| P1 | Done | Stabilize the implementation plan | Create Chinese and English phase plans, bind test gates, list deferred capabilities | `runPluginArchitectureCheck` |
| P2 | Done | Fill SPI contract boundaries | Check null/empty ids and versions, empty provider sets, and source contracts for `H2Plugin` and providers; add JUnit coverage | `runPluginArchitectureCheck`, daily gate |
| P3 | Done | Harden plugin loading diagnostics | Cover explicit class loading, ServiceLoader switch, version ranges, invalid descriptors, missing dependencies, dependency cycles, and forbidden provider types | `runPluginArchitectureCheck`, daily gate |
| P4 | Done | Consolidate built-in plugin paths | Ensure MVStore storage/table engines are registered and resolved through built-in providers; default table creation does not use the legacy `TableEngine` cache | `runPluginArchitectureCheck`, `runH2LegacySmoke` |
| P5 | Done | Complete table-engine pluginization | Cover external `TableEngineProvider` SQL table creation, schema default params, and built-in/legacy path compatibility | `runPluginArchitectureCheck`, `runH2LegacySmoke` |
| P6 | Done | Complete storage-engine pluginization | Cover storage id persistence, requested/persisted mismatch, secondary/default mismatch protection, missing provider, read-only downgrade, and maintenance capabilities | `runPluginArchitectureCheck` |
| P7 | Done | Complete observability | Stabilize built-in and configured plugin output in `INFORMATION_SCHEMA.PLUGINS`, `PLUGIN_PROVIDERS`, and `PLUGIN_CAPABILITIES` | `runPluginArchitectureCheck` |
| P8 | Done | Acceptance and closeout | Ran the daily gate; this round focused on plugin SPI, loading, table/storage provider tests, and diagnostic coverage, so full `TestAll ci` was not rerun | `clean test check build runH2LegacySmoke` |
| P10 | Done | Table / Index migration support layer | Add `TableProviderSupport` for read-only gates, storage type checks, and provider failure diagnostics; update contract tests and docs | `runPluginArchitectureCheck`, `runH2LegacySmoke` |
| P11 | Done | Transaction event extension | Add `TransactionEventProvider` / `TransactionContext`, wire commit / rollback boundary events, and cover diagnostics and failures | `runPluginArchitectureCheck`, `runH2LegacySmoke` |
| P13 | Done | Database lifecycle extension | Add `DatabaseLifecycleProvider` / `DatabaseLifecycleContext`, wire database close callbacks, and cover diagnostics and failure reporting | `runPluginArchitectureCheck` |
| P14 | Done | Plugin version coexistence and dependency resolution | Allow distinct versions of the same plugin id to coexist when provider ids do not conflict; resolve dependency versions with exact, wildcard, and interval matching | `runPluginArchitectureCheck` |
| P15 | Done | Multi-version diagnostics | Report each plugin id/version pair in `INFORMATION_SCHEMA.PLUGINS` and include `PLUGIN_VERSION` in capability rows | `runPluginArchitectureCheck` |

## Current Closeout Status

P1-P8, P10, P11, P13, P14, and P15 are complete. The plugin foundation currently provides:

| Capability | Status |
| --- | --- |
| Plugin registry | Provider registration, uniqueness checks, capability diagnostics, invalid descriptor rejection |
| Plugin loading | Default `ServiceLoader` automatic discovery, version ranges, dependency ordering, missing dependency diagnostics, and dependency-cycle diagnostics |
| Built-in plugins | MVStore storage/table providers registered and resolved through the built-in registry path |
| Table-engine extension | External `TableEngineProvider` can create tables through SQL engine ids and receive table/schema params |
| Table provider support layer | `TableProviderSupport` covers read-only gates, storage type checks, and provider failure wrapping |
| Storage-engine extension | Storage provider resolution, storage id persistence, mismatch rejection, read-only downgrade, and maintenance capability boundaries |
| Transaction event extension | Transaction providers can observe commit / rollback boundary events with provider/event/session diagnostics on failure |
| Database lifecycle extension | Lifecycle providers can observe database close events without URL-level `DATABASE_EVENT_LISTENER` injection |
| Version resolution | Plugin dependencies match exact versions, `*`, and intervals; multiple versions of the same plugin id may coexist when provider ids remain distinct |
| Observability | `INFORMATION_SCHEMA.PLUGINS`, `PLUGIN_PROVIDERS`, and `PLUGIN_CAPABILITIES` cover built-in, configured external, and multi-version plugins |
| Test gates | `runPluginArchitectureCheck` and the daily gate have passed |

## Working Rules

1. Commit locally after every completed phase, with the commit message describing the phase goal.
2. New production code must add or update JUnit tests in the same phase; legacy tests only supplement compatibility coverage.
3. External documentation changes must update the English copy at the same time.
4. Plugin capabilities not implemented in the current phase must be documented as deferred scope or later phases.
5. Do not change disk format, SQL compatibility behavior, or default configuration unless the phase document states the migration and rollback path.
