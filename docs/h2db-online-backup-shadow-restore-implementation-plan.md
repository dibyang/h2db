# H2DB 组合在线备份与影子恢复实施计划

状态：实施中（P0-P8 已完成，P9 联调与灰度进行中）
规划日期：2026-07-28  
目标仓库：`D:\work\java\h2db`  
需求来源：`D:\work\java2\vexra-adb\docs\requirements\h2db-online-backup-restore-requirements.md`  
实现分支制品基线：`h2/gradle.properties`中的 `2.4.0-SNAPSHOT`  
兼容输入基线：已发布 `2.3.0`的数据文件、传统备份、插件和 TCP client/server  
版本元数据现状：`Constants.VERSION`仍报告 `2.3.0`，发布前必须与 Gradle 制品版本统一  
兼容基线：JDK 8

## 意图

本计划用于把 H2DB 与一个或多个外部存储 participant 的组合在线备份、影子恢复校验和有界激活能力拆分为可独立评审、实现、验证和回滚的阶段。

总体策略是先证明 commit/DDL barrier 和 MVStore prepared snapshot 两项基础能力，再增加 participant SPI、组合备份 bundle、shadow restore 和 generation activation。现有 `BACKUP TO`、`SCRIPT` 和 `org.h2.tools.Restore` 在第一阶段保持原行为，不直接接入 participant。

## 背景

当前 `BackupCommand` 会 flush H2DB 的 MVStore，并在复制期间关闭空间复用。该机制可以支持现有 H2DB 单库在线备份，但没有暴露固定 `cutId` 的 prepared snapshot，也无法在同一 barrier 内协调外部存储。

当前 `Restore` 只负责解压备份文件，没有 shadow generation、离线校验、只读试打开、有界事务排空和 activation token。

当前仓库已经具有 `PluginProvider`、`PluginRegistry`、`PluginSecurity`、`StorageEngine` 和 transaction event provider 等扩展基础，可以新增独立的在线备份 participant provider，而不要求已有插件实现新接口。

## 目标

- 建立 H2DB commit、DDL 和 restore activation 共用的数据库级准入控制能力。
- 为 MVStore 建立固定 header、固定文件高水位、可在 barrier 外复制的 prepared snapshot。
- 让 H2DB snapshot 和全部 participant snapshot 共享同一 `backupId`、`cutId`、`databaseId` 和 `schemaEpoch`。
- 在 staging 区完成复制、校验和 manifest 生成，最后原子发布正式备份。
- 将备份恢复到 shadow generation，校验通过前不覆盖或关闭活动数据库。
- 在有界排空成功后向 ADB 路由层提供单次消费的 activation token。
- 保持 JDK 8、旧数据库文件、旧插件和现有备份/恢复工具兼容。

## 非目标

- 不在 H2DB 内实现 LDB/RocksDB 文件复制细节、对象存储上传或跨 region 调度。
- 不在本计划内实现增量备份、PITR、CDC 或日志归档。
- 不在第一阶段修改普通 `BACKUP TO` 的 SQL 语法、权限、返回值或 zip 格式。
- 不在 H2DB core 中实现 ADB SQL Server 的 generation 路由和集群共识。
- 不保证 embedded 模式旧 JDBC 连接在激活后无感迁移。
- 不为 `jdbc:*:mem:` 提供物理在线备份。
- 不在开放问题确认前修改生产代码或公开 SPI。

## 范围

| 范围 | 内容 |
| --- | --- |
| In | commit/DDL barrier、事务准入、MVStore prepared snapshot、持久化数据库身份和 schema epoch、participant SPI、组合 manifest、原子发布、shadow restore、只读试打开、activation token、统一 JDBC `unwrap`入口、H2 TCP v21 远程管理协议、指标、故障清理、兼容与性能测试。 |
| Out | 增量备份、PITR、对象存储 SDK、组合备份 SQL 语法、ADB 路由实现、跨节点共识、强杀失控插件线程、旧连接透明迁移、内存库物理备份。 |

## 现状与拟修改路径

| 领域 | 当前入口 | 规划变更 |
| --- | --- | --- |
| SQL 备份 | `org.h2.command.dml.BackupCommand` | 第一阶段不改变行为；只复用或下沉必要的 MVStore snapshot 基础能力。 |
| MVStore 复制 | `MVStore`、`FileStore`、`SingleFileStore.backup()` | 新增 prepared snapshot 句柄，固定 header、高水位和空间复用 pin。 |
| commit | `SessionLocal.commit(boolean)` | 在 transaction provider 回调前后进入和退出 commit gate。 |
| DDL | `Command.executeUpdate()`、`DefineCommand` | 在 catalog 发生修改前进入 DDL gate，完成或失败后退出。 |
| session/transaction | `SessionLocal.getTransaction()`、`Database.createSession()` | restore quiesce 时拒绝新事务并统计已开始事务。 |
| 插件 | `PluginProvider`、`PluginRegistry`、`PluginSecurity` | 新增可选 participant provider 类型和显式选择机制。 |
| 恢复 | `org.h2.tools.Restore` | 保持旧工具不变，新增独立 `ShadowRestoreService`。 |
| 数据库生命周期 | `Database`、`Engine` | 增加 generation 状态、quiesce 和 fence，不负责外部路由。 |
| JDBC 入口 | `JdbcConnection.unwrap()` | `JdbcConnection`实现统一 `OnlineBackupControl`；本地和远程连接使用同一调用方式。 |
| H2 TCP server | `SessionRemote`、`TcpServerThread`、`Transfer`、协议版本常量 | 新增 TCP v21 管理操作、远程 handle、混合版本拒绝和断线清理。 |
| 测试 | `src/test`、`src/test-plugin`、Gradle 专项任务 | 增加 core、MVStore、plugin、restore、故障注入和性能专项入口。 |

## 核心约束

1. 所有主线代码和公共 API 保持 JDK 8 兼容。
2. barrier 获取和等待时不得持有 H2 meta lock、table lock、MVStore store lock、`SessionLocal` monitor 或 participant 自有锁。
3. participant `prepare()`不得执行文件复制、网络上传、压缩或全量 checksum。
4. 新 commit 和 DDL 的阻塞只发生在 prepare barrier 内；materialize、checksum 和发布必须在 barrier 外。
5. prepared snapshot 存活期间必须阻止空间复用、compact 和 reclamation 覆盖其引用的 chunk。
6. 所有失败路径必须释放 barrier、snapshot pin、临时文件和 participant 资源，并保留线程中断状态。
7. 正式备份只能通过同目录 staging 到 final 的原子移动发布。
8. shadow restore 不得覆盖活动数据库路径。
9. activation 排空超时必须恢复旧库准入，不允许强制切换。
10. 新增持久化元数据必须说明旧版本读取、升级、回滚和再次升级行为。
11. embedded 和 H2 TCP server 模式必须使用同一个公开 Java API，不增加组合备份 SQL 语法。
12. 远程 handle 必须绑定创建它的 TCP session、数据库和管理员身份，连接关闭后不得继续复用。
13. 远程客户端不得指定任意服务端绝对路径；只能使用服务端配置根目录下的受限相对名称。
14. 新客户端连接旧服务器时必须在发送新操作码前根据协商版本返回明确的不支持错误。
15. `ONLINE_BACKUP_COORDINATION`默认关闭，只能在 Database 打开阶段确定，Database 生命周期内禁止热切换。
16. 功能关闭时不得创建 backup metadata map、加载 participant、执行 commit/DDL gate 计数或改变现有 `BACKUP TO`行为。
17. 功能启用时才允许创建 gate、初始化 identity/epoch、加载 participant 和调用组合备份 API。
18. `databaseId -> activeGenerationId`的持久化路由只归 ADB SQL Server 所有；H2 不持久化全局 active generation，也不从目录、文件时间或可打开性推断路由。
19. 新 generation 一旦对外接受写入，不得通过简单回拨 active pointer 自动回滚到旧 generation；后续恢复只能采用数据对账后的新切换或从一致性备份重新恢复。
20. participant SPI、session、manifest 和清理协议必须支持多个 participant；所有 participant 共享一次 prepare barrier 的总 deadline，不得按 participant 重新计算完整超时。
21. 首次生产灰度由 ADB 配置和 provider allowlist 限制为一个真实外部 participant；该限制不写死在 H2 SPI 或 bundle 格式中。
22. shadow validation 必须 fail-closed：只加载打开 catalog/存储所必需、在白名单内且明确声明支持 validation mode 的 provider；不得以普通运行模式兜底加载插件。
23. validation 只读打开和插件副作用隔离是两层独立保护；只读数据库不能阻止插件发起网络调用、注册服务或启动后台任务。
24. quiesce、fence 和 activation timeout 使用标准 SQLState，但必须各自分配独立 H2 vendor error code；调用方不得通过解析本地化异常消息判断重试或重连。
25. 缺少 `h2.onlineBackup.meta`的只读旧库不得生成临时、派生或调用方指定的 identity 来执行组合备份；只能继续使用旧备份工具，或通过显式 onboarding 建立新的备份链。
26. snapshot lease 到期不得跨线程强制关闭 active materializer，也不得在 active reader 非零时解除 reuse-space pin；卡死 reader 场景宁可熔断新 snapshot/maintenance，也不能不安全回收。
27. 新能力只在当前 2.4.x 开发线实现，不回移到已发布 2.3.x；2.3.0 作为兼容性输入基线，不作为新 API、bundle reader 或 TCP v21 的运行目标。

## 推荐设计基线

除“开放问题与决策记录”中标记为“已确认”的内容外，以下内容仍是当前推荐方案，不视为已经批准的最终设计。

### 功能启用与默认存储隔离

组合备份协调使用启动期数据库配置：

```text
ONLINE_BACKUP_COORDINATION=FALSE
```

默认值为 `FALSE`。ADB 需要组合备份的数据库在首次打开时显式配置：

```text
ONLINE_BACKUP_COORDINATION=TRUE
```

该配置是 Database 实例级启动参数，不提供运行期 `SET`语法：

- 第一个打开 Database 的连接确定本实例模式。
- Database 打开后不能从 `FALSE`热切换为`TRUE`，也不能从`TRUE`热切换为`FALSE`。
- 后续连接显式给出与已打开 Database 不一致的值时必须返回配置冲突，不静默改变模式。
- H2 TCP server 模式由服务端数据库配置决定，远程客户端不能通过管理请求临时启用。

关闭时：

- `DatabaseOperationGate`不创建，或 commit/DDL 路径只执行一个可预测的空值分支。
- 不创建 `h2.onlineBackup.meta`，不初始化 `databaseId/schemaEpoch`。
- 不发现、加载或调用在线备份 participant。
- `OnlineBackupControl`可以通过 `unwrap`获得，但调用管理方法返回稳定的 feature-disabled 错误。
- 普通 MVStore、事务、DDL、compact、reclamation、`BACKUP TO`和旧 `Restore`保持原行为。

启用时：

- Database 打开阶段创建 operation gate。
- 对新库或可写旧库初始化 `databaseId/schemaEpoch`。
- 从 Database 打开开始统计 catalog 修改和在途 commit/transaction。
- 允许 prepared snapshot、participant、shadow restore 和 activation。
- 只读旧库缺少 identity metadata 时拒绝组合备份，不生成路径派生或临时 identity。

配置从关闭改为开启必须关闭并重新打开 Database，确保不存在未被 gate 统计的历史在途 commit。

### 数据库操作门

功能启用时新增内部组件 `DatabaseOperationGate`，状态如下：

```text
OPEN
  -> BACKUP_PREPARING
  -> OPEN

OPEN
  -> RESTORE_QUIESCING
  -> OPEN       // activation 取消或超时
  -> FENCED     // router 已切换，旧 generation 禁止新事务

任意状态
  -> CLOSED
```

需要独立统计：

- 已进入 commit 临界区的事务数。
- 已开始并可能修改 catalog 的 DDL 数。
- restore quiesce 前已经开始、尚未结束的事务数。
- barrier 等待者数量和最长等待时间。

commit gate 必须覆盖：

```text
enterCommit
  -> TransactionEventProvider.beforeCommit
  -> H2 transaction commit
  -> TransactionEventProvider.afterCommit
  -> exitCommit
```

DDL gate 必须在 DDL 修改内存 catalog 或 meta table 之前进入，不能只在 DDL 最后的 `commit(true)`处阻塞。

### MVStore prepared snapshot

建议由 `MVStore`提供内部 prepared snapshot 操作，并由 `SingleFileStore`实现文件物化：

```java
PreparedFileSnapshot prepareFileSnapshot();
```

句柄至少固定：

| 字段 | 说明 |
| --- | --- |
| snapshotVersion | 准备阶段对应的 MVStore 版本。 |
| headerBlocks | barrier 内捕获的 store header 副本。 |
| copyLength | barrier 内固定的文件复制高水位。 |
| sourceFingerprint | 源文件身份、长度和必要的创建信息。 |
| reuseSpacePin | 在句柄关闭前禁止覆盖旧 chunk。 |
| createdAt/deadline | 用于超时回收和诊断。 |

materialize 只复制 `copyLength`以内的稳定数据，并使用捕获的 header 构造目标文件，不重新读取可能已经变化的活动 header。

同一数据库第一阶段只允许一个 prepared snapshot。句柄超过配置的最大存活时间后：

- `PREPARED`且没有 reader 时，watchdog 取得唯一 cleanup 所有权并自动 abort。
- `MATERIALIZING`时只发布 cancellation request，由 materializer 在分块边界协作退出。
- 最后一个 reader 退出前继续持有 reuse-space pin、compact/reclamation guard 和 snapshot 排他权。
- expiry、显式 abort、session close 和 TCP disconnect 竞争时只允许一次 cleanup。
- reader 长时间不退出时记录严重告警并熔断该库的新 snapshot/maintenance；禁止强制线程终止或提前 unpin，最终通过受控停止或重启清理。

### 数据库身份和 schema epoch

建议新增内部事务型 MVStore map：

```text
h2.onlineBackup.meta
  databaseId   -> UUID
  schemaEpoch  -> long
```

- `databaseId`仅在协调功能启用时，于新库首次创建或可写旧库首次按该模式打开时生成。
- 普通备份恢复和 generation 复制保留 `databaseId`。
- 只读数据库已含 identity metadata 时可执行组合备份；缺少 metadata 时返回稳定的 `IDENTITY_METADATA_REQUIRED`原因，不修改原库。
- 每个 catalog 修改事务最多递增一次 `schemaEpoch`。
- epoch 更新与 catalog 修改使用同一个 H2 transaction，DDL 回滚时一起回滚。
- 不能直接以现有 `TransactionContext.isDdl()`作为递增条件；P0.5 已证明失败且未修改 catalog 的 DDL 也可能触发 DDL commit 事件。正式实现必须在命令成功路径记录 session 级 catalog-changed 标记。
- 已启用协调功能的数据库不支持降级到 2.3.x 后继续写入；2.3.x 只允许只读应急打开，否则旧版本 DDL 不会维护 `schemaEpoch`。
- 物理备份和 compact 自然保留该 map；逻辑 `Recover`脚本当前不会重建未知 map，正式实现必须扩展 Recover 或明确生成新 identity 的灾难恢复语义。
- `generationId`不写入 `h2.onlineBackup.meta`。物理备份会复制该 map，而 shadow restore 必须获得一个新的 generation 身份，因此 generation 不能成为被复制的 H2 数据库身份元数据。
- ADB 为 source/shadow generation 生成并持久化 `generationId`，通过实例启动描述或管理调用上下文传给 H2。H2 只在当前实例的运行时状态、activation token、日志和报告中携带它。

