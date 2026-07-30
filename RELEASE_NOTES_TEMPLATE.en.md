# h2db 2.4.0

[中文](RELEASE_NOTES_TEMPLATE.md)

## Summary

This release focuses on coordinated online backup and shadow restore, DML fast path V1, and lifecycle reliability across execution, storage, networking, and tools. An operation gate, persistent database identity, prepared snapshots, participant coordination, atomic bundles, shadow validation, and a generation activation fence provide consistency across the database and external persistence participants.

## Compatibility

This release keeps the H2 embedded and server database model. Maven coordinates are `net.xdob.h2db:h2db:2.4.0`. Verification covers 2.3 database files, legacy zip backups, 2.3 API plugins, and regular JDBC / legacy backup paths in both version directions.

Coordinated online backup is disabled by default and fixed when a database is opened. Enabling it writes internal database identity metadata; the database must not subsequently be opened for writes by 2.3.x. Legacy databases can continue with legacy backup or use clone onboarding.

## Changes

* Added coordinated online backup and shadow restore with an operation gate, database identity/schema epoch, MVStore prepared snapshots, participant SPI, atomic manifests, shadow validation, generation tokens, and an activation fence.
* Added TCP online-backup control protocol v21/v22; older clients can continue through regular JDBC and legacy backup.
* Added provider certification, fault matrix, performance report, and bilingual online-backup operations guides.
* Completed experimental DML fast path V1 for simple `INSERT ... VALUES` and JDBC batches, including plan recognition, read-only parameter views, batch parameter views, table bulk writes, and transaction/error compatibility safeguards.
* Fixed immediate LOB-close races, SourceCompiler concurrent output, FilePathDisk retry convergence, GUI Console caller interrupt contamination, and interruption semantics across executor/session/server/auth/pool/file-lock/network lifecycles.
* Added clearer GitHub issue, contribution, and security intake paths.

## Security

This release does not declare a dedicated security fix. The online-backup control path validates identity, generation tokens, and activation fences. Release credentials, GPG private keys, and staging secrets must not be committed.

## Storage and Recovery Notes

Coordinated online backup creates an atomic bundle and restores it into a separate shadow directory for validation before generation activation. The deployment/router layer owns the generation registry, active pointer, CAS switch, and crash recovery. The first production rollout certifies one real `adb_ldb` participant.

## SQL and JDBC Notes

The DML fast path remains experimental and safely falls back. Generated keys, triggers, constraints, delta tables, `INSERT SELECT`, `MERGE`, `UPDATE`, and `DELETE` stay on the native execution path. There is no in-repository ADB/LDB integration throughput result, so this release makes no 1.5x or 3x performance claim.

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.4.0</version>
</dependency>
```

## Verification

Main verification commands:

```powershell
cd h2
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runH2LegacySmoke
.\gradlew.bat runOnlineBackupCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat --rerun-tasks runLongRunJUnitCheck
.\gradlew.bat --rerun-tasks longRunTestDistZip
.\gradlew.bat runH2TestAllCi
.\gradlew.bat clean jar sourceJar javadocJar generatePomFileForMavenPublication
.\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=build\test-m2-release-clean"
```

### Online Backup Acceptance

* Online-backup checks: 82/82.
* Plugin architecture checks: 140/140.
* The 2.3 compatibility matrix, fault matrix, shadow restore, and generation activation fence checks passed.
* Prepare latency p99/max under continuous DML: 2ms/2ms.
* Prepare latency p99/max under mixed DML, DDL, and long transactions: 21ms/21ms.

## Known Issues

* The first production rollout certifies only one real `adb_ldb` participant. New providers must pass the certification specification and fault matrix.
* Coordinated online backup does not replace deployment-layer generation registry, active pointer, CAS, or crash recovery.
* The DML fast path remains experimental V1; its scope and performance claims are limited as described above.
