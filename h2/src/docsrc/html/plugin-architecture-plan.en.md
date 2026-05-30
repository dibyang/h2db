# Plugin Architecture Implementation Plan

This document tracks the next implementation tasks for H2 pluginization. The current goal is to stabilize the existing plugin skeleton into an infrastructure that can keep evolving, then gradually make storage engines and table engines extensible through the same plugin mechanism. Built-in capabilities should continue to use the same provider and registry path.

## Scope

Included in this round:

| Area | Content |
| --- | --- |
| Plugin entrypoints | `H2Plugin`, `PluginProvider`, explicit class loading, `PLUGIN_PATHS`, optional `ServiceLoader` |
| Registry | Provider type/id uniqueness, source, capabilities, diagnostic snapshots |
| Storage engines | `StorageEngineProvider`, `StorageEngine`, persisted storage engine id, read-only downgrade policy |
| Table engines | `TableEngineProvider`, `TableEngineContext`, built-in MVStore table engine provider path |
| Test foundation | JUnit `pluginTest`, legacy smoke, full `TestAll ci` phase rerun rules |
| External docs | Chinese documentation and English copy kept in sync |

Not included in this round:

| Capability | Later phase |
| --- | --- |
| Hot loading, unloading, or online plugin replacement | Design after the plugin lifecycle is stable |
| Plugin package manifest, signing, and permission sandbox | Design during external plugin distribution |
| Multiple plugin versions and version resolution | Design during dependency/version semantic expansion |
| New extension points beyond storage/table | Review parser, function, auth, and similar extension points separately |
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
| P6 | Not started | Complete storage-engine pluginization | Cover storage id persistence, requested/persisted mismatch, missing provider, read-only downgrade, and maintenance capabilities | `runPluginArchitectureCheck`, storage-focused checks |
| P7 | Not started | Complete observability | Stabilize `INFORMATION_SCHEMA.PLUGINS`, `PLUGIN_PROVIDERS`, and `PLUGIN_CAPABILITIES`; update docs | `runPluginArchitectureCheck` |
| P8 | Not started | Acceptance and closeout | Run the daily gate, decide whether full `TestAll ci` is needed by risk, update status, and commit | Daily gate, full acceptance when needed |

## Working Rules

1. Commit locally after every completed phase, with the commit message describing the phase goal.
2. New production code must add or update JUnit tests in the same phase; legacy tests only supplement compatibility coverage.
3. External documentation changes must update the English copy at the same time.
4. Plugin capabilities not implemented in the current phase must be documented as deferred scope or later phases.
5. Do not change disk format, SQL compatibility behavior, or default configuration unless the phase document states the migration and rollback path.
