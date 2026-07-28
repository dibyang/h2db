# P9 在线备份性能门禁报告

[English](../en/online-backup/p9-performance-report.md)

## 口径

- 单个已认证真实 participant：`adb_ldb`。
- 本机 JDK 8、Windows、本地文件系统。
- ADB 写线程持续执行单行事务。
- 连续执行 30 次 H2 + LDB prepare/abort。
- prepare SLO：P99 和最大值不超过 1 秒；停止 barrier 操作后写吞吐应恢复。

## 结果

| 指标 | 实测 |
| --- | ---: |
| prepare P50 | 0 ms |
| prepare P95 | 1 ms |
| prepare P99 | 3 ms |
| prepare max | 3 ms |
| barrier 前 500 ms 提交数 | 622 |
| barrier 后 500 ms 提交数 | 529 |
| 测试期间 LDB 净文件增长 | 0 bytes |

自动门禁为
`AdbOnlineBackupPerformanceGateTest#keepsPrepareUnderOneSecondAndRecoversWriterThroughput`。

混合负载门禁同时保持一个未提交 MVStore 长事务、预装 1 MiB LDB 脏数据，并
持续执行 LDB DML 和 MVStore DDL。20 次 prepare/abort 的结果为：

| 指标 | 实测 |
| --- | ---: |
| prepare P50 | 4 ms |
| prepare P95 | 8 ms |
| prepare P99 | 18 ms |
| prepare max | 18 ms |
| 并发 DML 提交 | 57 |
| DDL create/drop 周期 | 17 |
| 预装脏数据 | 1,048,576 bytes |
| 长事务结果 | 回滚后 0 行 |

自动门禁为
`AdbOnlineBackupMixedLoadGateTest#keepsBarrierBoundedUnderMixedLoad`。
该结果证明当前机器和约定负载满足首期灰度 SLO，不代表对任意磁盘、脏页规模或
participant 数量的无条件承诺。放宽真实 participant 数量或改变存储介质前必须重测。
