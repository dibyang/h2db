# h2db 2.3.0

[中文](RELEASE_NOTES_TEMPLATE.md)

## Summary

This 2.3.0 release is the first h2db baseline with complete public release materials. It focuses on open-source publishing readiness, the experimental MVStore space reclamation maintenance API, the LongRun stress-test distribution, and the static H2 plugin foundation.

## Compatibility

This release keeps the H2 embedded and server database model. Maven coordinates are `net.xdob.h2db:h2db:2.3.0`. Pluginization is published as a static loading model; users do not load plugin classes through JDBC URLs. Existing regular H2 URLs and the legacy table engine class-name path remain compatible.

## Changes

* Added the static plugin foundation: `ServiceLoader` automatic discovery, unified provider registry, and table/storage/system catalog/JDBC URL prefix/transaction event/database lifecycle providers.
* Added plugin version coexistence, dependency resolution, and diagnostic views: `INFORMATION_SCHEMA.PLUGINS`, `PLUGIN_PROVIDERS`, `PLUGIN_CAPABILITIES`, and `PLUGIN_DEPENDENCIES`.
* Hardened plugin permission boundaries and diagnostics. Forbidden provider types, plugin-level allowed provider type violations, ServiceLoader discovery failures, invalid descriptors, missing dependencies, and dependency cycles now fail with diagnostics.
* Added documentation, status introspection, entry-point introspection, and diagnostic event listener support for the experimental MVStore space reclamation maintenance API.
* Added the standalone LongRun stress-test distribution with smoke, performance, crash/recovery, fault-injection, nightly, comprehensive, and 30-day soak profiles.
* Added release-facing README, contributing guide, security policy, support guide, Maven Central release guide, GitHub Release guide, third-party notices, and bilingual companions.

## Security

This release does not declare a dedicated security fix. Release materials now include `SECURITY.md` and credential-protection checks; release credentials, GPG private keys, and staging secrets must not be committed.

## Storage and Recovery Notes

MVStore space reclamation remains an experimental maintenance API. It does not add SQL entry points and does not schedule itself automatically. Pluginized storage extension supports provider registration, storage id persistence, missing-provider read-only rescue downgrade, and maintenance capability gates; production main-path storage engines must remain MVStore-backed.

## SQL and JDBC Notes

`JdbcUrlPrefixProvider` can map Driver-level prefixes such as `jdbc:vendor:*` to H2 URLs after automatic discovery. Plugin class loading through JDBC URL settings is no longer supported; URLs only select already discovered providers. Parser, function, auth, optimizer, and wire protocol extension points are outside this release scope.

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.3.0</version>
</dependency>
```

## Verification

Main verification commands:

```powershell
cd h2
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runH2LegacySmoke
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat --rerun-tasks runLongRunJUnitCheck
.\gradlew.bat --rerun-tasks longRunTestDistZip
```

### LongRun Acceptance

If this release includes the LongRun distribution package, list accepted profiles:

| Profile | Command | Result | Key Metrics |
| --- | --- | --- | --- |
| smoke | `./bin/h2-longrun watch -c config/smoke.properties` | PASS | About 14.09 million operations, 4 reopen checks, 60 reclamation success events, and 0 suspicious log lines. |
| performance | `./bin/h2-longrun watch -c config/performance.properties` | PASS | Online reclamation added moderate throughput overhead while significantly reducing final file size and MVStore size amplification. |
| crash/recovery | `./bin/h2-longrun watch -c config/crash-recovery.properties` | PASS | 15 crash cycles, 29 recovery checks, 0 warnings, and 0 suspicious log lines. |
| fault-injection | `./bin/h2-longrun watch -c config/fault-injection.properties` | PASS | 14 fault injection events, 11 recovered, 3 detected or detected by verify, and 0 unexpected. |

Performance profile note: online reclamation adds moderate throughput overhead in this release, but significantly reduces MVStore file growth and size amplification; this trade-off matches the long-running stability goal.

## Known Issues

* The plugin model is static; hot loading, unloading, and online replacement are not supported.
* Plugin manifest/signing, dedicated sandboxing, and parser/function/auth/optimizer/wire protocol extension points are outside this release scope.
* Non-MVStore production main paths are deferred until system catalog tables, LOBs, transaction logs, and temporary results are separated from `Store`.
* MVStore space reclamation is an experimental maintenance API. It does not expose SQL and does not schedule itself automatically.
