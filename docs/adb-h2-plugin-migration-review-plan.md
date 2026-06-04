# ADB 基于 H2 插件机制迁移评审与实施计划

## 背景

本文对照 `D:/work/java2/vexra/docs/adb-h2-plugin-migration-design.md` 与当前 H2 插件化实现，评估 ADB 从 `org.adb.*` H2 分叉迁移到 `h2db + ADB 插件` 的可行性、缺口和 H2 侧实施计划。

ADB 设计文档的核心判断是：`vexra-adb` 当前不是一个薄插件，而是“ADB 业务逻辑 + H2 分叉发行版”。迁移的正确顺序不是先删除 H2 分叉代码，而是先把 ADB 表、索引、事务和 LDB/Raft 适配从 `org.adb.*` 类型体系中剥离，再逐步切到 `org.h2.*` 运行。

## H2-P0 到 H2-P3 收口状态

| 阶段 | 结论 | 当前产出 |
| --- | --- | --- |
| H2-P0 | 已固定评审结论 | 本文档明确 ADB 迁移采用 table provider 优先，不以 storage engine 替换为第一阶段目标。 |
| H2-P1 | 已补契约测试 | `TableSpiContractTest` 覆盖注册、建表、参数透传、schema 上下文、基础 DML 和诊断表。 |
| H2-P2 | 已补迁移期稳定性文档 | `h2db-plugin-developer-guide.md` / `.en.md` 新增自定义 Table / Index 稳定性等级和约束。 |
| H2-P3 | 已补反馈处理规则 | 本文档和开发者指南明确 ADB 原型反馈优先补 `TableEngineContext` 契约与诊断，不扩大 storage 主路径。 |

## 目标

- 对照当前 H2 插件实现，确认 ADB 迁移已经满足的前置能力。
- 识别 ADB 迁移仍然依赖的 H2 SPI / 内部 API 缺口。
- 给出 H2 侧可执行、可测试、可回滚的分阶段实施计划。
- 明确哪些能力应由 H2 插件机制提供，哪些能力不应纳入本轮插件化范围。

## 非目标

- 不直接修改 Vexra / ADB 代码。
- 不承诺 SQL parser、optimizer、JDBC wire protocol、TCP/PG/Web Console 成为插件扩展点。
- 不一次性把 H2 所有内部表、索引、事务对象稳定为公开 API。
- 不改变现有 H2 默认 MVStore 行为、磁盘格式和 legacy `TableEngine` class-name 兼容路径。

## 现状/已有流程

### ADB 设计文档中的现状

| 区域 | 当前状态 | 迁移判断 |
| --- | --- | --- |
| `net.xdob.vexra.adb.*` | ADB 状态机、DbStore、编码、LDB/Raft 集成 | 应保留为 ADB 核心 |
| `org.adb.command` / `expression` / `bnf` | H2 SQL parser / DDL / DML / 表达式分叉 | 应回归 H2 依赖 |
| `org.adb.engine` / `schema` / `table` / `index` | H2 核心元数据、表、索引、行模型分叉 | 短期会继续强耦合，需逐步收敛 |
| `org.adb.jdbc` / `server` / `tools` | JDBC、TCP/PG/Web、工具分叉 | 应回归 H2 原生入口 |
| `AdbTableEngine` / `AdbTable` / `AdbIndex` | ADB 差异化表/索引能力 | 可迁移到 H2 table provider，但需要稳定适配层 |

### 当前 H2 已有插件能力

| 能力 | 当前实现 |
| --- | --- |
| 插件描述 | `org.h2.api.H2Plugin` |
| provider 父接口 | `org.h2.api.PluginProvider` |
| table provider | `org.h2.api.TableEngineProvider`，由 `Schema.createTable()` 路由 |
| storage provider | `org.h2.api.StorageEngineProvider`，由 `Database` 打开期解析 |
| maintenance provider | `org.h2.api.StorageMaintenance` |
| 显式加载 | `PLUGIN_CLASSES` |
| jar / 目录加载 | `PLUGIN_PATHS` |
| ServiceLoader | `PLUGIN_SERVICE_LOADER=TRUE` 时启用 |
| 诊断 | `INFORMATION_SCHEMA.PLUGINS`、`PLUGIN_PROVIDERS`、`PLUGIN_CAPABILITIES` |
| storage id 持久化 | 数据库路径加 `.storage` 旁路元数据 |
| 内置 provider | `mvstore`、`mvstore_secondary` storage provider 与 `mvstore` table provider |