只读旧库缺少 identity metadata 时的 onboarding 不属于一次普通组合备份：

1. 原只读库保持不变，仍可使用现有 `BACKUP TO`或既有运维备份方式。
2. 运维显式创建独立的可写 clone/new generation，不覆盖原路径。
3. clone 首次以 `ONLINE_BACKUP_COORDINATION=TRUE`打开，生成新的 `databaseId`和初始 `schemaEpoch`。
4. ADB 将该实例登记为一条新的备份链；不得宣称它延续了原库不存在的 identity/epoch 历史。
5. 如需将 clone 激活为服务实例，仍需执行完整 validation 和 generation activation 流程。

禁止使用文件路径、文件时间、可变 header、全文件 checksum 或调用方传入 UUID 派生临时 identity。这些值不能同时满足跨复制/compact 稳定性、事务原子性和防串库要求。

### participant SPI

建议新增独立 provider 类型，旧插件不实现时不受影响：

```java
public interface OnlineBackupParticipantProvider extends PluginProvider {
    PreparedBackupParticipant prepare(
            OnlineBackupContext context) throws Exception;
}
```

`OnlineBackupOptions`显式提供 participant ID 列表。接口、状态机和 manifest 从第一版起使用集合模型，不提供只能容纳单 participant 的特殊字段或旁路。

协调器在进入 barrier 前完成 participant ID 去重、provider 解析、capability 和 allowlist 检查；进入 barrier 后按 participant ID 的稳定顺序依次调用 prepare，任一失败时按实际完成顺序逆序 abort。第一阶段不并行执行 provider 回调，以保持锁顺序、失败归因和清理行为可预测。

所有 participant 与 H2 snapshot 共享同一个 prepare 总 deadline。每次调用只能使用剩余预算；不能为每个 participant 分别提供完整的 1 秒窗口。participant 数量增加造成的 barrier 延迟必须通过 P9 性能门禁评估。

prepared metadata 和 materialized artifact metadata 分开：

```java
public interface PreparedBackupParticipant extends AutoCloseable {
    PreparedParticipantMetadata getPreparedMetadata();

    MaterializedParticipantArtifact materialize(
            ParticipantArtifactTarget target) throws Exception;

    void abort() throws Exception;
}
```

`materialize()`返回最终相对路径、长度、SHA-256 和 participant 自身版本信息，避免在 prepare 阶段计算全量 checksum。

### 组合备份 bundle

第一阶段推荐发布目录 bundle：

```text
backup-<backupId>/
  manifest.json
  h2/
    <database>.mv.db
  participants/
    <participantId>/
      ...
```

发布过程：

1. 在 final 目录同一父目录创建唯一 staging 目录。
2. materialize H2 snapshot 和 participant snapshot。
3. 计算全部 artifact 的长度和 SHA-256。
4. 写入稳定字段顺序的 UTF-8 JSON manifest。
5. fsync 文件；目录 fsync 按平台能力执行并记录结果。
6. 原子移动 staging 到 final。
7. 原子移动成功后才返回成功。

最终 manifest 只表示不可变的 `PUBLISHED`备份。`PREPARING`、`FAILED`、`ABORTED`等运行状态写入独立操作审计记录，不尝试修改已经发布的 manifest。

### shadow restore 和 activation

建议新增独立 Java API，不修改旧 `Restore`：

```java
PreparedDatabaseRestore stageShadowRestore(
        Connection activeConnection,
        Path backupBundle,
        Path shadowDirectory,
        ShadowRestoreOptions options);
```

`validate()`依次完成：

1. 校验 manifest 格式版本和 `PUBLISHED`状态。
2. 拒绝绝对路径、`..`、符号链接和 shadow root 逃逸。
3. 校验文件长度和 SHA-256。
4. 从 manifest、catalog 和启动描述解析试打开所需 provider，逐一校验类型、ID、版本、validation capability 和服务端 allowlist。
5. 以 validation mode 只读试打开 shadow Database；只传入 shadow 路径和受限验证上下文，不复用活动 generation 的可写路径或业务运行上下文。
6. 校验 catalog、`databaseId`、`schemaEpoch`和存储引擎能力。
7. 关闭试打开实例并返回验证报告；关闭期间同样不得触发普通业务 lifecycle。

validation mode 采用“必要 provider 白名单 + 显式 capability + 默认拒绝”：

- 只加载解析或打开 catalog、数据类型、存储引擎和恢复后 participant artifact 所必需的 provider。
- provider 必须显式声明支持 validation open，并接受只读 `ValidationOpenContext`；未声明能力的旧 provider 视为未认证。
- validation context 禁止启动 scheduler/background thread、服务注册与发现、schema/data migration、seed/init write、外部消息发布以及指向生产系统的网络连接。
- 非必要的业务插件不加载，也不执行其 start/stop 回调。
- shadow 依赖未认证、缺失、版本不兼容或不在 allowlist 内的必要 provider 时，返回稳定的 `UNVALIDATABLE_PROVIDER`失败；不得静默跳过，也不得退回普通启动模式。
- 第一阶段的能力声明是安全认证信号，不仅是功能提示；provider 违反契约时从 allowlist 移除，并阻止 activation。

activation token 建议补充明确的确认和取消入口：

```java
public interface DatabaseActivationToken extends AutoCloseable {
    void commitActivation();

    void abortActivation();
}
```

推荐时序：

```text
ADB -> H2 old generation: prepareActivation(timeout)
H2: reject new transactions
H2: wait in-flight transactions
H2 -> ADB: activation token
ADB: atomically switch router to shadow generation
ADB -> token: commitActivation()
H2: mark old generation FENCED
ADB: health check new generation
ADB: drain and close old generation after rollback window
```

路由切换失败或 drain 超时时，调用 `abortActivation()`恢复旧 generation 的事务准入。

### Generation 路由归属与崩溃恢复

一个 generation 是同一逻辑数据库的一套完整物理实例，至少包含 H2 catalog 路径、相应 participant/LDB 数据路径及其启动描述。`databaseId`标识逻辑数据库，`generationId`标识这套可独立启动、校验、激活和保留的物理实例。

职责边界固定如下：

| 组件 | 负责 | 不负责 |
| --- | --- | --- |
| ADB SQL Server | 生成 generation ID；持久化 generation 描述和 active pointer；执行路由 CAS；恢复未完成切换；控制健康检查、保留和清理。 | 不绕过 H2 的 validate、drain、token 和 fence 协议直接切换。 |
| H2 | 校验当前 generation；有界 quiesce/drain；签发并单次消费 activation token；abort 后恢复准入；commit 后 fence 旧实例。 | 不持久化或选择全局 active generation；不实现 ADB 路由、集群共识和旧 generation 删除策略。 |

ADB 的持久化 generation registry 至少记录：

```text
databaseId
activeGenerationId
previousGenerationId
routeVersion
switchState
activationId
oldGenerationId
newGenerationId
sourceBackupId
cutId
schemaEpoch
updatedAt
```

`activeGenerationId + routeVersion`是路由事实来源和 CAS 条件。`switchState`用于恢复工作流，至少区分 `ACTIVE`、`SWITCH_PREPARED`、`ACTIVE_NEW`、`OLD_RETAINED`和`COMPLETED`；它不能替代 active pointer。

确认后的切换顺序：

1. ADB 为 shadow 分配新的 `generationId`，完成 H2 和 participant 的 validation。
2. ADB 请求旧 generation `prepareActivation(timeout)`；H2 拒绝新事务并排空在途事务后返回 token。
3. ADB 持久化 `SWITCH_PREPARED`，记录 `activationId`、old/new generation 和备份切点。
4. ADB 以 `routeVersion`为条件，原子地把持久化 `activeGenerationId`从 old CAS 为 new，并写入 `ACTIVE_NEW`。
5. ADB 根据已持久化的新 pointer 更新进程内路由；此后新请求只能进入 new generation。
6. ADB 消费 token，H2 将 old generation 置为 `FENCED`；随后执行新 generation 健康检查，并按策略保留旧文件。

activation token 只是存活 H2 进程内的并发协调能力，不是崩溃恢复事实来源。恢复规则只看持久化 active pointer：

- pointer 仍为 old：old 继续作为 active；清理或重试 `SWITCH_PREPARED`，并 abort 尚存 token。不得因为 shadow 可打开而自行切换。
- pointer 已为 new：重启或重连后只把 new 暴露为 active，并继续 fence/停止 old、健康检查和状态收敛；不得因 token 未 commit 回拨到 old。
- new 尚未接受任何写入且切换流程明确失败时，可以在受控流程中 abort 并保持 old；new 一旦接受写入即越过不可逆点，禁止自动回拨到可能已过期的 old。

### 统一 Java API 和 H2 Server 模式

组合备份和影子恢复统一通过 JDBC `unwrap`获取扩展控制接口：

```java
OnlineBackupControl control =
        connection.unwrap(OnlineBackupControl.class);
```

调用方不感知当前连接使用 `SessionLocal`还是`SessionRemote`：

```text
OnlineBackupControl
  -> SessionLocal
       -> direct OnlineBackupCoordinator
  -> SessionRemote
       -> H2 TCP v21
       -> server-side OnlineBackupCoordinator
```

如果 ADB SQL Server 与 H2DB 在同一进程中，直接走 embedded 分支，不经过 TCP。使用 H2 原生 TCP Server 时，由 `SessionRemote`发送 v21 管理操作，snapshot、bundle、shadow generation 和 activation token 都保存在服务器进程中。

TCP v21 规划新增以下逻辑操作；实际数值在实现评审时固定：

```text
ONLINE_BACKUP_PREPARE
ONLINE_BACKUP_MATERIALIZE
ONLINE_BACKUP_ABORT
ONLINE_BACKUP_CLOSE

SHADOW_RESTORE_STAGE
SHADOW_RESTORE_VALIDATE
ACTIVATION_PREPARE
ACTIVATION_COMMIT
ACTIVATION_ABORT
```

远程语义：

- server 为 prepared backup、restore 和 activation token 分配不透明 handle。
- handle 绑定 TCP session、databaseId、用户和创建时的协议版本。
- JDBC 连接正常或异常关闭时，服务端自动 abort 未发布备份和未消费 activation token。
- 自动重连后的新 session 不继承旧 handle；存在活动 handle 时禁止透明自动重连。
- materialize 和 shadow staging 发生在服务器文件系统。
- server 配置 `backupRoot`和`shadowRoot`；客户端只提交经过白名单校验的相对名称。
- participant ID 由服务端配置 allowlist 控制，客户端不能绕过。
- 新客户端与协议版本低于 21 的服务器连接时，`unwrap`可以成功，但调用管理方法必须在本地返回 feature-not-supported，不发送未知操作码。
- 旧客户端连接新服务器继续协商旧版本，普通 JDBC 和 `BACKUP TO`行为不变。

## 接口与包边界规划

| 类型 | 建议位置 | 稳定性 |
| --- | --- | --- |
| participant provider、context、metadata | `org.h2.api` | 实验性公开 SPI，需版本说明。 |
| `OnlineBackupControl`、options、session、result | `org.h2.api`或独立公开 backup 包 | 公开实验性 Java API；不暴露内部 session。 |
| `OnlineBackupCoordinator` | `org.h2.engine.backup` | internal。 |
| `DatabaseOperationGate` | `org.h2.engine`或`org.h2.engine.backup` | internal。 |
| prepared file snapshot | `org.h2.mvstore` | internal，不公开磁盘实现。 |
| bundle writer/reader、manifest codec | `org.h2.tools.backup`或 internal backup 包 | codec 可复用，API 待确认。 |
| shadow restore coordinator | `org.h2.engine.restore` | internal。 |
| embedded adapter | `org.h2.jdbc.JdbcConnection`、`SessionLocal` | `unwrap`公开，adapter internal。 |
| remote adapter | `SessionRemote`、`TcpServerThread`、`Transfer` | TCP v21 internal wire protocol。 |

公共 API 不得暴露 `SessionLocal`、`Database`、MVStore chunk 或 file header 等内部类型。

## 异常处理与错误语义

| 场景 | 行为 |
| --- | --- |
| barrier 等待超时 | 不创建成功 snapshot，释放 gate，返回可诊断 timeout。 |
| 最终 flush 超过 deadline | 完成不可中断的内部操作后 abort，释放 gate，记录 deadline overrun。 |
| participant prepare 失败 | 逆序 abort 已成功 participant，关闭 H2 snapshot，释放 gate。 |
| materialize 中断 | 恢复中断标记，删除或隔离 staging，不发布 final。 |
| checksum 失败 | 审计状态记为 FAILED，final 路径不可见。 |
| shadow 试打开失败 | 保留活动库，shadow 标记为不可激活。 |
| shadow 依赖未认证或非 validation-safe provider | 返回 `UNVALIDATABLE_PROVIDER`并阻止 activation，不按普通模式加载。 |
| drain 超时 | activation 失败并恢复 admission。 |
| router 切换失败 | token abort，旧库恢复 admission。 |
| token 重复消费 | 返回第一次消费结果或明确的非法状态，不重复切换状态。 |
| 临时 quiesce 拒绝新事务 | 返回可重试错误；连接保持有效，activation abort 或超时恢复 admission 后可再次使用。 |
| 旧 generation 已 FENCED | 返回不可恢复的连接错误并使当前连接作废；旧连接不得迁移到新 generation。 |
| 远程连接在 prepared handle 存活时断开 | 服务端自动 abort/close；清理失败记录 suppressed exception 和严重告警。 |
| 服务器协议版本低于 21 | 客户端不发送新操作码，直接返回 feature-not-supported。 |
| 远程路径越过配置根目录 | 在创建 staging 前拒绝，记录安全审计，不泄露服务端绝对路径。 |

已确认的 SQLState 与异常映射：

| 场景 | symbolic vendor code | SQLState | JDBC 异常 | 调用方动作 |
| --- | --- | --- | --- | --- |
| 临时 quiescing，事务尚未准入 | `ONLINE_BACKUP_QUIESCING_1` | `40001` | `SQLTransactionRollbackException` | 回滚当前工作单元并按退避策略重试；连接本身可保留。 |
| generation 已永久 fence | `GENERATION_FENCED_1` | `08006` | `SQLNonTransientConnectionException` | 丢弃旧连接并通过 ADB 路由新建连接，不得在原连接上重试。 |
| activation drain/prepare 超时 | `ONLINE_BACKUP_ACTIVATION_TIMEOUT_1` | `HYT00` | `SQLTimeoutException` | activation 失败；确认 admission 已恢复后决定重试。 |

三个 symbolic code 在实现阶段从未占用的 H2 vendor error code 区间分配整数，并分别在 `ErrorCode.getState()`和`DbException.getJdbcSQLException()`中显式映射。不得复用现有 `DEADLOCK_1`、`LOCK_TIMEOUT_1`或`CONNECTION_BROKEN_1`，即使它们的部分语义或异常类型相近。

