# Online Backup Provider Certification

[中文](../../online-backup/provider-certification.md)

| Provider | Type | Versions | Required for | Validation behavior | Status |
| --- | --- | --- | --- | --- | --- |
| `adb_ldb` | `online_backup_participant` | ADB 0.2.x / LDB 0.13.x / H2DB 2.4.x | ADB/LDB table data | Reads only manifest-declared streams, copies them to an isolated temporary directory, and runs offline `LDBFactory.check` | Certified |
| `mvstore` | `storage_engine` | H2DB 2.4.x | H2 catalog | Read-only validation open | Built-in certified |
| `mvstore` | `system_catalog` | H2DB 2.4.x | H2 catalog | Read-only validation open | Built-in certified |

`adb_ldb` validation must not start schedulers or background workers, register
services, run migrations or seed writes, publish messages, connect to
production endpoints, or access an active generation path.

Evidence is provided by `AdbOnlineBackupParticipantIntegrationTest`,
`LdbPreparedCheckpointTest`, `ShadowRestoreCoordinatorTest`,
`OnlineBackupBundlePublisherTest`, and the complete H2 online-backup suite.
Unknown providers fail closed. Any provider or version change requires renewed
side-effect, barrier-performance, fault, and compatibility certification.