结论：对 ADB 来说，“插件能否装载、能否被 H2 建表路径识别”已经不是主要阻塞点。

## 核心约束

| 约束 | 说明 |
| --- | --- |
| Java 8 | 新增 SPI 和测试不得使用 Java 8 之后 API。 |
| H2 默认兼容 | 普通 `jdbc:h2:*`、默认 MVStore 建表、legacy `TableEngine` class-name 路径不得回退。 |
| ADB 不是纯 storage provider | ADB 表、索引、事务可见性、锁和 LDB 集成都在表/索引层深度参与。 |
| 当前 storage provider 仍要求 MVStore-backed | `Database` 打开路径仍要求 `StorageEngine instanceof MVStoreBackedStorageEngine`，非 MVStore storage engine 暂不能作为主数据库引擎承载完整 H2。 |
| 当前 table provider 仍返回 H2 内部 `Table` | ADB 如果迁移，需要继续实现或继承 H2 内部表/索引对象，尚不是完全稳定公开 SPI。 |
| H2 不开放 parser / optimizer | ADB 迁移应复用 H2 parser、JDBC、Server，不应继续维护平行分叉。 |

## 方案缺口

### P0 缺口

| 缺口 | 当前表现 | 对 ADB 的影响 | 建议 |
| --- | --- | --- | --- |
| 缺少稳定表存储适配层 | `TableEngineProvider.createTable()` 直接返回 `org.h2.table.Table` | ADB 仍要依赖 `TableBase`、`Index`、`Row`、`SearchRow`、`Value`、`SessionLocal` 等内部类型 | 增加“内部可用但受管”的 table/index 插件基线和测试，不急于公开为 stable API |
| 自定义表的锁/约束/索引协议未文档化 | 当前只说明 provider 创建表 | ADB 的行锁、死锁检查、索引 rebuild、row count 等容易踩 H2 内核隐式约束 | 补 `TableEngineProvider` 开发约束和兼容测试矩阵 |
| 非 MVStore storage engine 无法完整接入 | `Database` 强制 `MVStoreBackedStorageEngine` | ADB 如果想作为真正 storage engine 替换 MVStore，会被阻塞 | 短期按 table provider 迁移；storage provider 只做诊断/维护能力，不承诺替代主引擎 |
| 旧 `jdbc:adb:*` 兼容策略不属于 H2 | H2 只提供 `jdbc:h2:*` + 插件参数 | Vexra 测试和应用入口需要切换或加兼容层 | 在 ADB 侧定义弃用窗口，H2 不新增 `jdbc:adb:*` |

### P1 缺口

| 缺口 | 当前表现 | 建议 |
| --- | --- | --- |
| provider 初始化失败分类较粗 | 多数是数据库打开失败 | 增加错误消息和 trace 诊断分类，保持错误码兼容 |
| 依赖版本只做最小解析 | `PluginDependency.version` 当前主要用于诊断 | 后续再做 capability version 或 optional dependency |
| classloader 生命周期较短 | `PLUGIN_PATHS` 加载后尝试关闭 loader | 明确插件不得依赖 loader 持续持有可变资源；如 ADB 需要 jar 隔离长期持有，需单独设计 |
| 迁移测试缺 ADB-like provider | 当前测试多是 MVStore/fake provider | 在 H2 侧新增一个最小 ADB-like table provider 测试夹具，覆盖 table/index 内部契约 |

## 接口设计

### 当前可直接使用的接口

