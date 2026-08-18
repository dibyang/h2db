# h2db 2.3.1

[中文](RELEASE_NOTES_TEMPLATE.md)

## Summary

This 2.3.1 release is an MVStore stability update. It fixes a lifecycle race between online space reclamation and store close, and a startup failure caused by overlapping physical ranges in abandoned chunk metadata.

## Compatibility

This release keeps the H2 embedded and server database model. Maven coordinates are `net.xdob.h2db:h2db:2.3.1`. It does not change SQL, the JDBC API, or the MVStore on-disk format; databases from 2.3.0 can be upgraded directly.

## Changes

* Fixed the MVStore lifecycle race between online space reclamation and store close. New reclamation writes cannot start after closing begins.
* Added validation of all allocated chunk physical ranges before rebuilding the free-space bitmap, even when a clean-shutdown marker is present.
* Routed overlaps that only involve abandoned chunks with no live pages through recovery, avoiding duplicate free-space mark errors during database open.
* Added deterministic regression coverage for overlapping abandoned chunks, live-chunk conflicts, and concurrent reclamation/store close.

## Security

This release does not declare a dedicated security fix. Release credentials, GPG private keys, and staging secrets must not be committed.

## Storage and Recovery Notes

MVStore space reclamation remains an experimental maintenance API. It does not add SQL entry points or schedule itself automatically. Eligible legacy damaged files can persist recovered metadata after a writable open and clean close. Files with overlapping live ranges or unverifiable consistency are still rejected; this fix is not a general-purpose database corruption repair tool. Keep an original backup before upgrade or repair.

## SQL and JDBC Notes

This release has no SQL semantic or JDBC API behavior changes.

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.3.1</version>
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
.\gradlew.bat runMvStoreReclamationJUnitCheck
.\gradlew.bat runH2TestAllCi
.\gradlew.bat --rerun-tasks runLongRunJUnitCheck
.\gradlew.bat --rerun-tasks longRunTestDistZip longRunTestDistTar
```

### LongRun Acceptance

| Profile | Command | Result | Key Metrics |
| --- | --- | --- | --- |
| comprehensive 12h | `java -jar h2-longrun.jar -c config/comprehensive.properties` | PASS | 1,776,387,749 operations, 58 reopen checks, 23 recovery checks, all 4,308 reclamation events successful, 0 warnings, and 0 suspicious log lines. |

## Known Issues

* MVStore space reclamation is an experimental maintenance API. It does not expose SQL and does not schedule itself automatically.
* Automatic recovery is limited to overlapping chunks confirmed to be abandoned with no live pages. Overlapping live ranges are still rejected as corruption.
* 2.3.1 handles the overlapping abandoned-chunk failure covered by this release; it does not promise automatic repair for unrelated truncation, page corruption, or storage-media failures.