公开 Java API 和 TCP v21 必须返回相同的 vendor code、SQLState 和 JDBC 异常类型。操作 report 另外记录稳定 reason：`QUIESCING`、`GENERATION_FENCED`或`ACTIVATION_TIMEOUT`。ADB 以 vendor code/结构化 reason 决策，SQLState 用于标准 JDBC 分类，异常消息只用于人工诊断。

## 幂等性规划

- `backupId`是调用方可提供的操作幂等键；未提供时由 H2DB 生成。
- `cutId`由每次成功进入 prepare 的协调器生成，不能由调用方伪造。
- 同一存活 session 对同一 target 重复 materialize，返回已完成结果或继续该 session 的未完成物化。
- final 已存在且 manifest 的 `backupId`、`databaseId`、`cutId`和参数完全一致时，返回已有结果。
- final 已存在但身份或参数不一致时拒绝覆盖。
- 第一阶段不承诺进程崩溃后继续旧 MVStore snapshot 的物化；崩溃后清理 staging，并使用新的 `backupId/cutId`重新 prepare。
- participant 是否支持跨进程 durable resume 通过未来 capability 扩展，不作为第一阶段接口承诺。
- `abort()`、`close()`和 activation token 消费必须可安全重复调用。

## 回滚策略

- 新组合备份只通过显式 Java API 启用，关闭调用路径即可回到旧 `BACKUP TO`。
- TCP v21 adapter 可独立关闭；embedded API 和旧 JDBC 协议不依赖远程 adapter。
- 新 participant provider 是可选类型；移除配置后旧插件和普通数据库行为不变。
- `h2.onlineBackup.meta`是附加内部 map；旧版本应忽略并保留该 map。
- prepared snapshot 代码回滚后，已有普通 MVStore 文件仍可打开。
- shadow activation 前可直接清理 shadow generation。
- activation 后在健康检查和回滚窗口结束前保留旧 generation 文件。
- new generation 尚未接受写入时，ADB 可取消未完成切换并保持 old 为 active。
- new generation 已接受写入后，不允许把 active pointer 简单回拨到旧 generation；如需恢复旧实现，必须先完成数据对账，或从一致性备份构造另一个新 generation，再按完整 activation 流程切换。
- 无论哪种恢复方式都不允许覆盖活动路径。

## 兼容性矩阵

| 场景 | 预期 |
| --- | --- |
| 未配置 `ONLINE_BACKUP_COORDINATION` | 按 `FALSE`处理；默认 MVStore、事务、DDL 和旧备份路径保持原行为。 |
| `ONLINE_BACKUP_COORDINATION=FALSE` | 不创建 gate、identity map 或 participant，不允许组合备份。 |
| `ONLINE_BACKUP_COORDINATION=TRUE` | Database 打开阶段初始化协调能力，并承担已声明的 gate 和 metadata 开销。 |
| Database 打开后尝试热切换配置 | 明确拒绝，要求关闭并重新打开 Database。 |
| 后续连接配置与已打开 Database 冲突 | 返回配置冲突，不改变现有实例模式。 |
| 新代码打开未含 backup metadata 的旧库 | 仅在协调功能已启用且数据库可写时初始化 `databaseId/schemaEpoch`；功能关闭时不初始化，只读旧库缺少 identity 时拒绝组合备份。 |
| 旧代码打开含 `h2.onlineBackup.meta`的新库 | 忽略未知 map，普通 SQL 和旧备份继续工作。 |
| 2.4.x 新代码打开 2.3.0 数据文件 | 功能关闭时按原行为打开；协调功能启用且可写时初始化 identity metadata，并从此承担降级只读约束。 |
| 已启用协调功能的数据文件降级到 2.3.x | 只允许只读应急打开；禁止 DDL/DML 写入，因为 2.3.x 不维护 `schemaEpoch`。 |
| 2.3.x 生成的传统备份 zip | 2.4.x 的旧 `Restore`路径继续支持；不要求其中存在组合 manifest。 |
| 2.4.x 生成的传统 `BACKUP TO` zip | 格式和旧恢复流程保持不变；若源库已启用协调 metadata，2.3.x 恢复后仍只允许只读应急使用。 |
| 2.4.x 组合目录 bundle | 仅由新 bundle reader/restore API 解释；2.3.x 工具无需识别，也不得将其误当传统 zip。 |
| Gradle 制品版本与 `Constants.VERSION/FULL_VERSION` | 发布和兼容测试前必须一致；manifest、日志和协议报告使用同一规范版本值。 |
| 新代码执行普通 `BACKUP TO` | 权限、返回值、zip 格式和恢复方式不变。 |
| 旧插件未实现 participant provider | 不参与组合备份，无加载错误。 |
| 内存数据库 | 新 API 明确返回不支持物理备份。 |
| 加密数据库 | snapshot 和 shadow validation 必须沿用正确密钥，不在 manifest 中写入密钥。 |
| shadow 依赖自定义 storage/catalog provider | 仅当 provider 在服务端 allowlist 中且显式支持 validation mode 时加载，否则 fail-closed。 |
| shadow 包含非必要业务插件 | validation open 不加载，不执行其 start/stop 或其他 lifecycle 回调。 |
| 旧 provider 未声明 validation capability | 默认视为未认证；不能用普通启动模式完成验证。 |
| 只读数据库已有 identity metadata | 允许组合备份；只读取稳定 `databaseId/schemaEpoch`，不修改原库。 |
| 只读旧库缺少 identity metadata | 明确拒绝组合备份并返回 `IDENTITY_METADATA_REQUIRED`；旧 `BACKUP TO`保持可用。 |
| 缺少 identity 的只读旧库 onboarding | 复制到独立可写 clone，以协调模式生成新 identity，并从该点建立新的备份链；不得冒充原链延续。 |
| embedded connection | `Connection.unwrap(OnlineBackupControl.class)`后直接调用本地 coordinator。 |
| 新客户端连接 TCP v21 server | 使用远程 handle 调用服务端 coordinator。 |
| 新客户端连接 TCP v17-v20 server | 新管理方法返回 feature-not-supported，不发送未知操作码。 |
| 旧客户端连接 TCP v21 server | 协商旧协议版本，普通 JDBC 和旧备份行为不变。 |
| 远程连接自动重连 | 活跃 handle 不允许继承；断线时服务端清理，重连后需重新 prepare。 |

## 总体阶段与依赖

| 阶段 | 目标 | 依赖 | 状态 |
| --- | --- | --- | --- |
| P0 决策与基线 | 关闭阻塞实现的开放问题，固定口径和兼容基线 | 无 | [x] |
| P0.5 Identity Metadata Spike | 用最小原型验证内部事务型 MVStore map 的原子性、持久化和兼容性，再决定 OQ-05 | P0 | [x] |
| P0.6 Snapshot Lease Spike | 验证 prepared snapshot 过期取消、reader 排空和安全解除 reuse-space pin，再决定 OQ-09 | P0 | [x] |
| P1 Operation Gate | 建立 commit、DDL、transaction 准入和指标接缝 | P0/P0.5 | [x] |
| P2 Identity/Epoch | 持久化 databaseId、schemaEpoch，并接收 ADB 提供的运行时 generationId | P0/P1 | [x] |
| P3 MVStore Snapshot | 实现固定切点的 prepared snapshot | P0.6/P1 | [x] |
| P4 Session 与 SPI | 实现组合 session 和 participant prepare/abort | P2/P3 | [x] |
| P5 Bundle Publish | materialize、checksum、manifest 和原子发布 | P4 | [x] |
| P6 Shadow Restore | 安全 staging、验证和只读试打开 | P2/P5 | [x] |
| P7 Activation | 有界排空、token、fence 和取消 | P1/P6 | [x] |
| P8 TCP v21 远程适配 | 统一 unwrap API 的 SessionRemote/TcpServerThread 实现 | P4-P7 | [x] |
| P9 联调与灰度 | ADB/LDB 联调、性能门禁、故障矩阵和发布准备 | P3-P8 | [x] |

## 执行门禁

| 门禁 | 要求 |
| --- | --- |
| G0 设计门禁 | 阻塞 P1-P3 的开放问题已记录明确决策。 |
| G1 JDK 门禁 | 主线代码在 JDK 8 下编译，不使用 Java 9+ API 或语法。 |
| G2 锁序门禁 | barrier 等待不持有 meta/table/store/session 锁；专项死锁测试通过。 |
| G3 切点门禁 | H2 与 fake participant 恢复后的提交序号严格对应同一 cut。 |
| G4 兼容门禁 | 现有 `BACKUP TO`和`Restore`测试结果不变。 |
| G5 清理门禁 | 超时、中断和任意 participant 失败后无 gate、pin、临时文件泄漏。 |
| G6 发布门禁 | manifest、文件落盘和原子移动成功后 final 才可见。 |
| G7 恢复门禁 | shadow 校验失败和 drain 超时均不影响活动库。 |
| G8 激活门禁 | router 失败可以取消；成功后旧 generation 不再接受新事务。 |
| G9 性能门禁 | 约定负载下 prepare P99、最大值和吞吐恢复满足已确认口径。 |
| G10 编译门禁 | 在 `h2/`下执行最窄生产编译验证。 |
| G11 测试门禁 | 每个生产改动阶段同步新增或更新对应测试。 |
| G12 协议兼容门禁 | 新旧 client/server 组合按协商版本工作，旧端不接收未知操作码。 |
| G13 远程清理门禁 | 正常关闭、网络断开、server stop 和自动重连均不遗留远程 handle。 |
| G14 默认存储隔离门禁 | 功能关闭时不创建 gate、metadata map 或 participant，不改变普通 MVStore、事务、DDL、compact、reclamation、`BACKUP TO`和旧 `Restore`的行为及基线性能。 |
| G15 Identity 原型门禁 | OQ-05 只有在事务原子性、重启、失败回滚、物理备份恢复、compact、只读、加密和 2.3.x 往返完成验证，并为旧版本写入和 Recover 缺口形成明确约束后才能确认。 |
| G16 Generation 路由门禁 | ADB 持久化 active pointer 是唯一事实来源；各崩溃点均能按 pointer 收敛，且 new 接受写入后不存在自动回拨 old 的路径。 |
| G17 Participant 灰度门禁 | H2 协议和实现通过多 fake participant 的顺序、共享 deadline 和逆序清理测试；首次生产配置只允许一个已认证真实 participant，放宽数量前重新执行性能与故障门禁。 |
| G18 Validation 副作用门禁 | shadow 只读试打开仅加载 allowlist 内且声明 validation capability 的必要 provider；网络、后台线程、服务注册、迁移和初始化写入均被阻止，未认证依赖 fail-closed。 |
| G19 错误契约门禁 | embedded/TCP 对 quiesce、fence、activation timeout 返回相同 vendor code、SQLState 和 JDBC 异常类型；quiesce/timeout 后连接可恢复，fence 后旧连接永久作废。 |
| G20 只读 Identity 门禁 | 缺少 identity metadata 的只读旧库稳定拒绝组合备份，且不创建 map/sidecar/临时 ID；onboarding 只在独立可写 clone 上生成新 identity 和新备份链。 |
| G21 Snapshot Lease 原型门禁 | OQ-09 只有在 idle expiry、materialize cancellation、reader 排空、cleanup owner 竞争、迟到读取拒绝和卡死 reader 保持 pin 均通过原型验证后才能确认。 |
| G22 版本兼容门禁 | 新能力只在 2.4.x 开发线交付；2.3.0 文件/zip/plugin/TCP 兼容矩阵通过，且发布制品版本、`Constants.VERSION/FULL_VERSION`、manifest 和日志版本一致。 |

## 任务清单

### P0 决策与基线

- [x] 确认统一使用 `Connection.unwrap(OnlineBackupControl.class)`；embedded 直连，H2 server 通过 TCP v21，且不增加组合备份 SQL 语法。
- [x] 确认 prepare 1 秒要求为受信 participant 和约定负载下的可观测性能 SLO；不强杀失控线程。
- [x] 确认第一阶段幂等重试限定为同一进程 session 和已发布结果复用；崩溃后使用新的 `backupId/cutId`。
- [x] 确认组合备份使用目录 bundle；现有 `BACKUP TO`继续使用单个 zip。
- [x] 确认 `ONLINE_BACKUP_COORDINATION`默认关闭、仅在 Database 打开阶段确定且禁止热切换；关闭时隔离于 H2 默认存储路径。
- [x] 确认 OQ-05 必须先完成 P0.5 原型验证，再决定身份元数据的最终存放位置。
- [x] 确认 generation 路由、持久化 active pointer 和崩溃恢复归 ADB SQL Server 所有；H2 只负责 generation-local validate、quiesce、token、abort 和 fence。
- [x] 确认 participant 协议和实现支持多个，但首次生产灰度只允许一个真实外部 participant。
- [x] 确认 shadow validation 只加载白名单内、明确支持 validation mode 的必要 provider；非必要业务插件不加载，未认证必要依赖 fail-closed。
- [x] 确认 quiesce、fence 和 activation timeout 的 SQLState、独立 vendor code、JDBC 异常类型及连接生命周期。
- [x] 确认缺少 identity metadata 的只读旧库拒绝组合备份；通过独立可写 clone onboarding 时建立全新 identity 和备份链。
- [x] 确认 OQ-09 必须先完成 P0.6 并发原型验证，再决定 prepared snapshot 超时后的自动回收策略。
- [x] 根据 P0.6 结果确认 idle 自动 abort、materializing 协作取消、reader 排空后 unpin，以及卡死 reader 保持 pin并熔断。
- [x] 确认当前 `2.4.0-SNAPSHOT`分支实现、新能力不回移 2.3.x，并将 2.3.0 固定为兼容性输入基线。
- [x] 记录 Gradle `2.4.0-SNAPSHOT`与运行时 `Constants.VERSION=2.3.0`的版本元数据差异，并将发布前消除纳入 G22/P9。
- [x] 对现有 `TestBackup`、`TestMVStoreConcurrent`、`TestOpenClose`和 plugin 测试建立变更前基线。

验收：

- [x] 所有 P0 决策写入本文“开放问题与决策记录”。
- [x] 没有仍会改变 P1-P3 核心接口或存储语义的未决问题。

基线命令：

```powershell
.\gradlew.bat legacyTestClasses
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
```

基线结果：

| 范围 | 结果 |
| --- | --- |
| `TestBackup` | 通过。 |
| `TestOpenClose` | 通过。必须使用仓库 `LegacyTestGroupRunner`提供的 `REGULAR`默认 mode；直接执行其 main 会因 legacy `IDENTITY`语法缺少 mode 配置而失败，不作为正式基线入口。 |
| `TestMVStoreConcurrent` | 通过。 |
| plugin 测试集 | 139 项通过，0 failure、0 error、0 skipped；其中既有测试 133 项，P0.6 新增原型 6 项。 |

### P0.5 Identity Metadata Spike

原型只验证 `h2.onlineBackup.meta`候选方案，不引入公开 JDBC API、TCP 协议、participant、bundle 或 shadow restore，也不视为正式磁盘格式承诺。

