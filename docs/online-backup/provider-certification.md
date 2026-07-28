# Online Backup Provider 认证清单

[English](../en/online-backup/provider-certification.md)

| Provider | 类型 | 版本范围 | 必要性 | Prepare 行为 | Validation 行为 | 认证状态 |
| --- | --- | --- | --- | --- | --- | --- |
| `adb_ldb` | `online_backup_participant` | ADB 0.2.x / LDB 0.13.x / H2DB 2.4.x | ADB/LDB 业务表数据必需 | 在 LDB mutex 内固定 retained Version、MemTable 引用和 lastSequence；锁外物化 | 只读取 manifest 声明的流，复制到隔离临时目录，调用离线 `LDBFactory.check` | 已认证 |
| `mvstore` | `storage_engine` | H2DB 2.4.x | H2 catalog 必需 | H2 prepared snapshot | 只读 validation open | 内置已认证 |
| `mvstore` | `system_catalog` | H2DB 2.4.x | H2 catalog 必需 | 不作为 participant | 只读 validation open | 内置已认证 |

`adb_ldb` validation 明确禁止：

- scheduler、后台 worker、服务注册和发现；
- schema/data migration、seed/init write；
- 外部消息发布；
- 指向生产服务的网络连接；
- 活动 generation 路径或可写句柄访问。

认证证据：

- ADB `AdbOnlineBackupParticipantIntegrationTest`；
- LDB `LdbPreparedCheckpointTest`及全量测试；
- H2 `ShadowRestoreCoordinatorTest`、`OnlineBackupBundlePublisherTest`和
  `runOnlineBackupCheck`。

新增或升级 provider 后必须重新执行 validation 副作用测试、barrier 性能测试、
故障矩阵和兼容矩阵。未列入本表的 provider fail-closed。
