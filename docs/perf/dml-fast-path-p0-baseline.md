# DML Fast Path P0 Baseline

[中文](dml-fast-path-p0-baseline.md)

## Scope

This document freezes the P0 baseline for `docs/plugin/plugin-dml-fast-path-plan.md`.
Later DML fast-path phases must compare against this baseline unless a new baseline is explicitly recorded with the same fields.

## Baseline Identity

| Field | Value |
| --- | --- |
| Baseline date | 2026-06-25 |
| h2db repository | `D:/work/java/h2db` |
| h2db baseline commit | `0390f0f9a` |
| ADB/LDB repository | `D:/work/java2/vexra-ldb` |
| ADB/LDB observed commit | `9b679ef` |
| Workload name | `insert_batch100` |
| Evidence source | User-provided long-run result, recorded into h2db for P0 tracking |

The ADB/LDB commit is recorded from the local repository state that was present when this baseline was created. If the original measurement was produced from a different ADB commit, the next rerun must record the exact commit and replace this row with the measured source commit.

## Frozen Measurements

| Scenario | Throughput | Ratio vs H2 full JDBC |
| --- | ---: | ---: |
| ADB store | 250,000 ops/s | 5.77x |
| ADB txn | 172,413 ops/s | 3.98x |
| ADB full JDBC `insert_batch100` long run | 40,453 ops/s | 0.93x |
| H2 full JDBC `insert_batch100` long run | 43,365 ops/s | 1.00x |

Calculation:

```text
ADB full JDBC ratio = 40,453 / 43,365 = 0.933
ADB txn potential ratio = 172,413 / 43,365 = 3.98
ADB store potential ratio = 250,000 / 43,365 = 5.77
```

## Interpretation

P0 confirms that the current bottleneck is not the ADB store or transaction layer alone. The store and transaction measurements have enough headroom to exceed native H2 full JDBC by more than 3x, but the complete JDBC path is currently below native H2 at about `0.93x`.

The later phases therefore optimize the h2db JDBC / prepared statement / DML execution boundary, not only ADB internals.

## Rerun Protocol

All follow-up performance reports for P3, P5, P7, and P8 must record the following fields:

| Field | Required value |
| --- | --- |
| h2db commit | Exact short and full commit hash |
| ADB/LDB commit | Exact short and full commit hash |
| JDK | Vendor and version |
| OS / CPU / disk | Machine identity or enough details to compare runs |
| JDBC URL | Full URL with secrets removed |
| workload | `insert_batch100` unless explicitly updating this baseline |
| batch size | `100` |
| row shape | Column count, key type, value sizes |
| transaction mode | `autoCommit` and explicit commit policy |
| generated keys | enabled / disabled |
| indexes / constraints | primary key and secondary indexes |
| run duration | Wall-clock duration or operation count |
| warmup | Whether warmup was used |
| throughput | ops/s |
| CPU / GC / IO notes | Sampling or profiler summary when available |

Suggested report template:

```text
run.id=
date=
h2db.commit=
adb.commit=
jdk=
os.cpu.disk=
jdbc.url=
workload=insert_batch100
batch.size=100
row.shape=
transaction.mode=
generated.keys=
indexes.constraints=
duration=
warmup=
throughput.opsPerSec=
ratio.vs.h2Baseline=
notes=
```

## Acceptance for P0

| Requirement | Evidence |
| --- | --- |
| H2 full JDBC baseline recorded | `43,365 ops/s` in this document |
| ADB full JDBC baseline recorded | `40,453 ops/s` in this document |
| ADB txn and store headroom recorded | `172,413 ops/s` and `250,000 ops/s` in this document |
| Current ratio recorded | `40,453 / 43,365 = 0.933` |
| h2db commit recorded | `0390f0f9a` |
| ADB/LDB local commit recorded | `9b679ef` |
| Rerun template recorded | `Rerun Protocol` section |

P0 is considered complete for h2db tracking once this file is committed. P7/P8 must replace or supplement this baseline with fresh measured runs from the new DML hook path.