- [x] 验证 `databaseId/schemaEpoch`在关闭、重启和重复打开后稳定。
- [x] 验证 catalog 修改与 `schemaEpoch`在同一个 H2 transaction 中共同提交或回滚。
- [x] 验证成功 catalog 事务和 4 路并发 DDL 无丢失递增；同时确认现有 `isDdl`事件不足以排除未修改 catalog 的失败 DDL。
- [x] 注入 metadata 写入后、transaction commit 前的失败，验证 catalog 与 epoch 不出现半提交。
- [x] 验证物理 `BACKUP TO`、`Restore`和 shadow 文件复制保留 map。
- [x] 验证当前及 2.3.x `SHUTDOWN COMPACT`保留 map；确认逻辑 `Recover`脚本当前不会重建未知 map。
- [x] 验证加密数据库和只读数据库已有 map 时可读取，缺少 map 时不写原库。
- [x] 验证当前代码创建 map 后，2.3.x 打开、关闭和 compact 不删除或改写 map。
- [x] 验证 2.3.x 执行 DDL 后 `schemaEpoch`不会递增；决定已启用协调功能的数据库不支持降级写入。
- [x] 验证原型关闭时不创建 map；正式实现仍需在 G14 下验证无额外 commit/chunk 和性能回归。

验收：

- [x] `T-H2BR-IDENTITY-SPIKE-TRANSACTION-01`
- [x] `T-H2BR-IDENTITY-SPIKE-FAILURE-01`
- [x] `T-H2BR-IDENTITY-SPIKE-REOPEN-01`
- [x] `T-H2BR-IDENTITY-SPIKE-BACKUP-RESTORE-01`
- [x] `T-H2BR-IDENTITY-SPIKE-COMPACT-01`
- [x] `T-H2BR-IDENTITY-SPIKE-READONLY-01`
- [x] `T-H2BR-IDENTITY-SPIKE-ENCRYPTED-01`
- [x] `T-H2BR-IDENTITY-SPIKE-23X-ROUNDTRIP-01`
- [x] `T-H2BR-IDENTITY-SPIKE-FEATURE-OFF-01`

原型结论：

| 项目 | 结果 |
| --- | --- |
| 独立事务型 map | 可行；metadata 写入可加入当前 DDL 的 TransactionStore transaction。 |
| 失败原子性 | metadata 写入后、commit 前抛错，重启后 catalog 和 epoch 均未半提交。 |
| 物理生命周期 | 重启、文件复制、`BACKUP TO`、`Restore`和 compact 均保留 identity/epoch。 |
| 2.3.x 往返 | 打开、关闭和 compact 保留未知 map；2.3.x DDL 不会递增 epoch，因此禁止降级写入。 |
| Recover | 当前逻辑恢复脚本不重建未知 map，正式实现必须扩展或定义灾难恢复后生成新 identity。 |
| DDL 变更判断 | 现有 transaction event 只能报告 DDL commit，不能证明 catalog 实际改变；正式实现需要成功路径的 session 级标记。 |
| 默认关闭 | 原型禁用时未创建 map；生产实现仍需执行 G14 性能和额外提交门禁。 |

验证命令：

```powershell
.\gradlew.bat runPluginArchitectureCheck --tests org.h2.test.plugin.OnlineBackupIdentityMetadataPrototypeTest
.\gradlew.bat runPluginArchitectureCheck
```

暂停条件：

- catalog 与 epoch 无法在同一个 TransactionStore commit 中原子落盘。
- 旧版本正常打开、关闭或 compact 会删除、改写未知 map，或因该 map 拒绝打开数据库。
- 功能关闭仍会产生持久化 map、额外提交或磁盘格式变化。
- 原型必须修改 MVStore 文件头或公开接口才能完成验证。

### P0.6 Snapshot Lease Spike

原型只验证 prepared snapshot lease 的并发状态和 MVStore reuse-space pin 释放时序，不实现正式 snapshot header/copyLength 捕获、不修改生产代码，也不视为正式 API。

- [x] 验证 `PREPARED`空闲 lease 到期后，watchdog 可通过单 cleanup owner 自动 abort 并恢复原 reuse-space 设置。
- [x] 验证 `MATERIALIZING`期间 lease 到期只设置 cancel request，不中断线程、不提前解除 pin。
- [x] 验证 materializer 协作退出且最后一个 active reader 离开后，才允许解除 pin。
- [x] 验证 owner close、显式 abort 和 watchdog 并发竞争时 cleanup 只执行一次。
- [x] 验证 expiry/abort 线性化后拒绝迟到的 materialize。
- [x] 验证 reader 卡死时 pin 保持、后续 snapshot 被拒绝，并暴露需要严重告警/受控重启的状态。
- [x] 验证 snapshot 存活期间 compaction/reclamation guard 不允许进入，cleanup 后恢复。

验收：

- [x] `T-H2BR-LEASE-SPIKE-IDLE-EXPIRY-01`
- [x] `T-H2BR-LEASE-SPIKE-MATERIALIZE-CANCEL-01`
- [x] `T-H2BR-LEASE-SPIKE-CLEANUP-RACE-01`
- [x] `T-H2BR-LEASE-SPIKE-LATE-READER-01`
- [x] `T-H2BR-LEASE-SPIKE-STUCK-READER-01`
- [x] `T-H2BR-LEASE-SPIKE-MAINTENANCE-GUARD-01`

原型结论：

| 项目 | 结果 |
| --- | --- |
| idle expiry | 可行；无 reader 时 expiry 可取得 cleanup 所有权并恢复原 `reuseSpace`值。 |
| materialize expiry | 可行；expiry 只发布 cancellation，未中断 materializer，真实 MVStore 仍保持 `reuseSpace=false`。 |
| reader drain | 可行；最后一个 reader 退出后才执行一次 cleanup 并解除 pin。 |
| cleanup race | 可行；32 个并发 expiry/abort/close 请求最终只有一次 cleanup。 |
| late reader | 可行；expiry 线性化后拒绝新的 materialize。 |
| stuck reader | 安全但不可自动释放；必须继续持有 pin、拒绝新 snapshot/maintenance，并告警升级。 |
| maintenance guard | 状态协议可阻止 snapshot 存活期间进入 compact/reclamation；正式实现仍需接入真实 maintenance 入口。 |

原型文件：

`h2/src/test-plugin/org/h2/test/plugin/OnlineBackupSnapshotLeasePrototypeTest.java`

验证命令：

```powershell
.\gradlew.bat runPluginArchitectureCheck --tests org.h2.test.plugin.OnlineBackupSnapshotLeasePrototypeTest --rerun-tasks
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
```

验证结果：目标原型 6 项全部通过；完整 plugin 测试集 139 项全部通过，0 failure、0 error、0 skipped。

建议据此确认的正式策略：

- `PREPARED`且无 reader 时允许 watchdog 自动 abort。
- `MATERIALIZING`时 watchdog 只请求协作取消，不跨线程强制 close，也不依赖 `Thread.interrupt()`。
- reuse-space pin、compact/reclamation guard 和新 snapshot 排他权只在最后一个 reader 退出后释放。
- expiry、显式 abort、session close 和 TCP disconnect 共享同一幂等状态机与唯一 cleanup owner。
- 卡死 reader 继续持有 pin并触发严重告警/熔断；不得为恢复服务而不安全 unpin，最终通过受控停止或重启清理。

暂停条件：

- watchdog 必须在 active reader 尚未退出时解除 reuse-space pin 才能回收。
- cleanup 无法建立唯一所有者，存在重复恢复 reuse-space 或重复 participant abort。
- 卡死 reader 场景只能通过强杀线程或不安全 unpin 才能继续运行。

### P1 DatabaseOperationGate

- [x] 在 Database 打开阶段解析并固定 `ONLINE_BACKUP_COORDINATION`，默认值为 `FALSE`，不提供运行期 `SET`或管理 API 热切换。
- [x] 后续连接配置与已打开 Database 冲突时明确失败，不改变现有实例模式。
- [x] 仅在功能启用时创建内部 gate 状态机、deadline 和 counter。
- [x] 功能关闭时，commit/DDL/transaction 路径至多执行一个可预测的空值或布尔分支，不增加锁、计数或资源生命周期。
- [x] 功能启用时，在 `SessionLocal.commit()`最外层包围 transaction provider 与 H2 commit。
- [x] 功能启用时，在非事务型 DDL 修改 catalog 前后进入和退出 DDL gate。
- [x] 功能启用时，在 transaction begin/end 路径增加 restore quiesce 所需计数。
- [x] 明确 system/lob session 的 bypass 规则和关闭流程。
- [x] 确保等待使用 `Condition`或等价机制，不轮询 `Thread.sleep()`。
- [x] 增加 prepare、drain、waiter、timeout 和失败原因的只读指标快照。

验收：

- [x] `T-H2BR-GATE-COMMIT-01`
- [x] `T-H2BR-GATE-DDL-01`
- [x] `T-H2BR-GATE-TIMEOUT-01`
- [x] `T-H2BR-GATE-INTERRUPT-01`
- [x] `T-H2BR-GATE-DEADLOCK-01`
- [x] `T-H2BR-GATE-CLOSE-01`
- [x] `T-H2BR-BEGIN-DRAIN-RACE-01`
- [x] `T-H2BR-FEATURE-DISABLED-01`
- [x] `T-H2BR-FEATURE-ENABLED-OPEN-01`
- [x] `T-H2BR-FEATURE-CONFLICT-01`
- [x] `T-H2BR-FEATURE-HOT-SWITCH-REJECT-01`
- [x] `T-H2BR-DEFAULT-MVSTORE-REGRESSION-01`

实现结论：

- `DatabaseOperationGate`使用公平 `ReentrantLock`和`Condition`实现 `OPEN`、`BACKUP_BARRIER`、`TRANSACTION_DRAIN`和`CLOSED`状态；超时或中断恢复准入并保留线程中断标记。
- JDBC statement、prepared statement 和 TCP server 在取得`SessionLocal` monitor 前进入命令准入；`Command`内部保留可重入准入，覆盖 JDBC 之外的直接执行路径。专项测试实际取得等待连接的 session monitor，证明 barrier 等待不持有该 monitor。
- system session 和 lob session 不绕过 transaction/commit 计数；它们与用户 session 共用`SessionLocal`接缝。正常关闭在内部 session 完成提交和关闭后 shutdown gate，异常 power-off 也 shutdown gate 并唤醒等待者。
- 功能关闭时`Database`不创建 gate，`Command`不创建 gate admission `ThreadLocal`，transaction 和 commit 路径只执行 nullable gate 分支。
- 指标快照包括状态、active/waiting 数量、barrier/drain 次数、timeout 次数、最近/最长等待时间和结构化最后失败原因。

验证命令：

```powershell
.\gradlew.bat runOnlineBackupCheck --rerun-tasks
.\gradlew.bat javadoc
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
.\gradlew.bat legacyTestClasses
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
```

验证结果：

| 范围 | 结果 |
| --- | --- |
| P1 专项 | `DatabaseOperationGateTest` 8 项通过，0 failure、0 error、0 skipped。 |
| JDK 8 / Javadoc | `compileJava`、`compileOnlineBackupTestJava`和`javadoc`通过。 |
| plugin 回归 | 139 项通过，0 failure、0 error、0 skipped。 |
| 传统备份与默认 MVStore | `TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。 |

暂停条件：

- gate 与 meta/table/store 锁无法形成可证明的单向锁序。
- system/lob session bypass 会允许 barrier 内出现未计数的持久化提交。

### P2 Database Identity 与 Schema Epoch

- [x] 仅在 `ONLINE_BACKUP_COORDINATION=TRUE`时增加内部事务型 metadata map。
- [x] 功能启用时为新库生成稳定 `databaseId`，为可写旧库初始化 identity/epoch。
- [x] 功能关闭时不创建、读取或更新在线备份 metadata map。
- [x] 只读旧库缺少 identity metadata 时以稳定 `ONLINE_BACKUP_IDENTITY_REQUIRED`错误拒绝组合备份，不修改原库，不创建 sidecar，不接受调用方 identity。
- [x] 已有 identity metadata 的只读库可取得完整 identity snapshot 且打开过程不写 metadata；P4/P5 继续验证 prepare/materialize 全程只读。
- [x] 固定独立可写 clone onboarding 语义：各 clone 生成新 identity/epoch，并显式开启新的备份链。
- [x] 加密数据库的 metadata map 使用数据库文件自身的加密上下文，不创建明文 sidecar。
- [x] 在 catalog 确实改变后设置可回滚的 session 级 catalog-changed 标记；禁止仅按 `TransactionContext.isDdl()`递增。
- [x] 在 catalog 实际修改事务中每事务递增一次 `schemaEpoch`，并对并发提交后的内存发布做单调保护。
- [x] 验证 DDL 回滚不递增 epoch，重启后 epoch 保持。
- [x] 固定逻辑 `Recover`灾难恢复生成新 `databaseId`并使旧备份链失效的语义。
- [x] 固定降级契约：已启用协调功能的数据库在 2.3.x 下只允许只读应急打开。2.4.x 无法反向约束已发布的 2.3.x 二进制，P9 的 ADB 启动策略必须阻止降级写入。
- [x] 接收并校验 ADB 提供的实例级 `generationId`；不得把它写入会随物理备份复制的 `h2.onlineBackup.meta`。
- [x] `DatabaseIdentityMetadata.Snapshot`提供 `databaseId/generationId/schemaEpoch`统一载体，并提供 generation 一致性校验；P6/P7 将其写入 validation report 和 activation token。
- [x] 验证 2.3.0 只读打开会忽略并保留附加 map，新代码再次打开仍读取原身份。

验收：

- [x] `T-H2BR-DATABASE-ID-01`
- [x] `T-H2BR-SCHEMA-EPOCH-COMMIT-01`
- [x] `T-H2BR-SCHEMA-EPOCH-ROLLBACK-01`
- [x] `T-H2BR-SCHEMA-EPOCH-REOPEN-01`
- [x] `T-H2BR-METADATA-OLD-VERSION-01`
- [x] `T-H2BR-GENERATION-RUNTIME-ID-01`
- [x] `T-H2BR-READONLY-IDENTITY-PRESENT-01`
- [x] `T-H2BR-READONLY-IDENTITY-MISSING-REJECT-01`
- [x] `T-H2BR-READONLY-NO-SIDECAR-OR-TEMP-ID-01`
- [x] `T-H2BR-IDENTITY-ONBOARDING-NEW-LINEAGE-01`

实现结果：

- 新增 `DatabaseIdentityMetadata`，使用事务型 `h2.onlineBackup.meta` map 保存 `databaseId/schemaEpoch`；`generationId`是启动期必填的运行时 UUID，不持久化到数据库文件。
- epoch 写入与 catalog 修改使用同一事务。实际 meta row 变更设置 session 标记，失败 DDL 和 savepoint 回滚会撤销标记；普通 DML、identity sequence 更新、失败或 no-op DDL 不误增。
- 多语句 SQL 和 `EXECUTE IMMEDIATE`只为内层动态 DDL补充可重入 DDL gate，不引入额外 command lifecycle 或 commit，保持原提交边界。
- 一个 SQL（例如 `ALTER TABLE`）可能产生多个真实 catalog 事务，因此可能递增多个 epoch；保证的是“每个实际 catalog 修改事务最多一次”，不是“每条 SQL 恰好一次”。
- 只读旧库缺 map 时保持 metadata unavailable，调用 `requireSnapshot()`返回稳定 vendor code `90158`；已有 map 的只读库可读取 identity。
- 物理副本保留 `databaseId`；两个不含 identity 的可写 clone 各自生成不同 lineage；逻辑 `Recover`重建也生成新 lineage。

验证命令：

```powershell
.\gradlew.bat runOnlineBackupCheck --rerun-tasks
.\gradlew.bat javadoc
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
.\gradlew.bat legacyTestClasses
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
```

验证结果：

| 范围 | 结果 |
| --- | --- |
| P1 + P2 专项 | 16 项通过，0 failure、0 error、0 skipped；其中 P2 `DatabaseIdentityMetadataTest` 8 项。 |
| JDK 8 / Javadoc | `compileJava`、`compileOnlineBackupTestJava`和`javadoc`通过。 |
| plugin 回归 | 139 项通过，0 failure、0 error、0 skipped。 |
| 传统备份与默认 MVStore | `TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。 |

