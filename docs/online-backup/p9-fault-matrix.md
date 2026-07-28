# P9 在线备份故障与崩溃矩阵

[English](../en/online-backup/p9-fault-matrix.md)

## 门禁

| 故障点 | 自动化证据 | 期望恢复行为 | 结果 |
| --- | --- | --- | --- |
| participant prepare | `OnlineBackupSessionTest#prepareFailureUsesReverseCleanupWithSuppressedFailures` | 逆序 abort 已完成 participant，保留 suppressed cleanup failure，释放 H2 snapshot | 通过 |
| participant materialize | `OnlineBackupBundleFaultMatrixTest#participantMaterializeFailureLeavesNoFinalOrStaging` | final 不可见，清理本次 staging，活动库继续可写 | 通过 |
| artifact checksum | `OnlineBackupBundleFaultMatrixTest#prePublishFailuresLeaveNoFinalOrStaging` | final 不可见，清理 staging | 通过 |
| artifact / manifest / staging directory fsync | 同上 | final 不可见，清理 staging | 通过 |
| atomic rename | 同上 | final 不可见，清理 staging | 通过 |
| rename 后 parent directory fsync | `OnlineBackupBundleFaultMatrixTest#parentFsyncFailureConvergesByIdempotentRetry` | 保留已经原子发布的 final，同一 cut 重试幂等复用 | 通过 |
| participant 完成后、atomic rename 前进程退出 | `OnlineBackupBundleFaultMatrixTest#processExitBeforeAtomicRenameNeverPublishesStaging` | final 不可见，完整 staging 不被误发布；源库可重开，新任务可继续发布 | 通过 |
| restore checksum | `ShadowRestoreCoordinatorTest#checksumFailureLeavesActiveDatabaseUnchanged` | shadow final 不可见，活动库不变且可写 | 通过 |
| shadow read-only open | `ShadowRestoreCoordinatorTest#validatesEncryptedShadowWithoutPersistingKey` | 错误凭据使试打开失败，清理失败 shadow final | 通过 |
| router switch | `AdbGenerationActivationCoordinatorTest#convergesForwardWhenInMemoryRouteUpdateFailsAfterPointerCas` | pointer 已为 new 时只做 forward recovery，完成路由和 old fence | 通过 |
| pointer 更新前重启 | `AdbGenerationRegistryTest#recoversStrictlyFromPersistedPointerAtEachCrashPoint` | pointer 为 old 时恢复 old，取消未提交 switch | 通过 |
| pointer 更新后重启 | 同上 | pointer 为 new 时恢复 new，继续 fence old | 通过 |
| new 首次写入后回拨 | `AdbGenerationRegistryTest#forbidsRollbackAfterFirstNewGenerationWrite` | fail-closed，禁止自动回拨 old | 通过 |

## 判定

- final bundle 只有同父目录 atomic rename 成功后可见。
- rename 前受控异常只清理本次 `backupId`拥有的 staging；进程强退可能遗留完整
  staging，但它不具备 final 路径语义且不得被手工改名为 final。
- rename 后失败以持久化 final 或 active pointer 为真相，重试向前收敛。
- generation 恢复不依赖进程内缓存；registry 重开测试模拟进程状态丢失。
- H2 `runOnlineBackupCheck`当前 76/76 通过；故障矩阵测试无 skipped。