| 接口 | ADB 迁移用途 |
| --- | --- |
| `H2Plugin` | ADB 插件描述入口，声明 id、version、provider 列表、H2 版本范围和依赖。 |
| `TableEngineProvider` | 替代 `org.adb.AdbTableEngine`，由 SQL `ENGINE "adb"` 或默认 table engine 路由。 |
| `TableEngineContext` | 提供 `Database`、`Schema`、`StorageEngine`、trace、`WITH` 参数、持久化/只读状态。 |
| `StorageEngineProvider` | 只建议短期用于 MVStore-backed 验证或维护能力，不建议作为 ADB 主迁移路径。 |
| `StorageMaintenance` | 可承接 ADB compact / vacuum / LDB 维护动作，但必须通过 capability gate。 |

### 建议新增的 H2 侧受管接口/约束

第一轮不建议一次性新增大而全的公开 API，而是新增“受管内部扩展契约”文档和测试夹具。

| 项目 | 建议形态 | 说明 |
| --- | --- | --- |
| `TableProviderContractTest` | JUnit 测试基线 | 覆盖 provider 建表、DDL 输出、reopen、索引、scan、update、delete、drop。 |
| `TableEngineProvider` 文档扩展 | 开发者指南章节 | 明确 provider 可依赖哪些内部类型、哪些调用顺序不能假设。 |
| ADB-like sample provider | `src/test-plugin` 测试夹具 | 不引入 Vexra 依赖，用 fake KV table 模拟 ADB 行/索引行为。 |
| 内部 API 稳定性清单 | 文档表格 | 标记 `TableBase`、`Index`、`Row`、`SearchRow`、`Value`、`SessionLocal` 的迁移期稳定等级。 |

## 数据结构

### 迁移期 H2 侧需要保护的数据结构

| 类型 | 稳定性建议 | 说明 |
| --- | --- | --- |
| `CreateTableData` | 迁移期受管 | provider 建表入口必须依赖。 |
| `TableEngineContext` | 公开 SPI | 已是 provider 上下文，需保持 Java 8 兼容。 |
| `Table` / `TableBase` | 迁移期受管内部 API | ADB 表实现大概率需要继承或适配。 |
| `Index` | 迁移期受管内部 API | ADB 主键/二级索引需要实现。 |
| `Row` / `SearchRow` / `Value` | 迁移期受管内部 API | ADB codec 与扫描边界需要依赖。 |
| `SessionLocal` | 高风险内部 API | 涉及事务、权限、锁、可见性，不能轻易承诺稳定。 |
| `.storage` sidecar | H2 storage 元数据 | ADB 迁移如果只走 table provider，不应额外依赖 storage id 语义。 |

## 状态机

### H2 插件加载状态

| 状态 | 触发 | 说明 |
| --- | --- | --- |
| `BUILTIN_REGISTERED` | `BuiltinPlugins.register()` | 注册内置 MVStore provider。 |
| `CONFIGURED_LOADED` | `PLUGIN_CLASSES` / `PLUGIN_PATHS` | 加载显式插件。 |
| `SERVICE_LOADED` | `PLUGIN_SERVICE_LOADER=TRUE` | 可选自动发现。 |
| `READY` | provider 冲突、依赖、版本校验通过 | 后续建表可按 provider id 路由。 |
| `FAILED` | 类加载、依赖、版本、provider 冲突失败 | 数据库打开失败。 |

### ADB 迁移状态

| 状态 | 说明 | 允许回滚 |
| --- | --- | --- |
| `FORKED_H2` | 当前 `org.adb.*` 分叉发行 | 是 |
| `DUAL_PATH_PROTOTYPE` | 旧路径保留，新 H2 plugin 原型可加载 | 是 |
| `TABLE_PROVIDER_RUNNING` | ADB table provider 可在 H2 上跑 DDL/DML | 是 |
| `FORK_CODE_REMOVED` | 非差异化 H2 分叉代码删除 | 需要发布级回滚 |
| `PLUGIN_DISTRIBUTION` | ADB 作为插件包发布 | 需要兼容窗口 |