### P3 MVStore Prepared Snapshot

- [x] 在 MVStore store lock 内执行最终 flush、capture 和 reuse-space pin。
- [x] 固定 raw header blocks、copy length、snapshot version 和 SHA-256 源 fingerprint；加密库同时捕获物理加密头。
- [x] 在 materialize 期间阻止 compact、reclamation 和并发 prepared snapshot，并在真正持有 store lock 的 rewrite 临界区二次检查。
- [x] 只复制 `afterLastBlock`固定高水位以内的数据，并使用捕获 header 生成目标文件；不把预分配尾部误纳入 copyLength。
- [x] 对成功、失败、中断、超时和重复 close 恢复空间复用，失败物化删除未完成目标。
- [x] 实现 snapshot lease、绝对单调 deadline、active reader 计数和唯一 cleanup owner。
- [x] lease 在 idle `PREPARED`状态过期时由 daemon watchdog 自动 abort；取消 task 从调度队列及时移除。
- [x] lease 在 `MATERIALIZING`状态过期时只设置 cancel request；复制循环每 64 KiB 协作检查，不依赖 `Thread.interrupt()`。
- [x] 仅在 active reader 降为零后恢复 reuse-space、compact/reclamation 和新 snapshot 准入。
- [x] 显式 abort、重复 close、store/session shutdown 和 watchdog 共享同一幂等 cleanup 状态机；P8 将 TCP handle close 接到同一 `close()`。
- [x] reader 卡死时保持 pin并熔断新 snapshot/maintenance；暴露文件增长、pin 存活时间、reader owner 和受控重启建议，供 P4/P9 报告与告警消费。
- [x] 验证 prepare 后持续提交可与固定高水位复制并行；源文件可控增长且 close 后恢复 reuse-space。

验收：

- [x] `T-H2BR-SNAPSHOT-FIXED-CUT-01`
- [x] `T-H2BR-SNAPSHOT-CONCURRENT-WRITE-01`
- [x] `T-H2BR-SNAPSHOT-HEADER-RACE-01`
- [x] `T-H2BR-SNAPSHOT-CLOSE-IDEMPOTENT-01`
- [x] `T-H2BR-SNAPSHOT-RECLAIM-CONFLICT-01`
- [x] `T-H2BR-SNAPSHOT-FILE-GROWTH-01`
- [x] `T-H2BR-SNAPSHOT-LEASE-IDLE-EXPIRY-01`
- [x] `T-H2BR-SNAPSHOT-LEASE-MATERIALIZE-CANCEL-01`
- [x] `T-H2BR-SNAPSHOT-LEASE-CLEANUP-RACE-01`
- [x] `T-H2BR-SNAPSHOT-LEASE-STUCK-READER-01`

实现结果：

- 新增 `MVStorePreparedSnapshot`；Database 在 P1 backup barrier 内完成最终 flush 和 capture 后立即恢复 commit/DDL 准入，长时间复制不持有 database/store lock。
- copyLength 使用“可选加密头 + `freeSpace.afterLastBlock`”，不使用可能包含预分配空洞的 `FileChannel.size()`；物化时首部使用 capture 时的 raw bytes，其余区域只读固定高水位。
- 同一 MVStore 同时只允许一个 prepared snapshot。reuse-space pin 同时阻止 compact、reclamation 和第二个 snapshot；所有释放路径在最后一个 reader 退出后线性化。
- watchdog 使用惰性初始化的单 daemon scheduler；功能关闭且从未 prepare 时不创建线程。过期不 interrupt reader，只把状态转为 `CANCEL_REQUESTED`。
- 加密、只读 identity-present 数据库均可物化；加密目标可用原密码重开，只读源文件在 prepare/materialize/close 前后逐字节一致。

验证命令：

```powershell
.\gradlew.bat runOnlineBackupCheck --rerun-tasks
.\gradlew.bat javadoc
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
```

验证结果：

| 范围 | 结果 |
| --- | --- |
| P1-P3 专项 | `runOnlineBackupCheck` 26 项通过，0 failure、0 error、0 skipped；P3 新增 10 项。 |
| JDK 8 / Javadoc | `compileJava`、`compileOnlineBackupTestJava`和`javadoc`通过。 |
| plugin 回归 | 139 项通过，0 failure、0 error、0 skipped。 |
| 传统备份与默认 MVStore | `TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。 |

暂停条件：

- 不能证明 capture 后 `copyLength`以内除 header 外的数据保持不可变。
- prepared snapshot 必须长期持有 MVStore store lock 才能复制。
- 关闭空间复用导致的文件增长无法通过生命周期和容量门禁控制。

### P4 OnlineBackupSession 与 Participant SPI

- [x] 冻结 context、options、prepared metadata、artifact target 和 materialized artifact 集合模型。
- [x] 所有 participant API 和 session 状态使用集合模型，不引入单 participant 特例；P5 manifest 直接消费同一有序集合。
- [x] 在 `PluginSecurity`中增加受控 `online_backup_participant` provider 类型和 `onlineBackup.prepare` capability。
- [x] 只调用 `OnlineBackupOptions`显式选择的 provider；空列表表示 H2-only，不自动扫描或调用其他 provider。
- [x] 在进入 barrier 前完成 provider 存在性、重复 ID、接口类型和 capability 检查。
- [x] 在 barrier 内按 participant ID 的稳定顺序依次调用 prepare，H2 snapshot 和所有 participant 共享同一个绝对总 deadline。
- [x] 失败时按实际完成顺序逆序 abort，并将全部清理异常按发生顺序作为 suppressed exception 保留。
- [x] 定义 `PREPARING/PREPARED/ABORTING/ABORTED/CLOSED`状态机和 `abort()/close()`幂等语义。
- [x] Database 生命周期持有且限制每库一个 prepared session；显式关闭、prepare 失败、Database shutdown 和 power-off 使用同一清理路径。

验收：

- [x] `T-H2BR-PARTICIPANT-ORDER-01`
- [x] `T-H2BR-PARTICIPANT-FAILURE-CLEANUP-01`
- [x] `T-H2BR-PARTICIPANT-DUPLICATE-ID-01`
- [x] `T-H2BR-PARTICIPANT-EXPLICIT-SELECTION-01`
- [x] `T-H2BR-PARTICIPANT-MULTI-ORDER-01`
- [x] `T-H2BR-PARTICIPANT-SHARED-DEADLINE-01`
- [x] `T-H2BR-PARTICIPANT-MULTI-REVERSE-ABORT-01`
- [x] `T-H2BR-SESSION-STATE-01`

实现结果：

- 新增 `OnlineBackupParticipantProvider`、`PreparedBackupParticipant`、`OnlineBackupContext`、`OnlineBackupOptions`、prepared metadata 和 materialized artifact API；participant provenance 同时保留 plugin ID/version。
- 解析阶段只读取显式 ID，拒绝空值、重复、缺失、错误接口和未声明 capability 的 provider；完成全部校验后才 claim 每库唯一 session 并进入 barrier。
- context 固定 `backupId/cutId/databaseId/generationId/schemaEpoch/deadlineNanos`，所有 provider 接收同一实例；后调用者看到的是同一 deadline 的剩余预算。
- provider prepare、metadata 校验或 deadline 检查任一失败时，已完成 participant 逆序 abort，最后关闭 H2 snapshot。清理失败不覆盖主异常。
- session monitor 覆盖完整 prepare 状态转换；Database shutdown 与 prepare 并发时会等待当前回调结束后清理，不会在 close 后追加资源。

验证命令：

```powershell
.\gradlew.bat runOnlineBackupCheck --rerun-tasks
.\gradlew.bat javadoc
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
```

验证结果：

| 范围 | 结果 |
| --- | --- |
| P1-P4 专项 | `runOnlineBackupCheck` 32 项通过，0 failure、0 error、0 skipped；P4 新增 6 项。 |
| JDK 8 / Javadoc | `compileJava`、`compileOnlineBackupTestJava`和`javadoc`通过。 |
| plugin 回归 | 140 项通过，0 failure、0 error、0 skipped；新增 participant provider 安全边界测试。 |
| 传统备份与默认 MVStore | `TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。 |

### P5 Bundle、Manifest 与原子发布

- [x] 实现同父目录 staging 和 final 路径冲突检查；staging 以 `backupId`确定命名，同一幂等操作重试前清理其未发布 staging。
- [x] 为每个 participant 分配 Base64URL 编码的独立相对 artifact root；拒绝绝对路径、盘符、空分段、`.`、`..`、反斜杠逃逸、符号链接和未关闭输出流。
- [x] materialize H2 和 participant artifacts；participant 返回的路径集合必须与实际创建的文件集合完全一致。
- [x] 计算长度、SHA-256 和稳定排序的 artifact 列表，所有 artifact 在写 manifest 前执行文件 `force(true)`。
- [x] 实现 `formatVersion=1`的 UTF-8 JSON codec、稳定字段顺序、严格必需字段类型校验和未知字段兼容。
- [x] 将运行审计状态与不可变 published manifest 分离；final manifest 只允许 `PUBLISHED`，审计文件独立记录 `MATERIALIZING`、`FAILED`或`PUBLISHED`。
- [x] 文件 fsync 和 atomic move 是成功硬条件；目录 fsync 成功时记录 `SUCCEEDED`，Windows 等平台明确拒绝目录句柄时降级为 `UNSUPPORTED`并写入审计，不把降级伪装为已 fsync。
- [x] 处理同 session 重复发布、并发同 cut 发布复用，以及 final 身份、cut 或 participant 参数冲突拒绝；不覆盖既有 final。

验收：

- [x] `T-H2BR-MANIFEST-ROUNDTRIP-01`
- [x] `T-H2BR-MANIFEST-UNKNOWN-FIELD-01`
- [x] `T-H2BR-BUNDLE-CHECKSUM-01`
- [x] `T-H2BR-BUNDLE-ATOMIC-PUBLISH-01`
- [x] `T-H2BR-BUNDLE-PUBLISH-FAILURE-01`
- [x] `T-H2BR-BUNDLE-IDEMPOTENT-01`

实现结果：

- `OnlineBackupOptions`可接收调用方 `backupId`；未提供时仍由 H2 生成。`cutId`始终由每次 prepare 生成。
- `OnlineBackupSession.publish(Path)`在持有 session 生命周期锁时调用 bundle publisher；相同 target 重复调用返回同一完成结果，其他 target 被拒绝。
- bundle 固定包含 `manifest.json`、`h2/database.mv.db`和零到多个 `participants/<encoded-id>/...`。
- participant artifact 输出使用受限 target：每个文件以 `CREATE_NEW`创建，关闭时强制落盘；回调返回前仍有打开流会失败并由 H2 关闭。
- manifest 记录 format/status、backup/cut/database/generation identity、schema epoch、H2 版本、prepare pause、H2 artifact 以及稳定排序的 participant provenance、cut metadata 和 artifacts。
- 发布失败只删除本次确定 staging，不删除 final；final 原子移动完成后才对调用方返回成功。

验证命令：

```powershell
.\gradlew.bat runOnlineBackupCheck --tests org.h2.test.backup.OnlineBackupBundlePublisherTest --rerun-tasks
.\gradlew.bat runOnlineBackupCheck --rerun-tasks
.\gradlew.bat javadoc
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
.\gradlew.bat legacyTestClasses
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
```

验证结果：

| 范围 | 结果 |
| --- | --- |
| P5 专项 | `OnlineBackupBundlePublisherTest` 7 项通过，0 failure、0 error、0 skipped。 |
| P1-P5 专项 | `runOnlineBackupCheck` 39 项通过，0 failure、0 error。 |
| JDK 8 / Javadoc | `compileJava`、`compileOnlineBackupTestJava`和`javadoc`通过。 |
| plugin 回归 | 140 项通过，0 failure、0 error、0 skipped。 |
| 传统备份与默认 MVStore | `TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。 |

### P6 Shadow Restore 与 Validate

- [x] 新增独立 `ShadowRestoreCoordinator.stageAndValidate()` API，不修改旧 `Restore.execute()`行为。
- [x] 校验 manifest、artifact path、长度和 SHA-256；拒绝未声明、重复、缺失和非普通文件。
- [x] 拒绝 zip-slip/path traversal、Windows ADS/保留分隔符、symlink/junction、bundle root link 和 shadow root 逃逸。
- [x] 恢复到全新 staging 和 shadow generation，不覆盖已有目录；验证报告强制落盘后才执行同父目录 atomic move。
- [x] 增加只读 `OnlineBackupValidationContext`和`ParticipantArtifactSource`；二者不暴露活动 generation 路径、生产 endpoint、scheduler 或可写句柄。
- [x] 从 manifest 和显式启动选择解析必要 provider，校验类型、ID、插件 ID、版本、`validation.open` capability 和 allowlist。
- [x] validation open 注册 builtin provider 和显式选择的必要 provider，跳过 `ServiceLoader`；participant 必须精确匹配 manifest 和 allowlist。
- [x] 建立第一阶段副作用隔离边界：validation Database 强制只读、不注册退出钩子、不扫描普通插件、不执行 trigger 或 Database lifecycle；provider 的网络/线程安全仍由 capability 认证契约保证，不宣称存在 JVM 强制沙箱。
- [x] 对缺失、未认证、版本不兼容或不在 allowlist 内的必要 provider 返回独立 `UNVALIDATABLE_PROVIDER`错误码 90160，禁止普通模式或反射类名兜底。
- [x] 验证 validation open 和 close 均不触发普通业务 trigger/Database lifecycle；catalog 确实依赖 Java alias 时 fail-closed。
- [x] 只读试打开 shadow Database，查询 `INFORMATION_SCHEMA`并验证 storage engine、`databaseId`、`generationId`和`schemaEpoch`。
- [x] 失败时 final 始终不可见；写入 FAILED 诊断报告，并按 `keepFailedShadow`选择保留或清理 staging。

验收：

- [x] `T-H2BR-RESTORE-PATH-SAFETY-01`
- [x] `T-H2BR-RESTORE-CHECKSUM-01`
- [x] `T-H2BR-RESTORE-READONLY-OPEN-01`
- [x] `T-H2BR-RESTORE-CATALOG-VALIDATE-01`
- [x] `T-H2BR-VALIDATION-PROVIDER-ALLOWLIST-01`
- [x] `T-H2BR-VALIDATION-LEGACY-PROVIDER-REJECT-01`
- [x] `T-H2BR-VALIDATION-NO-LIFECYCLE-SIDE-EFFECT-01`
- [x] `T-H2BR-VALIDATION-NO-NETWORK-OR-THREAD-01`
- [x] `T-H2BR-VALIDATION-REQUIRED-PROVIDER-01`
- [x] `T-H2BR-RESTORE-ACTIVE-UNCHANGED-01`

实现结果：

- `OnlineBackupManifest`在 format v1 中增加 `storageEngineId`和稳定排序的 `requiredProviders`来源信息；decoder 对缺少这些新增字段的既有 v1 bundle 保持 builtin MVStore 兼容。
- `BackupBundleVerifier`先验证完整 bundle 文件树，再以 `CREATE_NEW`复制到 staging；每个文件同时校验长度和 SHA-256 并执行 `force(true)`。
- `ShadowRestoreOptions`携带新 generation ID、participant 精确 allowlist、额外必要 core provider 选择、共享 validation deadline、失败保留策略和仅在内存中使用的解密参数。
- `ValidationProviderRegistry`使用一次性线程绑定 scope；validation Database 只能消费一次，直接在 JDBC URL 中启用内部 validation mode 会以 90160 失败。
- validation Database 强制 `ACCESS_MODE_DATA=r`、`IFEXISTS=TRUE`和协调模式，跳过 `ServiceLoader`、临时文件清理、VM shutdown 注册及普通关闭 lifecycle。
- builtin storage/catalog/table provider 显式声明 `validation.open`；自定义必要 provider 必须同时满足来源版本、capability 和显式 allowlist。
- participant validation 只获得身份/epoch 上下文以及 manifest 已声明 artifact 的稳定只读流；旧 participant 即使进入 allowlist，也会因缺少 `onlineBackup.validate` capability 而 fail-closed。
- trigger 定义在 validation open 中保留 catalog 元数据但不加载业务类；Java alias 被实际解析时返回 90160；旧 table engine 反射兜底在 validation mode 中被禁止。
- 成功路径在 shadow 内保存不可变 `backup-manifest.json`和强制落盘的 `validation-report.json`；旁路审计独立记录阶段和目录 fsync 结果。
- 加密数据库由调用方提供 cipher/password 完成只读验证；manifest、validation report 和 audit 均不持久化密钥。
- capability 是进入 validation 的安全认证信号，不是对恶意 provider 的 JVM 沙箱。P9 仍须建立真实 provider 认证清单并验证网络、线程、服务注册和外部副作用契约。

验证命令：

```powershell
.\gradlew.bat compileJava compileOnlineBackupTestJava
.\gradlew.bat runOnlineBackupCheck --tests org.h2.test.backup.ShadowRestoreCoordinatorTest --rerun-tasks
.\gradlew.bat runOnlineBackupCheck --rerun-tasks
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
.\gradlew.bat javadoc
.\gradlew.bat legacyTestClasses
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
```

验证结果：

| 范围 | 结果 |
| --- | --- |
| P6 专项 | `ShadowRestoreCoordinatorTest` 13 项通过，0 failure、0 error、0 skipped。 |
| P1-P6 专项 | `runOnlineBackupCheck` 52 项通过，0 failure、0 error、0 skipped。 |
| JDK 8 / Javadoc | `compileJava`、`compileOnlineBackupTestJava`和`javadoc`通过。 |
| plugin 回归 | 140 项通过，0 failure、0 error、0 skipped。 |
| 传统备份与默认 MVStore | `TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。 |

