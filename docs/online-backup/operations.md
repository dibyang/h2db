# H2DB / ADB 在线备份、Shadow 恢复与 Generation 切换手册

[English](../en/online-backup/operations.md)

## 适用范围

本手册适用于 H2DB 2.4.x、Vexra ADB 0.2.x 和 Vexra LDB
0.13.x 的首期单 participant 灰度。H2 负责 generation-local 的一致切点、
shadow 校验、排空、activation token 和旧实例 fence；ADB 持久化 generation
registry、`routeVersion`和 active pointer。

## 灰度顺序

ADB 配置 `vexra.adb.onlineBackup.mode`：

1. `disabled`：默认值；旧备份路径保持不变。
2. `generate-only`：只允许单个已认证 `adb_ldb`生成组合 bundle。
3. `shadow-validate`：允许恢复到隔离 shadow 并执行只读校验。
4. `activate`：允许排空旧 generation、CAS pointer、切换路由和 fence。

禁止跳级。达到性能、容量、故障和恢复门禁前不得替换默认备份路径。

## 生成与校验

1. 连接串显式配置 `ONLINE_BACKUP_COORDINATION=TRUE`和本次
   `ONLINE_BACKUP_GENERATION_ID`。
2. 通过 `Connection.unwrap(OnlineBackupControl.class)`准备备份，ADB 生产入口
   必须用 `AdbOnlineBackupRolloutPolicy`校验 participant 列表。
3. 发布成功后保存 `manifest.json`、操作报告、`databaseId`、`generationId`、
   `backupId`和`cutId`。
4. shadow 路径必须与活动 generation 隔离；participant allowlist 只包含
   `adb_ldb`。
5. shadow 校验成功不等于已激活，不得提前接收生产写入。

## 激活与崩溃恢复

1. ADB 请求旧 H2 generation `prepareActivation`，等待事务排空并取得 token。
2. registry 持久化 `SWITCH_PREPARED`。
3. 以 `routeVersion`为条件把 active pointer 从 old CAS 为 new。
4. 根据已持久化 pointer 更新进程内路由。
5. 消费 token，永久 fence old；registry 完成 fence 状态。

恢复只看持久化 pointer：

- pointer 为 old：启动 old，取消尚未 CAS 的 prepared switch。
- pointer 为 new：启动 new，继续更新进程内路由并 fence old。
- new 首次写入前可执行显式、带 routeVersion CAS 的回拨。
- new 首次写入前，router 会先持久化 `newWritesAccepted=true`；此后禁止回拨，
  只能 forward recovery、重新切换或从一致备份恢复。

## 只读旧库 Clone Onboarding

缺少 `h2.onlineBackup.meta`的只读旧库不能参与组合备份：

1. 保持原库只读且不修改。
2. 复制到独立、可写、未对外服务的 clone。
3. 使用 2.4.x 和新的 `generationId`首次打开 clone，生成新的 `databaseId`和
   `schemaEpoch=0`。
4. 对 clone 执行完整备份、shadow validation 和数据对账。
5. 把 clone 登记为新备份链；不得宣称延续原库不存在的 identity 历史。
6. 按正常 activation 协议切换。原库保留到回滚窗口和审计完成。

## 回滚与清理

- pointer CAS 前失败：abort token，清理本次 staging，active 仍为 old。
- pointer CAS 后失败：禁止自动选择 old；按 pointer 恢复 new 并继续 fence。
- final bundle 不覆盖、不就地修改；失败只清理本次确定拥有的 staging。
- 进程强退可能遗留 `.staging-<backupId>`；它不是已发布备份，禁止手工改名。
  确认对应任务已终止且 final 不存在后，才可按完整 `backupId`精确清理。
- activation token、snapshot lease 和 LDB checkpoint lease 必须关闭。
- 清理 old generation 前确认：新实例健康、备份可恢复、审计完成、回滚窗口结束，
  且 registry 状态为 `FENCED`。

## 发布门禁

- H2 `runOnlineBackupCheck`和`runPluginArchitectureCheck`通过。
- ADB、LDB 全量单元测试和 Javadoc 通过。
- 2.3.0 数据文件、传统 zip、插件和 TCP 双向兼容矩阵通过。
- 性能报告满足 prepare P99 500 ms、最大值 1 秒 SLO，写吞吐在 5 秒内恢复到
  基线 90%以上，且文件增长在容量预算内。
- provider 认证清单、故障矩阵和本手册与发布制品一起归档。