## 时序流程

### 目标建表流程

```mermaid
sequenceDiagram
  participant Client as JDBC Client
  participant H2 as org.h2.Driver/Database
  participant Registry as PluginRegistry
  participant Provider as AdbTableEngineProvider
  participant Table as AdbTable/AdbIndex
  participant LDB as DbStore/LDB

  Client->>H2: jdbc:h2:...;PLUGIN_CLASSES=...
  H2->>Registry: register built-in + ADB plugin
  Client->>H2: CREATE TABLE ... ENGINE "adb"
  H2->>Registry: find table provider "adb"
  H2->>Provider: createTable(CreateTableData, TableEngineContext)
  Provider->>Table: create AdbTable / indexes
  Table->>LDB: read/write encoded rows
```

## 异常处理

| 场景 | H2 侧行为 | 迁移要求 |
| --- | --- | --- |
| 插件类找不到 | 数据库打开失败 | 错误包含 class name。 |
| provider id 冲突 | 数据库打开失败 | 错误包含 provider type/id 和已有 plugin。 |
| 版本范围不匹配 | 数据库打开失败 | 错误包含 required / actual。 |
| `createTable()` 失败 | SQL 执行失败 | provider 需释放已分配的 ADB/LDB 资源。 |
| ADB 存储异常 | provider 转换为 H2 `DbException` | 保留 ADB 原始 cause 和关键诊断。 |
| 只读误写 | H2 / provider 拒绝 | ADB table/index 必须尊重 `TableEngineContext.isReadOnly()`。 |

## 幂等性

- 插件注册在一次数据库打开过程中只应成功一次；重复 provider id 必须失败，不允许静默覆盖。
- ADB provider 的建表失败路径必须可重试，不应留下半注册表对象或半初始化 LDB 列族。
- `supports(capability)` 必须无副作用，允许 H2 诊断表多次调用。
- ADB 表/索引 rebuild、drop、reopen 应以自身元数据为准，避免重复执行导致数据损坏。

## 回滚策略

| 阶段 | 回滚方式 |
| --- | --- |
| 原型阶段 | 移除 `PLUGIN_CLASSES`，继续使用旧 `org.adb.*` 分叉。 |
| table provider 阶段 | 保留旧 `jdbc:adb:*` 构件，发布说明中允许切回。 |
| 删除分叉前 | 必须冻结旧版本包、记录测试基线和数据兼容边界。 |
| 删除分叉后 | 通过回退 ADB 构件版本恢复，不在同一构件内混带两套 H2 内核。 |

## 兼容性

| 兼容项 | 判断 |
| --- | --- |
| H2 默认用户 | 不应受 ADB 插件迁移影响。 |
| legacy `TableEngine` | 必须继续保留，避免破坏已有 H2 用户扩展。 |
| `jdbc:adb:*` | 由 ADB 侧决定兼容层或弃用窗口，H2 不新增驱动前缀。 |
| ADB 旧数据 | 必须由 ADB 侧验证 codec、列族、索引元数据和 snapshot/restore。 |
| H2 版本升级 | 迁移期固定 H2 小版本，先补 table/index 契约测试再升级。 |

## 灰度/迁移

| 阶段 | 范围 | H2 侧产出 | ADB 侧预期 |
| --- | --- | --- | --- |
| M0 评审收口 | 不改代码 | 本文档、缺口清单 | 确认迁移边界 |
| M1 契约测试 | H2 `src/test-plugin` | ADB-like table provider 测试夹具 | 提供最小 ADB 表/索引行为清单 |
| M2 文档与稳定性清单 | H2 docs | table provider 内部 API 迁移期清单 | ADB 评估 import 替换 |
| M3 原型适配 | H2 必要小修 | provider 失败诊断、上下文补强 | ADB `H2Plugin + TableEngineProvider` 原型 |
| M4 回归基线 | H2 plugin check | 覆盖 DDL/DML/reopen/drop/diagnostics | ADB 双路径测试 |
| M5 正式迁移 | 按需补缺口 | 不扩大 parser/JDBC 扩展范围 | 删除非差异化 H2 分叉 |