### P7 Activation、Drain 与 Fence

- [x] 在 transaction begin 和 DDL admission 路径拒绝 quiesce 后的新事务/DDL；begin 与 drain 状态转换、计数和拒绝在同一 gate lock 下完成。
- [x] 等待 quiesce 前已开始的 transaction、commit 和 DDL 全部结束；超时和 interrupt 均恢复或终止 gate，不签发失效 token。
- [x] 返回进程内 `ActivationToken`；相同 commit 或 abort 可幂等重试，互相冲突的重复消费明确失败。
- [x] `commitActivation()`把旧 generation 原子转为 `FENCED`，且仅在 active transaction/commit/DDL 全部为零时完成。
- [x] `abortActivation()`和未消费 token 的 `close()`恢复旧库准入。
- [x] 分配 90161 `ONLINE_BACKUP_QUIESCING_1`、90162 `GENERATION_FENCED_1`和 90163 `ONLINE_BACKUP_ACTIVATION_TIMEOUT_1`，分别显式映射 `40001`/`SQLTransactionRollbackException`、`08006`/`SQLNonTransientConnectionException`和`HYT00`/`SQLTimeoutException`。
- [x] quiesce 和 activation timeout 后恢复 admission，原连接保持有效；fence 后既有 embedded 连接 `isClosed=true`、`isValid=false`，旧 statement 和新连接均稳定返回 90162。
- [x] `ActivationReport`输出结构化 `ActivationStatus`和非本地化 `ActivationReason`；gate metrics 对拒绝和 timeout 输出 `QUIESCING`、`GENERATION_FENCED`、`ACTIVATION_TIMEOUT`。
- [x] 验证旧 generation 已 fence 后不能通过旧 session、已 prepare statement 或重新连接继续操作。
- [x] H2 activation core 不包含路由、健康检查、旧 generation 删除或 rollback-window 策略；这些仍由 ADB 持久化 active pointer 驱动。
- [x] `ActivationToken`文档和实现均限定为当前 H2 进程内协调对象，不作为重启后的 active generation 判断依据。

验收：

- [x] `T-H2BR-DRAIN-SUCCESS-01`
- [x] `T-H2BR-DRAIN-TIMEOUT-01`
- [x] `T-H2BR-ACTIVATION-ABORT-01`
- [x] `T-H2BR-ACTIVATION-CONSUME-ONCE-01`
- [x] `T-H2BR-OLD-GENERATION-FENCE-01`
- [x] `T-H2BR-BEGIN-DRAIN-RACE-01`
- [x] `T-H2BR-ACTIVATION-GENERATION-MISMATCH-01`
- [x] `T-H2BR-QUIESCE-ERROR-CONTRACT-01`
- [x] `T-H2BR-FENCE-ERROR-CONTRACT-01`
- [x] `T-H2BR-ACTIVATION-TIMEOUT-CONTRACT-01`
- [x] `T-H2BR-ACTIVATION-CONNECTION-LIFECYCLE-01`

实现结果：

- `DatabaseOperationGate`增加持久到 Database 关闭的 `FENCED`状态；`TRANSACTION_DRAIN → OPEN`只由 abort/close 完成，`TRANSACTION_DRAIN → FENCED`只由 token commit 完成。
- drain 设置状态与 transaction/DDL begin 计数使用同一公平锁，消除 begin/drain 漏计窗口；已有事务的 commit 仍可在 drain 中完成。
- drain timeout 解除 quiesce 并返回独立 90163；Database shutdown 会改变 gate generation、唤醒 waiter，并阻止其返回已失效 handle。
- `ActivationCoordinator.prepare()`在 drain 前后校验 old generation；report 使用 drain 完成后的 `databaseId/schemaEpoch`，并拒绝 old/new generation 相同。
- `ActivationToken`持有 drain handle 和不可变 report；相同动作返回第一次结果，commit-after-abort 和 abort-after-commit 不改变状态并明确报错。
- `ActivationReport`记录 `activationId`、database/old/new generation、schema epoch、drain 时长、状态、稳定 reason 和更新时间。
- 三个 vendor code 在 `ErrorCode.getState()`和`DbException.getJdbcSQLException()`中显式映射；消息查找保留 vendor-code 专用文本，避免复用 deadlock、connection-broken 或 lock-timeout 诊断。
- embedded `JdbcConnection.checkClosed()`识别 permanent fence；现存连接、已创建 statement、`isClosed/isValid`和 fence 后新 session 均表现为不可恢复连接失效。
- 默认未启用协调功能的 Database 不创建 gate；JDBC 路径只增加可预测的 null 分支，传统备份和默认 MVStore 回归保持通过。
- H2 不持久化 token，不更新 active pointer，也不删除旧 generation；P9 的 ADB 崩溃恢复仍只依据持久化 active pointer。

验证命令：

```powershell
.\gradlew.bat runOnlineBackupCheck --tests org.h2.test.backup.ActivationCoordinatorTest --rerun-tasks
.\gradlew.bat runOnlineBackupCheck --rerun-tasks
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
.\gradlew.bat javadoc
.\gradlew.bat legacyTestClasses
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
```

验证结果：

| 范围 | 结果 |
| --- | --- |
| P7 专项 | `ActivationCoordinatorTest` 6 项通过，0 failure、0 error、0 skipped。 |
| P1-P7 专项 | `runOnlineBackupCheck` 58 项通过，0 failure、0 error、0 skipped。 |
| JDK 8 / Javadoc | `compileJava`、`compileOnlineBackupTestJava`和`javadoc`通过。 |
| plugin 回归 | 140 项通过，0 failure、0 error、0 skipped。 |
| 传统备份与默认 MVStore | `TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。 |

### P8 TCP v21 远程适配

- [x] 让 `JdbcConnection`实现统一 `OnlineBackupControl`，本地和远程连接使用同一公开 API。
- [x] 将 H2 TCP 最大协议版本从 20 提升到 21，并保持最低兼容版本不变。
- [x] 在 `SessionRemote`增加在线备份、shadow restore 和 activation 管理操作。
- [x] 在 `TcpServerThread`增加对应分发，只调用服务端 core coordinator，不复制业务实现。
- [x] 为 prepared backup、restore 和 activation token 建立 TCP session 级不透明 handle registry。
- [x] 校验 handle 所属 session、databaseId、管理员身份和操作状态，禁止跨连接复用。
- [x] 在正常关闭、网络断开、server stop 和异常路径中逆序 abort/close 全部 handle。
- [x] 存在活动 handle 时禁止自动重连继承状态；重连后要求重新 prepare。
- [x] 增加服务端 `backupRoot`、`shadowRoot`和 participant allowlist 配置。
- [x] 远程请求只接受受限相对名称，路径解析后必须仍位于配置根目录内。
- [x] 新客户端在协商版本低于 21 时本地返回 feature-not-supported，不发送新操作码。
- [x] 验证旧客户端连接 v21 server 时普通 JDBC、`BACKUP TO`和连接状态行为不变。
- [x] 验证 embedded 与 TCP v21 对 quiesce、fence 和 activation timeout 保持 vendor code、SQLState、JDBC 异常类型和连接生命周期一致。

验收：

- [x] `T-H2BR-REMOTE-UNWRAP-01`
- [x] `T-H2BR-REMOTE-PREPARE-MATERIALIZE-01`
- [x] `T-H2BR-REMOTE-RESTORE-ACTIVATION-01`
- [x] `T-H2BR-REMOTE-DISCONNECT-CLEANUP-01`
- [x] `T-H2BR-REMOTE-HANDLE-OWNERSHIP-01`
- [x] `T-H2BR-REMOTE-PATH-ROOT-01`
- [x] `T-H2BR-REMOTE-NEW-CLIENT-OLD-SERVER-01`
- [x] `T-H2BR-REMOTE-OLD-CLIENT-NEW-SERVER-01`
- [x] `T-H2BR-REMOTE-AUTORECONNECT-01`
- [x] `T-H2BR-REMOTE-ACTIVATION-ERROR-PARITY-01`

暂停条件：

- 网络断开后无法有界释放 prepared snapshot pin 或 activation quiesce。
- 远程 handle 必须脱离 TCP session 才能满足基本流程，且没有可靠的持久化所有权协议。
- 服务端路径和 participant 选择无法通过固定 root/allowlist 建立明确权限边界。

实现说明：

- 新增公开 `OnlineBackupControl`及 backup、restore、activation 三类连接归属 handle 和不可变 report/descriptor DTO；`JdbcConnection.unwrap()`在 embedded 与 TCP 连接上返回同一入口。
- embedded adapter 直接调用既有 `OnlineBackupSession`、`ShadowRestoreCoordinator`和`ActivationCoordinator`；TCP adapter 只编解码 v21 DTO 与 opaque handle ID，服务端分发继续调用同一 core coordinator。
- TCP v21 新增 prepare/publish/abort/close、shadow stage/validate/close 和 activation prepare/commit/abort/close 操作；v17-v20 协商范围保持不变。
- 服务端 registry 按 TCP session 隔离并绑定 `databaseId`；正常 close、异常断链和 server stop 均按创建逆序关闭，未消费 activation token 自动 abort，prepared snapshot 复用 P3 的幂等 cleanup。
- `SessionRemote`在管理请求执行中、存在 active handle 或已收到 generation fence 后禁止透明 auto-reconnect；连接断开后必须重新建立 session 并重新 prepare。
- 服务端新增 `-tcpOnlineBackupRoot`、`-tcpShadowRoot`和`-tcpOnlineBackupParticipants`；root 必须启动时已存在且不是 symlink/junction，远程只接受单段受限名称并解析为 root 的直接子项。
- 管理操作要求 ADMIN；participant 选择必须是服务端 allowlist 子集；第一阶段 TCP adapter 不接受额外 validation provider。携带 shadow 密码的远程 validation 必须使用 `-tcpSSL`。
- 管理错误响应会脱敏 backup、shadow 和 database root；不会把服务端绝对目录返回客户端。
- 新客户端协商到 v20 时在发送新 opcode 前返回 feature-not-supported；最高只协商到 v20 的客户端行为测试确认 v21 server 上普通 JDBC 和传统 `BACKUP TO`不变。真实 2.3.0 二进制 client/server 全矩阵仍归 P9。

验证命令：

```powershell
.\gradlew.bat runOnlineBackupCheck --tests org.h2.test.backup.OnlineBackupJdbcControlTest --tests org.h2.test.backup.OnlineBackupTcpV21Test --rerun-tasks
.\gradlew.bat runOnlineBackupCheck --rerun-tasks
.\gradlew.bat runPluginArchitectureCheck --rerun-tasks
.\gradlew.bat javadoc
.\gradlew.bat legacyTestClasses
java -cp "build/classes/java/legacyTest;build/classes/java/main;build/resources/legacyTest;build/resources/main" `
  org.h2.test.LegacyTestGroupRunner `
  org.h2.test.db.TestBackup `
  org.h2.test.db.TestOpenClose `
  org.h2.test.store.TestMVStoreConcurrent
```

验证结果：

| 范围 | 结果 |
| --- | --- |
| P8 专项 | `OnlineBackupJdbcControlTest`和`OnlineBackupTcpV21Test`共 10 项通过，0 failure、0 error、0 skipped。 |
| P1-P8 专项 | `runOnlineBackupCheck` 68 项通过，0 failure、0 error、0 skipped。 |
| JDK 8 / Javadoc | `compileJava`、`compileOnlineBackupTestJava`和`javadoc`通过。 |
| plugin 回归 | 140 项通过，0 failure、0 error、0 skipped。 |
| 传统备份与默认 MVStore | `TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。 |

阶段约束：

- TCP handle 只在所属连接进程内有效，不做跨 session、跨进程续传或自动继承。
- 非 TLS TCP 不传输 shadow 明文密码；加密数据库的远程 shadow validation 必须启用 `-tcpSSL`。
- remote additional validation provider 在 P8 fail-closed；P9 建立真实 provider 认证清单和独立服务端 provider allowlist 后才能开放。
- P8 的旧客户端测试通过最高协议版本钳制到 v20 验证 wire 行为；真实 2.3.0 jar 与 2.4.x 的双向二进制组合留在 P9 兼容矩阵执行。

