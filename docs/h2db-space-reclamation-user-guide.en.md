# H2 MVStore Space Reclamation Maintenance API User Guide (Experimental)

This is the English companion of
[h2db-space-reclamation-user-guide.md](h2db-space-reclamation-user-guide.md).
The Chinese document is the primary version.

## Positioning

`MVStoreSpaceReclamation` is a maintenance-window API for MVStore space reclamation. It is intended to reclaim bloated `.mv.db` files after auto compact is disabled or after large delete workloads.

The current capability does not expose SQL, does not schedule itself automatically, and does not change the `.mv.db` file format. External users can call the Java API explicitly after closing the database or entering a controlled maintenance window.

## Suitable Scenarios

- The `.mv.db` file is much larger than the actual live data.
- The application can schedule a maintenance window, pause writes, and ensure no other process opens the same database file.
- The user wants to rewrite the MVStore file without exporting and importing application data.
- The user needs a diagnosable, recoverable shadow compact / full-copy maintenance path.

## Non-Goals

- This API is not a root-cause fix for `.mv.db` corruption.
- It does not support `FILE_LOCK=NO`, `nolock:`, or multiple JVM embedded writers.
- It should not switch a prepared shadow unconditionally while business writes continue.
- The current version does not provide real version-scan incremental catch-up; source changes reject the switch by default.

## Basic Usage

```java
MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactClosedStore(
        fileName,
        MVStoreSpaceReclamationOptions.builder()
                .keepBackup(true)
                .build());
```

`compactClosedStore()` creates a shadow file, verifies it, backs up the source file, switches files, and restores from backup first when failures happen.

## Split-Phase Usage

Callers can split shadow creation and the final switch:

```java
MVStoreSpaceReclamation.compactToShadow(fileName, MVStoreSpaceReclamationOptions.DEFAULT);
MVStoreSpaceReclamationAnalysis analysis = MVStoreSpaceReclamation.analyzePreparedShadow(fileName);
if (analysis.isSourceUnchanged()) {
    MVStoreSpaceReclamation.switchToShadow(fileName, MVStoreSpaceReclamationOptions.DEFAULT);
}
```

If the source changes after the shadow is created, `switchToShadow()` rejects the switch by default to avoid silently losing new data. If maintenance full-copy fallback is acceptable, enable it explicitly:

```java
MVStoreSpaceReclamation.switchToShadow(
        fileName,
        MVStoreSpaceReclamationOptions.builder()
                .refreshShadowIfSourceChanged(true)
                .build());
```

## Diagnostics

`MVStoreSpaceReclamationResult` exposes:

- `getSourceSize()`
- `getCompactedSize()`
- `getSavedBytes()`
- `getSavedPercent()`
- `getDiagnosticSummary()`

GitHub Release and Maven Central notes should ask users to keep this summary when reporting issues. It helps identify the failed phase, file-size changes, and whether replacement happened.

## Leftovers and Recovery

Maintenance can leave these files:

- `<db>.mv.db.reclaim.shadow`
- `<db>.mv.db.reclaim.backup`
- `<db>.mv.db.reclaim.manifest`

If the process stops around the switch window, call:

```java
MVStoreSpaceReclamation.recover(fileName);
```

If the source store exists and only leftover cleanup is needed, call:

```java
MVStoreSpaceReclamation.cleanUp(fileName);
```

## Release Guidance

When publishing a GitHub Release or Maven Central artifact for external users, state clearly that:

- This API is an experimental maintenance entry point.
- It must be used in a controlled maintenance window.
- It does not automatically catch up writes during copy by default.
- Source changes reject the switch by default; full-copy fallback only happens when `refreshShadowIfSourceChanged` is enabled explicitly.
- The artifact does not change the `.mv.db` format. After a completed switch, older versions can still open the new file as a normal MVStore file.

## Verification Commands

At minimum, maintainers should run:

```powershell
.\gradlew.bat compileJava
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
```