## 测试方案

### H2 侧测试

| 测试编号 | 类型 | 覆盖 |
| --- | --- | --- |
| `T-PLUGIN-ADB-LIKE-TABLE-01` | JUnit | ADB-like provider 可通过 `ENGINE "adb_like"` 建表。 |
| `T-PLUGIN-ADB-LIKE-CRUD-01` | JUnit | insert / update / delete / scan / count 基础行为。 |
| `T-PLUGIN-ADB-LIKE-INDEX-01` | JUnit | 主键和二级索引 create/drop/rebuild 基线。 |
| `T-PLUGIN-ADB-LIKE-REOPEN-01` | JUnit | 持久库 reopen 后表元数据和索引可用。 |
| `T-PLUGIN-ADB-LIKE-FAILURE-01` | JUnit | provider 建表失败释放资源且可重试。 |
| `T-PLUGIN-ADB-LIKE-DIAGNOSTIC-01` | JUnit | 插件诊断表可展示 ADB-like provider 来源和 capability。 |

### ADB 侧测试建议

| 类型 | 覆盖 |
| --- | --- |
| 连接兼容 | `jdbc:h2:*;PLUGIN_CLASSES=...` 打开 ADB 数据库。 |
| DDL/DML | 建表、主键、二级索引、扫描、更新、删除。 |
| 事务与锁 | 并发写、读写冲突、死锁/超时。 |
| 恢复 | checkpoint、reopen、snapshot/restore、异常重启。 |
| 双路径 | 旧 `jdbc:adb:*` 与新 H2 plugin 路径在迁移期并行验证。 |

## 风险点

| 风险 | 等级 | 检测 | 缓解 |
| --- | --- | --- | --- |
| ADB 继续深依赖 H2 内部类 | P0 | import 清单、编译破坏 | 固定 H2 版本，补 table/index 契约测试。 |
| 误删 ADB 核心事务/编码逻辑 | P0 | ADB DDL/DML/恢复测试失败 | 建立保留白名单。 |
| H2 SPI 被过早宣称 stable | P1 | 升级后第三方插件破坏 | 文档标记迁移期受管，不承诺长期稳定。 |
| `jdbc:adb:*` 兼容预期不清 | P1 | 用户连接失败 | ADB 侧发布弃用窗口或适配层。 |
| 非 MVStore storage 诉求扩大范围 | P1 | 触及 `Database` 打开主路径 | 本轮只支持 table provider 迁移，不承诺替代主引擎。 |

## 分阶段实施计划

### H2-P0：固定评审结论

- [x] 新增本文档。
- [x] 将 ADB 迁移定位为 table provider 优先，而不是 storage engine 替换优先。
- [x] 明确 H2 不新增 parser、optimizer、JDBC driver 扩展点。

验收：

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat runPluginArchitectureCheck
```

### H2-P1：补 ADB-like table provider 契约测试

- [x] 在 `h2/src/test-plugin` 新增 table provider 契约测试夹具。
- [x] 覆盖 provider 注册、建表、`WITH` 参数、schema default params、schema 上下文、基础 DML 和诊断表。
- [x] 使用 fake provider，不引入 Vexra 依赖。
- [ ] 自定义 `Table` / `Index` 的 KV-like 行存储、reopen、drop 深度测试留到 ADB 原型提供最小行为清单后再补。

验收：

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat runPluginArchitectureCheck
```

### H2-P2：补 table/index 迁移期稳定性文档

- [x] 在插件开发者指南中新增“自定义 Table / Index 迁移期约束”。
- [x] 标出 `TableBase`、`Index`、`Row`、`SearchRow`、`Value`、`SessionLocal` 的风险等级。
- [x] 明确 provider 不应依赖 parser、optimizer、JDBC 内部实现细节。
- [x] 中英文文档同步更新。

验收：

- 文档中英文同步。
- 无替换字符。

