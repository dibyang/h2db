# P9 Online Backup Fault and Crash Matrix

[中文](../../online-backup/p9-fault-matrix.md)

| Fault point | Executable evidence | Required recovery | Result |
| --- | --- | --- | --- |
| Participant prepare | `OnlineBackupSessionTest#prepareFailureUsesReverseCleanupWithSuppressedFailures` | Reverse-abort completed participants, preserve suppressed cleanup failures, release the H2 snapshot | Pass |
| Participant materialize | `OnlineBackupBundleFaultMatrixTest#participantMaterializeFailureLeavesNoFinalOrStaging` | No final, owned staging removed, active database remains writable | Pass |
| Checksum, artifact/manifest/directory fsync, atomic rename | `OnlineBackupBundleFaultMatrixTest#prePublishFailuresLeaveNoFinalOrStaging` | No final before rename; owned staging removed | Pass |
| Parent-directory fsync after rename | `OnlineBackupBundleFaultMatrixTest#parentFsyncFailureConvergesByIdempotentRetry` | Keep the atomically published final and converge by same-cut idempotent retry | Pass |
| Process exit after participant materialization and before rename | `OnlineBackupBundleFaultMatrixTest#processExitBeforeAtomicRenameNeverPublishesStaging` | No final; complete staging is not published; source reopens and a new task can publish | Pass |
| Restore checksum | `ShadowRestoreCoordinatorTest#checksumFailureLeavesActiveDatabaseUnchanged` | No shadow final; active database unchanged | Pass |
| Shadow read-only open | `ShadowRestoreCoordinatorTest#validatesEncryptedShadowWithoutPersistingKey` | Failed open leaves no shadow final | Pass |
| In-process route switch | `AdbGenerationActivationCoordinatorTest#convergesForwardWhenInMemoryRouteUpdateFailsAfterPointerCas` | A new pointer permits forward recovery only; finish routing and fencing | Pass |
| Restart before/after pointer CAS | `AdbGenerationRegistryTest#recoversStrictlyFromPersistedPointerAtEachCrashPoint` | Recover strictly from the persisted pointer | Pass |
| Rollback after first new write | `AdbGenerationRegistryTest#forbidsRollbackAfterFirstNewGenerationWrite` | Fail closed; never automatically route back to old | Pass |

The final bundle becomes visible only through a same-parent atomic rename.
Controlled failures before rename remove only staging owned by the current
backup ID. A hard process exit may leave complete staging, but staging has no
final-path semantics and must never be manually renamed into a final bundle.
Failures after rename or pointer CAS follow the persisted final or pointer and
converge forward. Registry reopen tests model loss of all process-local state.
The H2 online-backup suite currently passes 76/76 with no skipped tests.
