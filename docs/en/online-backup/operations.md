# H2DB / ADB Online Backup and Generation Operations

[中文](../../online-backup/operations.md)

This runbook applies to the first H2DB 2.4.x, ADB 0.2.x, and LDB 0.13.x
rollout. H2 owns generation-local snapshots, validation, drain, activation
tokens, and fencing. ADB owns the persistent generation registry,
`routeVersion`, and active pointer.

Roll out `vexra.adb.onlineBackup.mode` in this order:
`disabled`, `generate-only`, `shadow-validate`, then `activate`. The ADB
production entry point accepts only the certified `adb_ldb` participant.
Do not replace the existing backup path before performance, capacity, fault,
and recovery gates pass.

The performance gate requires prepare P99 at or below 500 ms, the maximum at
or below one second, and writer throughput to recover to at least 90% of its
baseline within five seconds.

Activation order is: drain old H2 and obtain a token; persist
`SWITCH_PREPARED`; CAS the active pointer; update the in-process route; consume
the token and fence old. Recovery always follows the persisted pointer. An old
pointer starts old and aborts an uncommitted switch. A new pointer starts new
and continues fencing old. The router persists the first-new-write marker
before returning a write route; rollback is forbidden after that marker.

Legacy read-only databases without `h2.onlineBackup.meta` must be copied to an
isolated writable clone. Open the clone with H2DB 2.4.x and a new generation
ID, create a new database identity and backup lineage, run backup and shadow
validation, reconcile data, and activate normally. The original remains
unchanged.

Before deleting an old generation, confirm that the new generation is healthy,
the backup restores, auditing and the rollback window are complete, and the
registry marks old as `FENCED`. Always close activation tokens, H2 snapshot
leases, and LDB checkpoint leases. Never overwrite a published bundle.
A hard process exit may leave `.staging-<backupId>`. It is not a published
backup and must never be manually renamed. Remove it only after confirming the
owning task has ended and no final bundle exists.