### P9 ADB/LDB 联调、性能与灰度

- [x] 接入一个真实 ADB/LDB participant，同时保留 fake participant。
- [x] ADB 首次灰度配置和 provider allowlist 只允许一个已认证真实 participant；H2 core 不硬编码数量上限。
- [x] 使用多个 fake participant 完成顺序、部分 prepare 成功、逆序 abort、共享 deadline 和 manifest 多条目测试。
- [x] 保持首期单真实 participant 限制；放宽数量前必须重新执行 barrier 性能、容量和全部故障注入门禁。
- [x] 建立 validation-safe provider 认证清单，记录 provider ID、类型、版本范围、必要性和被允许的 validation 行为。
- [x] 建立 2.3.0 数据文件、传统 zip、旧插件和 TCP v17-v20 与 2.4.x 新实现的双向兼容测试矩阵。
- [x] 发布前校验 Gradle artifact、`Constants.VERSION/FULL_VERSION`、manifest `h2dbVersion`、日志和协议版本报告一致。
- [x] 用跨存储单调提交序号验证同一 cut。
- [x] 实现 ADB generation registry、`routeVersion` CAS 和 active pointer 的持久化恢复。
- [x] 覆盖 pointer 更新前后、进程内路由更新前后和 token 消费前后的崩溃矩阵。
- [x] 验证 pointer 为 old 时恢复 old，pointer 为 new 时恢复 new 并继续 fence old。
- [x] 验证 new 接受写入后禁止自动回拨 old，只允许 forward recovery。
- [x] 注入 participant prepare、materialize、checksum、fsync、rename、shadow open 和 router switch 失败。
- [x] 在持续 DML、DDL、长事务和高脏页负载下测量 barrier。
- [x] 记录 prepare P50/P95/P99/max、吞吐下降、恢复时间和文件增长。
- [x] 先启用只生成、不恢复，再启用 shadow validate，最后灰度 activation。
- [x] 达到门禁前不替换 ADB 默认备份路径。
- [x] 编写只读旧库 clone onboarding 手册，明确新 identity、新备份链、原库不变以及后续 activation 步骤。
- [x] 更新用户文档、运维文档、兼容说明和回滚手册。

已完成的 P9 联调实现与证据：

- LDB `ced22a5`增加 `CheckpointLease`和 `prepareCheckpoint(deadlineNanos)`。prepare 在 DB mutex 内固定 retained Version、各 CF current/immutable MemTable 引用和 `lastSequence`；锁外按 sequence 过滤生成独立 SST/MANIFEST，支持协作中断、失败 staging 清理和幂等 close。LDB 根模块 256 项单元测试、专项原型/生产 API 测试和 Javadoc 通过。
- ADB `f555d1a`增加真实 `adb_ldb` participant。运行时绑定键为 `(databaseId,generationId)`；prepare 使用 LDB lease，materialize 通过受限 artifact target 输出，validation 只把 manifest 已声明文件复制到隔离临时目录并调用离线 `LDBFactory.check`，不启动业务 scheduler、服务注册或外部网络。ADB 全量测试与 Javadoc 通过。
- ADB `071d107`增加持久化 generation registry、进程间文件锁、文件 fsync、同目录原子替换、`routeVersion` CAS、进程内 router 和 H2 activation token 协调。测试覆盖 pointer 更新前/后重启、进程内路由更新失败后的 forward recovery、token abort/commit、显式写前回拨以及首次新写后的永久禁止回拨。
- ADB `b92b9c2`增加生产灰度策略，模式严格按 `DISABLED -> GENERATE_ONLY -> SHADOW_VALIDATE -> ACTIVATE`放开；首次生产入口只接受单个已认证 `adb_ldb`，该限制不进入 H2 SPI、bundle 格式或 H2 core。
- 真实已发布 `h2db-2.3.0.jar`参与兼容测试：旧版本创建的数据文件和传统 zip 可由当前版本打开/恢复；2.3 client 对 2.4 server、2.4 client 对 2.3 server 的普通 JDBC/传统 `BACKUP TO`保持可用，v21 管理操作在协商到 v20 后由客户端本地拒绝；仅按 2.3 API 编译的插件可由当前插件加载器加载。
- 修复 Windows 显式插件路径解析：只把反斜杠加逗号解释为逗号转义，普通 `C:\...`不再被通用字符串拆分器吞掉反斜杠。
- 当前 `runOnlineBackupCheck`为 76/76，`runPluginArchitectureCheck`为 140/140；Gradle 制品、`Constants.VERSION/FULL_VERSION`和 bundle manifest 已统一为 `2.4.0-SNAPSHOT`语义。
- `AdbOnlineBackupParticipantIntegrationTest#restoresSameMonotonicCutFromH2AndLdbArtifacts`在一个事务中向 MVStore 和 ADB/LDB 写入相同单调序号；恢复后的两边最大序号同为 40，切点后提交的 41 在两边都不存在。
- `AdbOnlineBackupPerformanceGateTest`在最终全量验收轮次的持续 DML 下测得 30 次 prepare 的 P50/P95/P99/max 为 `0/1/2/2 ms`；500 ms 提交基线为 601，1,012 ms 内出现 809 提交的恢复窗口，达到“5 秒内恢复到基线 90%”门禁，LDB 净文件增长 0 byte。
- `AdbOnlineBackupMixedLoadGateTest`在 1 MiB 预装 LDB 脏数据、持续 LDB DML、持续 MVStore DDL 和一个未提交 MVStore 长事务并存时，20 次 prepare 的 P50/P95/P99/max 为 `4/7/21/21 ms`，期间完成 48 次 DML 提交和 14 轮 DDL，长事务回滚后为 0 行。详见 `docs/online-backup/p9-performance-report.md`。
- `OnlineBackupBundleFaultMatrixTest`增加 checksum、artifact/manifest/directory fsync、atomic rename 和 participant materialize 确定性故障；rename 前异常不留 final/staging，rename 后 parent fsync 失败保留 final 并由同一 cut 幂等重试收敛。独立子进程在 participant、manifest 和 staging fsync 全部完成后、atomic rename 前执行 `Runtime.halt`，验证 final 不可见、staging 不会被误发布、源库可重开且新任务可继续发布。结合既有 prepare、shadow open/checksum 和 ADB router/pointer 测试，完整证据见 `docs/online-backup/p9-fault-matrix.md`。

验收：

- [x] `T-H2BR-CROSS-STORE-CUT-01`
- [x] `T-H2BR-CRASH-MATRIX-01`
- [x] `T-H2BR-GENERATION-POINTER-CRASH-01`
- [x] `T-H2BR-GENERATION-POST-WRITE-NO-ROLLBACK-01`
- [x] `T-H2BR-PARTICIPANT-ROLLOUT-LIMIT-01`
- [x] `T-H2BR-23X-DATAFILE-UPGRADE-01`
- [x] `T-H2BR-23X-TRADITIONAL-BACKUP-01`
- [x] `T-H2BR-23X-PLUGIN-COMPAT-01`
- [x] `T-H2BR-23X-TCP-COMPAT-01`
- [x] `T-H2BR-VERSION-METADATA-CONSISTENCY-01`
- [x] `T-H2BR-PERFORMANCE-GATE-01`
- [x] `T-H2BR-ROLLBACK-DRILL-01`

## 测试与验证计划

### 最小编译门禁

运行目录：`D:\work\java\h2db\h2`

```powershell
.\gradlew.bat compileJava
```

### 专项测试入口

计划新增或确认：

```powershell
.\gradlew.bat runOnlineBackupCheck
```

该任务应聚合 core gate、MVStore snapshot、participant SPI、bundle、shadow restore、activation 和 TCP v21 远程协议测试。由于当前构建脚本会禁用名称中包含 `test`的 Gradle task，不以普通 `gradlew test`作为有效验证结果。

### 关键一致性测试模型

H2 catalog 和 fake participant 同时记录单调递增的提交序号：

1. barrier 前完成的提交必须同时出现在 H2 和 participant 恢复结果中。
2. barrier 后完成的提交不得只出现在其中一方。
3. prepare 期间未提交或回滚的事务不得出现在任一恢复结果中。
4. DDL 对应的 `schemaEpoch`必须与恢复后的 catalog 一致。

### 故障注入点

- gate 关闭前、关闭后和 drain 完成后。
- MVStore flush 前后。
- H2 snapshot 成功、首个 participant 成功、全部 participant 成功后。
- artifact 创建、复制、checksum、manifest 写入、fsync 和 atomic move。
- shadow staging、校验、试打开和关闭。
- activation token 返回前后、router 切换前后、旧 generation fence 前后。
- TCP handle 创建前后、响应发送前后、连接半关闭、网络断开、server stop 和自动重连。

## 可观测性

第一阶段不引入第三方 metrics 依赖。每次操作返回结构化 report，并通过 H2 trace 输出可聚合字段：

| 指标 | 说明 |
| --- | --- |
| prepareTotalMillis | prepare API 总耗时。 |
| barrierWaitMillis | 等待在途 commit/DDL 的时间。 |
| barrierHoldMillis | gate 关闭到释放的总时间。 |
| finalFlushMillis | barrier 内最终 flush 时间。 |
| participantPrepareMillis | 每个 participant prepare 时间。 |
| h2MaterializeMillis | H2 artifact 复制时间。 |
| participantMaterializeMillis | participant artifact 物化时间。 |
| checksumMillis | 校验时间。 |
| publishMillis | fsync 和原子发布耗时。 |
| shadowValidateMillis | shadow 校验和试打开耗时。 |
| drainMillis | activation 排空耗时。 |
| failurePhase/failureCode | 稳定失败分类。 |

P99 由 ADB 或专项性能工具对逐操作 report 聚合，不在 H2 core 内引入完整 histogram 实现。

## 风险登记

| 风险 | 级别 | 检测 | 缓解 | 状态 |
| --- | --- | --- | --- | --- |
| MVStore header 在 barrier 外变化导致切点漂移 | P0 | header race 和恢复 cut 测试 | barrier 内捕获 header，固定 copyLength | [x] |
| 关闭空间复用期间文件快速增长 | P1 | file growth 指标和容量测试 | 单 snapshot、TTL、容量门禁、及时 abort | [x] |
| materializer 卡死导致 snapshot pin 长期占用和文件增长 | P0 | lease age、active reader、file growth 和 maintenance-blocked 指标 | 保持 pin保证安全，熔断新 snapshot/maintenance，暴露受控重启诊断；禁止强制 unpin | [x] |
| participant 在 barrier 内阻塞 | P1 | watchdog 和 phase timing | 受信 provider、deadline 契约、预检、灰度认证 | [x] |
| 多 participant 逐个消耗完整 timeout，导致总 barrier 时间随数量失控 | P0 | 多 fake participant deadline 测试 | 使用单一绝对 deadline，每次 prepare 只获得剩余预算；首次灰度限制一个真实 participant | [x] |
| flush 无法在 1 秒内安全中断 | P1 | 高脏页性能测试 | barrier 外预 flush、deadline overrun、重新定义硬上限口径 | [x] |
| DDL 只在 commit 处阻塞导致 catalog 已变更 | P0 | DDL race 测试 | DDL 执行前进入 gate | [x] |
| quiesce 与 transaction begin 竞态产生漏计事务 | P0 | begin/drain race 测试 | P7 已将 begin/end、drain 状态和计数放入同一 gate lock，并覆盖 DML/DDL race、timeout 和 shutdown | [x] |
| ADB 把永久 fence 当作可重试错误并复用旧连接 | P0 | embedded/TCP error contract 测试 | 独立 90162、`08006`和 non-transient connection exception；embedded/TCP 均使旧连接、statement 和新 session 永久不可用 | [x] |
| 新错误复用 deadlock/lock-timeout vendor code 导致诊断和重试策略混淆 | P1 | error-code uniqueness 测试 | P7 已分配 90161-90163，显式映射 SQLState/JDBC 类型并保留独立消息；未复用既有 vendor code | [x] |
| validation open 触发 plugin 外部副作用 | P0 | fake lifecycle/network/thread provider 测试 | 必要 provider 白名单、显式 validation capability、受限 context、trigger/lifecycle 抑制、真实 `adb_ldb`认证和未认证依赖 fail-closed | [x] |
| 只读模式被误认为足以隔离插件副作用 | P0 | 只读库中注入网络、线程和服务注册尝试 | 数据库只读与 provider capability/allowlist 分层；真实 provider 只执行隔离临时目录中的离线校验 | [x] |
| manifest 状态与 atomic publish 冲突 | P1 | crash matrix | final manifest 只写 PUBLISHED，运行状态独立；异常注入和 rename 前子进程 `Runtime.halt`均证明 staging 不会误发布 | [x] |
| 旧版本删除或改写未知 metadata map | P1 | 旧版本往返测试 | 已验证 2.3.0 只读打开保留未知 map；降级写入由 ADB 启动策略禁止 | [x] |
| 路由 pointer 已切到 new 但进程内路由或 token fence 尚未完成时进程退出 | P1 | ADB 各切换点 crash drill | 只按持久化 active pointer 恢复；pointer 为 new 时启动 new 并继续 fence old，不依赖 token 推断 | [x] |
| new generation 已接受写入后自动回拨 old 导致新写入丢失 | P0 | post-write rollback drill | 将首次 new 写入视为不可逆点；禁止自动回拨，采用数据对账后的新切换或一致性备份恢复 | [x] |
| TCP 断线遗留 snapshot pin 或 quiesce | P0 | 断线、server stop 和半关闭测试 | handle 绑定 TCP session，连接清理时逆序 abort/close | [x] |
| 新客户端向旧服务器发送未知操作码 | P1 | 新旧 client/server 兼容矩阵 | 协商版本低于 21 时客户端本地拒绝 | [x] |
| 远程路径逃逸服务端根目录 | P0 | traversal、绝对路径、symlink 测试 | 固定 root、仅相对名称、解析后边界复核 | [x] |
| 协调功能意外侵入默认 MVStore 路径 | P0 | feature-off 回归、文件结构和性能基线对比 | 默认关闭、启动期固定；关闭时不创建 gate/metadata/participant，热路径仅保留可预测分支 | [x] |
| 失败但未修改 catalog 的 DDL 误增 schemaEpoch | P0 | P0.5 失败 DDL 原型测试 | 命令成功路径设置 session 级 catalog-changed 标记；禁止直接使用 `isDdl`事件 | 已验证 |
| 2.3.x 降级写入导致 schemaEpoch 过期 | P0 | P0.5 2.3.x DDL 往返测试 | 已启用协调功能的数据库只允许旧版本只读应急打开，不支持降级写入 | 已验证 |
| Recover 逻辑脚本丢失 identity map | P1 | P0.5 Recover 重建测试 | 正式实现扩展 Recover，或明确生成新 identity 并切断旧备份链 | 已验证 |
| 只读旧库用路径/checksum/调用方 UUID 临时派生 identity，造成备份链漂移或串库 | P0 | copy/rename/compact/onboarding 测试 | 缺少 metadata 时拒绝组合备份；只允许独立可写 clone 生成新 identity 和新链 | [x] |
| Gradle artifact 与运行时版本报告不一致，导致 manifest 和诊断误判 | P0 | version metadata consistency test | 发布前统一 Gradle、Constants、manifest、日志和协议版本来源 | [x] |

