# P9 在线备份性能门禁报告

[English](../en/online-backup/p9-performance-report.md)

## 口径

- 单个已认证真实 participant：`adb_ldb`。
- 本机 JDK 8、Windows、本地文件系统。
- ADB 写线程持续执行单行事务。
- 连续执行 30 次 H2 + LDB prepare/abort。
- prepare SLO：P99 不超过 500 ms、最大值不超过 1 秒；停止 barrier 操作后
  写吞吐在 5 秒内恢复到基线 90%以上。

## 结果

| 指标 | 实测 |
| --- | ---: |
| prepare P50 | 0 ms |
| prepare P95 | 1 ms |
| prepare P99 | 2 ms |
| prepare max | 2 ms |
| barrier 前 500 ms 提交数 | 601 |
| 首个达到 90% 的 500 ms 恢复窗口 | 809 |
| 恢复到 90% 的时间 | 1,012 ms |
| 测试期间 LDB 净文件增长 | 0 bytes |

自动门禁为
`AdbOnlineBackupPerformanceGateTest#keepsPrepareUnderOneSecondAndRecoversWriterThroughput`。

混合负载门禁同时保持一个未提交 MVStore 长事务、预装 1 MiB LDB 脏数据，并
持续执行 LDB DML 和 MVStore DDL。20 次 prepare/abort 的结果为：

| 指标 | 实测 |
| --- | ---: |
| prepare P50 | 4 ms |
| prepare P95 | 7 ms |
| prepare P99 | 21 ms |
| prepare max | 21 ms |
| 并发 DML 提交 | 48 |
| DDL create/drop 周期 | 14 |
| 预装脏数据 | 1,048,576 bytes |
| 长事务结果 | 回滚后 0 行 |

自动门禁为
`AdbOnlineBackupMixedLoadGateTest#keepsBarrierBoundedUnderMixedLoad`。
两项门禁均显式断言 P99 不超过 500 ms、最大值不超过 1,000 ms；持续 DML
门禁还断言吞吐在 5 秒内恢复到基线的 90%以上。该结果证明当前机器和约定负载
满足首期灰度 SLO，不代表对任意磁盘、脏页规模或
participant 数量的无条件承诺。放宽真实 participant 数量或改变存储介质前必须重测。
