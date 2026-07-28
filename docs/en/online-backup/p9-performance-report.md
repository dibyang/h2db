# P9 Online Backup Performance Gate

[中文](../../online-backup/p9-performance-report.md)

The gate used one certified `adb_ldb` participant, JDK 8 on Windows and local
storage, a continuously committing ADB writer, and 30 prepare/abort cycles.
The prepare P50/P95/P99/max values were 0/1/2/2 ms. The writer completed 601
transactions in the 500 ms baseline window and reached a window of 809
transactions after 1,012 ms. Net LDB file growth was 0 bytes.

The mixed-load gate kept an uncommitted MVStore transaction open, preloaded
1 MiB of LDB-backed dirty data, and ran continuous LDB DML and MVStore DDL.
Across 20 prepare/abort cycles, P50/P95/P99/max were 4/7/21/21 ms while 48
DML commits and 14 DDL create/drop cycles completed. The long transaction was
rolled back and remained absent.

The executable gate is
`AdbOnlineBackupPerformanceGateTest#keepsPrepareUnderOneSecondAndRecoversWriterThroughput`.
The mixed-load gate is
`AdbOnlineBackupMixedLoadGateTest#keepsBarrierBoundedUnderMixedLoad`.
Both gates assert P99 at or below 500 ms and the maximum at or below 1,000 ms.
The continuous-DML gate also asserts recovery to 90% of baseline within five
seconds. This proves the first-rollout workload meets the SLO on the tested
host. It is not an unconditional guarantee for other storage, dirty-page
volumes, or participant counts; those changes require a new measurement.