### H2-P3：按 ADB 原型反馈补上下文和诊断

- [x] 若 ADB 原型证明需要更多上下文字段，优先补 `TableEngineContext` 契约测试，避免 provider 直接挖 `Database` 内部状态。
- [x] 明确 `createTable()` 失败诊断要求：保留 cause，包含 provider id、table name、参数摘要。
- [x] 当前已通过契约测试覆盖 `Database`、`Schema`、`StorageEngine`、trace、参数、持久化/只读状态的可见性。
- [x] 不在本阶段扩大 storage engine 主路径。
- [ ] 若 ADB 原型后续证明确实需要新上下文字段，再单独新增 `TableEngineContext` 方法和二进制兼容评估。

验收：

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat compileJava
```

### H2-P4：非 MVStore 主路径前置契约

- [x] 明确当前 storage provider 已完成注册、加载、诊断、storage id、缺失 provider、只读降级和 MVStore-backed 多 provider 验证。
- [x] 明确任意非 MVStore 替代主路径还缺 system catalog provider 契约，因为系统元数据表、LOB、事务日志、临时结果仍依赖 `Store`。
- [x] 将非 MVStore 主路径从“隐含缺口”转为单独实施阶段，不混入 ADB-P1。
- [x] 新增 `SystemCatalogProvider` / `SystemCatalogContext` 前置 SPI，允许 system catalog provider 注册和诊断。
- [ ] 后续补系统表、LOB、事务日志和临时结果契约后，再允许生产主路径 storage engine 摆脱 MVStore-backed 约束。

验收：

- 文档明确 system catalog、LOB、事务日志、临时结果的所有权。
- 新增非 MVStore system catalog provider 契约测试。
- 默认 H2 MVStore 行为、ADB table provider 原型行为均不回退。

### ADB-P1：ADB 最小插件原型

- ADB 侧新增 `AdbH2Plugin implements H2Plugin`。
- 新增 `AdbTableEngineProvider implements TableEngineProvider`。
- 保留旧 `org.adb.*` 路径，不删除代码。
- 先跑通 `jdbc:h2:*;PLUGIN_CLASSES=...` 下的最小建表。

### ADB-P2：ADB 表/索引 import 迁移

- 将 `AdbTable`、`AdbPrimaryIndex`、`AdbSecondaryIndex` 等从 `org.adb.*` 逐步迁移到 `org.h2.*`。
- 保留 ADB 自有 codec、Txn、LDB、Raft 集成。
- 建立旧路径和新路径的 DDL/DML 对照测试。

### ADB-P3：删除非差异化 H2 分叉

- 删除 parser、JDBC、Server、tools、Web、MVStore 等非 ADB 差异化代码。
- 将 ADB 构件定位为 H2 插件包。
- 发布兼容说明和回滚窗口。

## 开放问题

| 问题 | 影响 | 建议处理 |
| --- | --- | --- |
| ADB 是否需要保留 `jdbc:adb:*` | 影响用户连接兼容 | ADB 侧决策，H2 不新增驱动前缀。 |
| ADB 是否需要真正替换 storage engine | 影响 H2 `Database` 主路径 | 本轮先不做，除非 ADB 明确不走 table/index 迁移。 |
| ADB 对 `SessionLocal` 的依赖深度 | 影响 SPI 稳定性 | 先做 import 清单和最小原型。 |
| ADB 是否需要 H2 Server 包装 | 影响运维入口 | 优先改用 H2 原生 Server。 |

## 结论

当前 H2 插件化实现已经足够支撑 ADB 启动最小插件原型：插件加载、table provider 路由、诊断表、版本/依赖校验和文档入口都已具备。

真正的迁移缺口不在“插件能不能加载”，而在“ADB 自定义表/索引能否在不维护 H2 分叉的情况下稳定依赖 H2 内核”。因此 H2 侧下一步应优先补 ADB-like table provider 契约测试和迁移期内部 API 稳定性文档；ADB 侧则应先做双路径原型，再逐步删除 `org.adb.*` 分叉。