## 开放问题与决策记录

以下问题按对实现阻塞程度排序。`推荐方案`是讨论起点，不代表已经确认。

| ID | 开放问题 | 推荐方案 | 影响阶段 | 状态 |
| --- | --- | --- | --- | --- |
| OQ-01 | embedded 和 H2 server 模式通过什么入口调用组合备份？ | 统一使用 `Connection.unwrap(OnlineBackupControl.class)`；embedded 直连，H2 server 通过 TCP v21；不扩展 `BACKUP TO`。 | P0/P4/P8 | 已确认 |
| OQ-02 | 1 秒是性能 SLO 还是包括失控回调的绝对硬上限？ | 定义为受信 participant 和约定负载下的 SLO；引擎记录 overrun，但不强杀线程。 | P0/P1/P3 | 已确认 |
| OQ-03 | 第一阶段幂等重试是否必须跨进程继续旧 cut？ | 不要求继续未发布旧 cut；支持同 session 重试和已发布结果复用，崩溃后使用新的 `backupId/cutId`。 | P0/P3/P5 | 已确认 |
| OQ-04 | 组合备份发布为目录还是单 zip？ | 第一阶段使用目录 bundle，旧 `BACKUP TO`继续使用 zip。 | P0/P5/P6 | 已确认 |
| OQ-05 | databaseId/schemaEpoch 持久化在哪里？ | 使用 `.mv.db`内独立事务型 `h2.onlineBackup.meta` map，不复用进程内 modification id；成功 catalog 变更标记、Recover 扩展和禁止 2.3.x 降级写入是强制约束。 | P0.5/P2/P3 | 已确认 |
| OQ-06 | generation 路由由谁持有？ | ADB SQL Server 生成 generation ID，并持久化 generation registry、active pointer、切换状态和崩溃恢复；H2 只提供 generation-local validate、quiesce、activation token、abort 和 fence。 | P2/P7/P9 | 已确认 |
| OQ-07 | 第一阶段支持几个 participant？ | SPI、session、manifest 和 H2 实现从第一版支持多个；按稳定 ID 顺序 prepare、共享总 deadline、逆序 abort。首次生产灰度由 ADB 配置限制为一个已认证真实 participant。 | P4/P9 | 已确认 |
| OQ-08 | validation open 是否加载业务插件？ | 只加载 allowlist 内且显式声明 validation capability 的必要 storage/catalog/data-type/participant provider；非必要业务插件不加载，未认证必要依赖返回 `UNVALIDATABLE_PROVIDER`，不得普通模式兜底。 | P6/P9 | 已确认 |
| OQ-09 | prepared snapshot 超时后能否自动 abort？ | idle 时允许 watchdog 自动 abort；materializing 时只协作取消，最后一个 reader 退出后 unpin；卡死 reader 保持 pin并告警/熔断，禁止强制 close。P0.6 已验证该状态协议。 | P0.6/P3 | 已确认 |
| OQ-10 | 临时 quiesce、fence、timeout 使用什么 SQLState？ | 分别使用 `40001`、`08006`、`HYT00`，并新增三个独立 H2 vendor code，映射到 `SQLTransactionRollbackException`、`SQLNonTransientConnectionException`和`SQLTimeoutException`。 | P7/P8 | 已确认 |
| OQ-11 | 只读旧库缺少 databaseId 时如何处理？ | 明确拒绝组合备份，不创建 sidecar/临时 ID，也不接受调用方 identity；旧备份方式保持可用。需要接入时复制到独立可写 clone，生成新 identity 并建立新的备份链。 | P2/P4/P9 | 已确认 |
| OQ-12 | 需求基线 2.3.0 与当前 2.4.0-SNAPSHOT 如何处理？ | 当前 2.4.x 开发线实现，不向已发布 2.3.x 回移；2.3.0 作为数据文件、传统 zip、插件和 TCP 兼容输入基线。发布前统一 Gradle 与运行时版本元数据。 | P0/P9 | 已确认 |
| OQ-13 | 在线备份协调是否影响 H2 默认存储实现，如何启用？ | 增加 `ONLINE_BACKUP_COORDINATION`启动期配置，默认 `FALSE`；仅 Database 打开时确定且禁止热切换。关闭时不创建 gate、metadata map 或 participant，不改变旧备份和默认存储语义。 | P0/P1/P2/P9 | 已确认 |

### 决策记录模板

每次确认后追加一行，不覆盖原始问题：

| 日期 | ID | 决策 | 理由 | 后续动作 |
| --- | --- | --- | --- | --- |
| 2026-07-28 | OQ-01 | 统一使用 `Connection.unwrap(OnlineBackupControl.class)`；embedded 直连 core，H2 server 通过 TCP v21 远程管理协议；不增加组合备份 SQL 语法。 | 公开 API 保持一致，同时满足 server 模式的服务端资源和文件操作要求，避免把长生命周期 handle 编码成 SQL。 | P4 固定公开 API，P8 实现协议协商、远程 handle、路径根目录、断线清理和兼容矩阵。 |
| 2026-07-28 | OQ-02 | 1 秒按受信 participant 和约定负载下的性能 SLO 执行，不提供线程强杀。 | MVStore flush 和 Java 回调不可安全抢占，强杀会破坏锁与资源清理。 | P1/P3 增加 deadline、overrun 指标、预 flush 和超时故障测试。 |
| 2026-07-28 | OQ-03 | 第一阶段只支持同 session 重试和已发布结果复用；进程崩溃后使用新的 `backupId/cutId`。 | MVStore snapshot pin 是进程内资源，跨进程续传需要持久化 pin 和新的存储恢复协议。 | P3/P5 实现 staging 清理、final 复用和冲突拒绝。 |
| 2026-07-28 | OQ-04 | 组合备份发布为目录 bundle；旧 `BACKUP TO`继续使用 zip。 | participant 可能产生多个大文件，目录发布可避免统一压缩流和重复复制。 | P5 固定 bundle 布局、同父目录 staging 和原子移动。 |
| 2026-07-28 | OQ-05 | `databaseId/schemaEpoch`存放在 `.mv.db`内独立事务型 `h2.onlineBackup.meta` map。 | P0.5 原型证明其与 catalog transaction 原子提交、重启、并发、物理备份恢复、compact、只读、加密和 2.3.x map 保留均可行；sidecar、文件头和 catalog setting 的一致性或兼容性更差。 | P2 增加成功 catalog 变更标记；扩展 Recover 或定义新 identity 语义；启用协调功能后禁止 2.3.x 降级写入。 |
| 2026-07-28 | OQ-13 | `ONLINE_BACKUP_COORDINATION`默认关闭，只能在 Database 打开阶段确定，Database 生命周期内禁止热切换；关闭时不创建协调资源或持久化元数据。 | 将新能力与 H2 默认存储实现隔离，避免普通用户承担无关的存储格式、锁、计数和 participant 生命周期风险。 | P1 实现启动期配置和 feature-off 快路径，P2 按启用状态初始化 metadata，P9 增加默认 MVStore 回归与性能对比。 |
| 2026-07-28 | OQ-06 | generation 路由及其崩溃恢复归 ADB SQL Server；ADB 的持久化 active pointer 是唯一事实来源。`generationId`不进入 H2 identity map，H2 只提供当前实例的 validate、quiesce、token、abort 和 fence。 | generation 是 H2 与 participant 的组合部署实例，不能由单个 H2 文件决定；物理备份会复制 identity map，因此 generation ID 必须由部署和路由层重新分配；进程内 token 无法承担崩溃恢复。 | P2 接收实例级 generationId；P7 固定 token 边界；P9 实现 registry、CAS、崩溃矩阵和 new 写入后的 forward recovery。 |
| 2026-07-28 | OQ-07 | participant 协议和实现支持多个；按稳定 ID 顺序 prepare、共享一次 barrier 总 deadline、按完成顺序逆序 abort。首次生产灰度限制一个真实 participant。 | 集合模型可避免未来破坏 SPI 和 manifest；确定性顺序便于复现与清理；生产限一可控制首发性能和故障组合风险。 | P4 完成多 fake participant 测试；P9 通过 ADB 配置和 allowlist 限一，放宽前重新过性能与故障门禁。 |
| 2026-07-28 | OQ-08 | shadow validation 只加载白名单内且显式支持 validation mode 的必要 provider；非必要业务插件不加载，未认证必要依赖 fail-closed。 | 只读 H2 只能约束数据库写入，无法阻止插件访问网络、启动线程、注册服务或修改外部系统；加载全部插件风险过高，而完全不加载又无法打开自定义存储/catalog。 | P6 增加受限 validation context、provider capability/allowlist 检查、稳定错误和副作用测试；P9 建立 provider 认证清单。 |
| 2026-07-28 | OQ-10 | quiesce、fence、activation timeout 分别使用 `40001`、`08006`、`HYT00`，但分配独立 vendor code 并显式映射 JDBC 异常类型。 | 标准 SQLState 便于 JDBC 调用方分类；H2 现有相近错误分别代表 deadlock、lock timeout 和普通 connection broken，复用 vendor code 会混淆诊断与自动恢复。 | P7 实现错误码和连接生命周期；P8 验证 embedded/TCP 等价；ADB 使用 vendor code 或结构化 reason 决策。 |
| 2026-07-28 | OQ-11 | 缺少 identity metadata 的只读旧库拒绝组合备份；旧工具保持可用。通过独立可写 clone onboarding 时生成新 identity 并开始新备份链。 | 临时派生或外部注入 identity 不能保证跨复制/compact 稳定、catalog transaction 原子性和防串库；sidecar 又会重引入 OQ-05 已排除的双重事实来源。 | P2 实现稳定拒绝和只读无写测试；P9 编写 clone onboarding 与新 lineage 运维流程。 |
| 2026-07-28 | OQ-09 | idle prepared snapshot 允许 watchdog 自动 abort；materializing 只协作取消，reader 排空后 unpin；卡死 reader 保持 pin并熔断，禁止强制 close。 | P0.6 的 6 项测试证明 cleanup 可线性化且不会提前恢复真实 MVStore `reuseSpace`；卡死 reader 场景必须保留 pin，说明强制回收不安全。 | P3 实现正式 lease 状态机并接入 compact/reclamation；P8 让 TCP disconnect 复用相同 cleanup；增加告警、熔断和受控重启手册。 |
| 2026-07-28 | OQ-12 | 新能力在当前 2.4.x 开发线实现，不回移已发布 2.3.x；2.3.0 是数据文件、传统 zip、插件和 TCP 的兼容输入基线。 | 需求中的 2.3.0 表示既有部署兼容目标，而当前制品已进入 2.4.0-SNAPSHOT；回移会扩大协议和磁盘语义风险。仓库还存在 Gradle 2.4 snapshot 与运行时 2.3.0 的版本报告差异。 | P0 固定基线并建立既有测试结果；P9 完成兼容矩阵，发布前统一全部版本元数据。 |

## 建议的开放问题讨论顺序

1. OQ-01 至 OQ-13 已全部确认。
2. P0、P0.5、P0.6 和 P1-P9 已完成；后续放宽真实 participant 数量或替换 ADB 默认备份路径时，必须重新执行性能、容量、故障和兼容门禁。

## 最终验收记录（2026-07-28）

| 需求 | 实现与证据 | 结果 |
| --- | --- | --- |
| H2BR-001/002 session 与 participant SPI | P4 多 fake participant 顺序、共享 deadline、逆序 abort；P9 真实 `adb_ldb` participant | 通过 |
| H2BR-003 有界 barrier | commit/DDL gate、超时清理；持续 DML 与混合负载 P99 分别为 2 ms、21 ms，最大分别为 2 ms、21 ms | 通过 |
| H2BR-004 prepared snapshot | MVStore lease 固定 header/copyLength，barrier 外物化；LDB retained Version/MemTable lease | 通过 |
| H2BR-005 manifest | 稳定编码、未知字段容忍、H2 与 participant artifact checksum、atomic publish | 通过 |
| H2BR-006 shadow restore/validate | 隔离 staging、checksum、allowlist provider、只读试打开、活动库不变 | 通过 |
| H2BR-007 activation drain | 有界 transaction drain、可取消 token、永久 fence；ADB pointer/CAS forward recovery | 通过 |
| H2BR-008 可观测性 | 结构化 prepare/publish/restore/activation report、phase timing、稳定 failure reason | 通过 |
| H2BR-009 取消与异常清理 | prepare/materialize/checksum/fsync/rename/shadow/router 故障矩阵及 rename 前进程强退 | 通过 |
| H2BR-010 兼容 | 传统 `BACKUP TO`/`Restore`、默认 MVStore、旧插件、2.3.0 文件/zip/TCP 双向矩阵 | 通过 |

最终命令与结果：

- H2：`runOnlineBackupCheck --rerun-tasks` 76/76；`runPluginArchitectureCheck --rerun-tasks` 140/140；`javadoc`通过；`TestBackup`、`TestOpenClose`和`TestMVStoreConcurrent`通过。
- ADB：全量 `test`和`javadoc`通过；持续 DML 吞吐在 1,012 ms 内达到基线 90%以上；混合 DML/DDL/长事务/高脏页门禁通过。
- LDB：全量 `:test --rerun-tasks`和`:javadoc`通过。

## 完成定义

- 所有阻塞阶段实施的开放问题已经形成书面决策。
- P1-P9 的生产改动均有对应测试编号和实际测试。
- H2 与 participant 的恢复结果通过同一 cut 一致性验证。
- 多 participant 的稳定顺序、共享 deadline、逆序清理和 manifest 集合语义通过测试；首次灰度保持一个真实 participant。
- shadow validation 对必要 provider 可完成只读试打开，并证明非必要插件及外部副作用未被触发；未认证依赖稳定失败。
- quiesce、fence 和 activation timeout 的 vendor code、SQLState、JDBC 异常类型及连接生命周期在 embedded/TCP 下保持一致。
- 缺少 identity 的只读旧库不会产生临时 identity 或隐式写入；clone onboarding 明确建立新备份链。
- 2.3.0 数据文件、传统 zip、旧插件和 TCP 混合版本兼容矩阵通过；新能力仅在 2.4.x 交付且版本元数据一致。
- 现有 `BACKUP TO`、`Restore`、旧插件和旧数据库兼容测试通过。
- `ONLINE_BACKUP_COORDINATION`关闭时不创建 gate、metadata map 或 participant，默认 MVStore 的文件结构、事务、DDL、compact、reclamation 和基线性能无可观察回归。
- identity metadata 与成功 catalog 修改原子提交；失败 DDL 不误增 epoch，Recover 和降级写入遵守 OQ-05 已确认约束。
- 所有异常和中断路径无 gate、snapshot pin、文件句柄和 staging 泄漏。
- snapshot lease 超时遵守 idle 自动 abort、materializing 协作取消和 reader 排空后 unpin；卡死 reader 不发生强制回收。
- shadow restore 和 activation 任意失败均不破坏活动库。
- generation 路由在全部崩溃点均按 ADB 持久化 active pointer 收敛，且 new 接受写入后不会自动回拨 old。
- 已确认性能负载下满足 prepare barrier 和吞吐恢复门禁。
- 文档记录真实实现、验证命令、已知限制、升级和回滚流程。
