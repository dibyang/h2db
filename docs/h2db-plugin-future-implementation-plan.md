# H2 插件化 F0-F9 可追踪实施计划

## 范围

本计划承接 [h2db-plugin-architecture-future-roadmap.md](h2db-plugin-architecture-future-roadmap.md)，用于推进阶段一之后暂缓的插件化能力。每个阶段完成后必须本地提交，生产代码变更必须同步补 JUnit 测试。

F0-F9 已完成第一轮骨架落地。下一轮不再把暂缓能力散落在口头讨论里，统一以 R1-R9 继续追踪：每个 R 阶段进入开发前必须补齐任务清单、测试资产登记和验收命令。

## 总体状态

| 阶段 | 目标 | 状态 |
| --- | --- | --- |
| F0 | 建立后续阶段可追踪计划 | [x] |
| F1 | 插件诊断与 registry 可观测性 | [x] |
| F2 | storage engine id 持久化边界 | [x] |
| F3 | 缺失 storage 插件处理策略 | [x] |
| F4 | 显式外部插件加载 | [x] |
| F5 | 收敛第三方 Storage SPI 最小面 | [x] |
| F6 | 插件依赖、版本和冲突治理 | [x] |
| F7 | 安全边界第一版 | [x] |
| F8 | S1/S2 maintenance 接入 | [x] |
| F9 | 自动发现、示例和生态补齐 | [x] |
| R1 | SQL 元数据诊断产品化 | [x] |
| R2 | storage engine id 真实持久化 | [x] |
| R3 | 第二个真实 Storage Engine | [x] |
| R4 | 缺失 storage 插件只读降级 | [x] |
| R5 | S1/S2 真实维护实现接入 | [x] |
| R6 | 完整外部插件隔离 | [x] |
| R7 | 插件依赖和版本范围增强 | [x] |
| R8 | ServiceLoader 真实资源发现样例 | [x] |
| R9 | 插件文档和帮助资源产品化 | [x] |

## 统一门禁

| 门禁 | 要求 |
| --- | --- |
| G0 兼容 | 默认 MVStore、旧库、legacy `TableEngine` 行为不回退。 |
| G1 测试 | 每个生产代码阶段必须新增或更新 JUnit；只有 JUnit 不自然覆盖的场景，才补 H2 `TestBase` 或 Gradle 专项入口。 |
| G2 构建 | 阶段提交前运行 `.\gradlew.bat test` 和必要的 `.\gradlew.bat compileJava`；涉及 MVStore 恢复或空间回收时追加专项入口。 |
| G3 回滚 | 每个阶段能通过单独 commit 回滚，不混入下一阶段逻辑。 |
| G4 文档 | 每个阶段完成后更新本计划状态、测试资产登记和非 JUnit 入口说明。 |
| G5 对外文档 | 面向用户或插件作者的公开文档必须同时提供中文和英文副本，英文副本使用 `.en.md` 后缀。 |

## 测试统一管理策略

后续实现仍以 JUnit 为主，但 H2 仓库已有大量 `TestBase` / `TestAll` 风格测试，以及 MVStore 专项 Gradle 入口。它们不能游离在计划之外，必须按测试资产纳管。

### 测试分层

| 层级 | 类型 | 目录或入口 | 适用场景 | 阶段门禁 |
| --- | --- | --- | --- | --- |
| L0 | JUnit 插件专项测试 | `h2/src/test-plugin`，`.\gradlew.bat runPluginArchitectureCheck` | 插件 registry、metadata、loader、capability、SPI 纯逻辑 | 默认必须 |
| L1 | H2 传统兼容测试 | `h2/src/test/org/h2/test`，`TestBase` / `TestAllJunit` / `TestAll` | SQL 行为、JDBC 兼容、旧测试框架已有覆盖点 | 触及 SQL 或既有兼容行为时必须登记 |
| L2 | Gradle 专项入口 | `runMvStoreRecoveryCheck`、`runMvStoreSpaceReclamationCheck`、后续 `runPlugin*Check` | crash-safe、文件格式、空间回收、长流程或需要单独 JVM 的场景 | 触及对应领域时必须运行 |
| L3 | 扩展 CI / 长跑 | `TestAll` 全量或随机、压力、故障注入 | 发布前或高风险磁盘格式变更 | 不作为每阶段默认门禁，但必须写明是否需要 |

### 非 JUnit 测试纳管规则

- 能用 JUnit 简洁表达的插件逻辑，优先放入 `h2/src/test-plugin`，并由 `test` 任务间接运行。
- 需要复用 H2 既有 SQL/JDBC/兼容测试夹具时，允许新增或修改 `TestBase` 测试，但必须在本计划登记测试编号、文件位置和运行入口。
- 需要独立 JVM、故障注入、磁盘格式、恢复或空间回收验证时，允许新增 Gradle 专项入口，命名为 `run<领域>Check`，并在 `test` 是否依赖该入口的问题上单独拍板。
- 已存在的 `runMvStoreRecoveryCheck` 和 `runMvStoreSpaceReclamationCheck` 作为 S1/S2 相关阶段的受管测试入口，不再视为零散手工命令。
- 每个阶段提交前必须在最终说明里列出实际运行过的测试命令；未运行的专项入口要说明原因。

### 已纳管的非 JUnit 入口

| 入口 | 类型 | 覆盖范围 | 触发阶段 | 管理要求 |
| --- | --- | --- | --- | --- |
| `runMvStoreRecoveryCheck` | Gradle `JavaExec` | MVStore recovery、损坏恢复、crash-safe 相关路径 | R2、R5、任何文件格式或恢复变更 | 触及持久化或恢复语义时运行 |
| `runMvStoreSpaceReclamationCheck` | Gradle `JavaExec` | MVStore 空间回收、shadow compact、在线回收基础设施 | R5、S2 真实实现 | 触及 S1/S2 空间回收时运行 |
| `TestAllJunit` / `TestAll` | H2 传统测试聚合 | 既有 SQL/JDBC/兼容回归 | R1、R3、R4、R9 中改变 SQL 或帮助资源时 | 作为扩展验证登记，不强制每阶段全跑 |
| `TestBase` 单测类 | H2 传统测试 | 具体 SQL/JDBC/存储兼容场景 | 需要进入既有测试体系时 | 新增或修改必须登记测试编号 |

### 测试资产登记表

| 测试编号 | 类型 | 计划位置 | 命令 | 覆盖阶段 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `T-PLUGIN-R1-SQL-METADATA-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R1 | [x] |
| `T-PLUGIN-R1-CAPABILITY-ROWS-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R1 | [x] |
| `T-PLUGIN-R2-STORAGE-ID-PERSIST-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R2 | [x] |
| `T-PLUGIN-R2-UPGRADE-ROLLBACK-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R2 | [x] |
| `T-PLUGIN-R3-SECOND-STORAGE-CRUD-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R3 | [x] |
| `T-PLUGIN-R3-COMPAT-SQL-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R3 | [x] |
| `T-PLUGIN-R4-READONLY-DEGRADE-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R4 | [x] |
| `T-PLUGIN-R4-NO-WRITE-ON-DEGRADE-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R4 | [x] |
| `T-PLUGIN-R5-S1-COMPACT-BRIDGE-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R5 | [x] |
| `T-PLUGIN-R5-S2-ONLINE-VACUUM-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R5 | [x] |
| `T-PLUGIN-R6-CLASSLOADER-ISOLATION-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R6 | [x] |
| `T-PLUGIN-R6-RESOURCE-CLOSE-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R6 | [x] |
| `T-PLUGIN-R7-VERSION-RANGE-SEMANTICS-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R7 | [x] |
| `T-PLUGIN-R7-DEPENDENCY-ORDER-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R7 | [x] |
| `T-PLUGIN-R8-SERVICE-RESOURCE-01` | JUnit | `h2/src/test-plugin` + test resources | `.\gradlew.bat runPluginArchitectureCheck` | R8 | [x] |
| `T-PLUGIN-R8-SAMPLE-PLUGIN-01` | JUnit | `h2/src/test-plugin` + test resources | `.\gradlew.bat runPluginArchitectureCheck` | R8 | [x] |
| `T-PLUGIN-R9-HELP-RESOURCE-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | R9 | [x] |
| `T-PLUGIN-R9-DEVELOPER-GUIDE-01` | 文档检查 | `docs/h2db-plugin-developer-guide.md`、`docs/h2db-plugin-developer-guide.en.md` | 文档评审 | R9 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-REGISTER-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-CREATE-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-CRUD-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-UPDATE-DELETE-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-INDEX-PLAN-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-DROP-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-SCRIPT-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-PARAMS-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-SCHEMA-CONTEXT-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 / H2-P3 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-SCHEMA-PARAMS-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P1 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-CONTEXT-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P3 | [x] |
| `T-PLUGIN-F5-TABLE-SPI-DIAGNOSTIC-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P3 | [x] |
| `T-PLUGIN-P4-SYSTEM-CATALOG-BUILTIN-DIAGNOSTIC-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P4 | [x] |
| `T-PLUGIN-P4-SYSTEM-CATALOG-REGISTER-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P4 | [x] |
| `T-PLUGIN-P4-SYSTEM-CATALOG-DIAGNOSTIC-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P4 | [x] |
| `T-PLUGIN-P4-SYSTEM-CATALOG-MISSING-01` | JUnit | `h2/src/test-plugin` | `.\gradlew.bat runPluginArchitectureCheck` | H2-P4 | [x] |

## 阶段任务

### F0 后续规划收口

- [x] 新增本文档，作为 F0-F9 开发追踪入口。
- [x] 保留路线图文档作为设计依据。
- [x] 固定阶段名使用 `F*`，与阶段一 `P0-P6` 区分。

### F1 插件诊断与 Registry 可观测性

- [x] 新增 registry 快照 API，避免 SQL/测试直接依赖内部 `HashMap`。
- [x] 提供 provider/capability 诊断数据结构。
- [x] 增加 JUnit 覆盖 provider 来源、capability 展示、冲突诊断。
- [x] 本阶段先提供内部诊断入口，`INFORMATION_SCHEMA` 接入保留给后续 SQL 元数据阶段。

测试编号：

- [x] `T-PLUGIN-F1-INFO-SCHEMA-01`
- [x] `T-PLUGIN-F1-CAPABILITY-LIST-01`
- [x] `T-PLUGIN-F1-CONFLICT-DIAGNOSTIC-01`

### F2 Storage Engine Id 持久化边界

- [x] 新增 storage engine id 解析策略，旧库缺省为 `mvstore`。
- [x] 新增显式 storage engine id 与数据库实际 id 的校验入口。
- [x] 文档化真正磁盘格式持久化仍需单独 RFC。
- [x] 增加 JUnit 覆盖旧库默认、显式不匹配、回滚开关。

测试编号：

- [x] `T-PLUGIN-F2-OLD-DB-DEFAULT-01`
- [x] `T-PLUGIN-F2-STORAGE-ID-MISMATCH-01`
- [x] `T-PLUGIN-F2-ROLLBACK-01`

### F3 缺失 Storage 插件处理策略

- [x] 明确缺失 storage provider 默认拒绝打开。
- [x] 错误消息包含 storage engine id 和 provider type。
- [x] 不自动 fallback 到 `mvstore`。
- [x] 增加 JUnit 覆盖缺失、只读降级占位、无隐式 fallback。

测试编号：

- [x] `T-PLUGIN-F3-MISSING-STORAGE-FAIL-01`
- [x] `T-PLUGIN-F3-MISSING-STORAGE-READONLY-01`
- [x] `T-PLUGIN-F3-NO-MVSTORE-FALLBACK-01`

### F4 显式外部插件加载

- [x] 选择第一版显式配置入口：`PLUGIN_CLASSES`。
- [x] 加载实现 `H2Plugin` 的外部类并注册 provider。
- [x] 加载失败时显式报错。
- [x] 内置 provider 冲突不可覆盖。

测试编号：

- [x] `T-PLUGIN-F4-LOAD-CLASS-01`
- [x] `T-PLUGIN-F4-LOAD-FAIL-01`
- [x] `T-PLUGIN-F4-BUILTIN-CONFLICT-01`

### F5 稳定第三方 Storage SPI 最小面

- [x] 收敛 storage SPI 的最小生命周期和异常语义。
- [x] 增加 fake storage provider 编译/注册测试。
- [x] 明确 `supports()` 无副作用。
- [x] 文档化 experimental/stable 边界。

测试编号：

- [x] `T-PLUGIN-F5-SPI-COMPAT-01`
- [x] `T-PLUGIN-F5-CAPABILITY-PURE-01`
- [x] `T-PLUGIN-F5-LIFECYCLE-01`

### F6 插件依赖、版本和冲突治理

- [x] 增加插件描述元数据：H2 版本范围、依赖。
- [x] 加载时校验版本范围和依赖。
- [x] 冲突错误包含 plugin id、provider type/id。
- [x] 增加 JUnit 覆盖版本不匹配、依赖缺失、冲突治理。

测试编号：

- [x] `T-PLUGIN-F6-H2-VERSION-RANGE-01`
- [x] `T-PLUGIN-F6-DEPENDENCY-MISSING-01`
- [x] `T-PLUGIN-F6-PROVIDER-CONFLICT-01`

### F7 安全边界第一版

- [x] 限制外部插件 provider type 白名单。
- [x] 增加敏感配置脱敏工具和测试。
- [x] 明确 classloader 关闭/释放占位策略。
- [x] 文档化不支持热卸载。

测试编号：

- [x] `T-PLUGIN-F7-SENSITIVE-TRACE-01`
- [x] `T-PLUGIN-F7-FORBIDDEN-CAPABILITY-01`
- [x] `T-PLUGIN-F7-CLASSLOADER-CLOSE-01`

### F8 S1/S2 Maintenance 接入

- [x] 扩展 `StorageMaintenanceResult` 表达 skipped / unsupported / success。
- [x] MVStore maintenance 暴露 S1 compact 能力边界。
- [x] S2 入口通过 capability gate 调用。
- [x] 增加 JUnit 覆盖 unsupported 和 MVStore-only 行为。

测试编号：

- [x] `T-PLUGIN-F8-S1-MAINTENANCE-01`
- [x] `T-PLUGIN-F8-S2-VACUUM-GATE-01`
- [x] `T-PLUGIN-F8-MVSTORE-S2-ONLY-01`

### F9 自动发现、示例和生态补齐

- [x] `ServiceLoader` 默认关闭。
- [x] 显式开启时发现 `H2Plugin`。
- [x] 新增最小示例插件或测试内示例。
- [x] 补充插件开发说明。

测试编号：

- [x] `T-PLUGIN-F9-SERVICELOADER-OFF-01`
- [x] `T-PLUGIN-F9-SERVICELOADER-ON-01`
- [x] `T-PLUGIN-F9-SAMPLE-COMPILE-01`

## R1-R9 原始补齐清单（归档）

R1-R9 是在 F0-F9 骨架之上的真实产品化和工程化阶段，当前已按最小可落地范围完成，并为每个阶段建立了 JUnit 覆盖和本地提交。下面保留原始任务拆解，用于后续复盘和继续深化。

本节中的 `[ ]` 保留为原始拆解记录，不再表示当前总体状态；当前状态以本文前面的“总体状态”和“测试资产登记表”为准。若后续继续深化某个 R 阶段，应先把对应条目迁出归档区，再重新补齐任务清单、测试资产和验收命令。

### R1 SQL 元数据诊断产品化

- [ ] 新增插件诊断 SQL 元数据入口，候选为 `INFORMATION_SCHEMA.PLUGINS`、`INFORMATION_SCHEMA.PLUGIN_PROVIDERS`、`INFORMATION_SCHEMA.PLUGIN_CAPABILITIES`。
- [ ] 明确每张表的字段、排序、是否包含内置 provider、是否暴露外部插件来源。
- [ ] 保证 SQL 入口只读，不允许通过元数据表修改 registry。
- [ ] 增加 trace / 错误消息和 SQL 元数据之间的关联字段。

测试要求：

- [ ] `T-PLUGIN-R1-SQL-METADATA-01`
- [ ] `T-PLUGIN-R1-CAPABILITY-ROWS-01`

待拍板：

- 诊断表是否进入标准 `INFORMATION_SCHEMA`，还是先使用 H2 私有表名。
- capability 展示是一行一个 capability，还是 provider 行内聚合。

### R2 Storage Engine Id 真实持久化

- [ ] 为 storage engine id 写单独 RFC，明确 file header、sidecar、MVStore meta map 的取舍。
- [ ] 实现新库写入 storage engine id，旧库缺省 `mvstore`。
- [ ] 打开数据库时校验显式 storage id 与持久化 id。
- [ ] 明确 backup / restore / rename / recovery 场景是否携带该 id。
- [ ] 明确回滚策略：禁用新能力后旧 MVStore 库仍可打开。

测试要求：

- [ ] `T-PLUGIN-R2-STORAGE-ID-PERSIST-01`
- [ ] `T-PLUGIN-R2-UPGRADE-ROLLBACK-01`
- [ ] 涉及恢复语义时运行 `.\gradlew.bat runMvStoreRecoveryCheck`。

待拍板：

- storage id 是强约束还是提示信息。
- 是否把 provider version 写入持久化元数据。
- sidecar 文件是否允许参与数据库备份。

### R3 第二个真实 Storage Engine

- [ ] 选择第二 storage engine 的最小真实目标：可以是只读实验引擎、内存持久化边界验证引擎，或面向后续产品能力的真实引擎。
- [ ] 通过 `StorageEngineProvider` 完成注册、打开、关闭、能力声明。
- [ ] 覆盖基本 DDL/DML 或明确第一版只支持的子集。
- [ ] 验证默认 MVStore 和 legacy `TableEngine` 行为不回退。

测试要求：

- [ ] `T-PLUGIN-R3-SECOND-STORAGE-CRUD-01`
- [ ] `T-PLUGIN-R3-COMPAT-SQL-01`

待拍板：

- 第二 storage engine 的目标是否必须真实落盘。
- 第一版是否允许只读或功能子集。

### R4 缺失 Storage 插件只读降级

- [ ] 在默认拒绝打开之外，实现显式只读降级开关。
- [ ] 降级必须由持久化 metadata 和 provider capability 共同约束，不能静默 fallback 到 `mvstore`。
- [ ] 降级打开后禁止写入、compact、vacuum 等会改变文件状态的操作。
- [ ] 错误消息必须包含缺失 provider、storage id、降级原因和建议配置。

测试要求：

- [ ] `T-PLUGIN-R4-READONLY-DEGRADE-01`
- [ ] `T-PLUGIN-R4-NO-WRITE-ON-DEGRADE-01`

待拍板：

- 只读降级是否必须由原 provider 显式声明支持。
- 降级失败使用新错误码还是复用现有错误码。

### R5 S1/S2 真实维护实现接入

- [ ] 把 S1 已有 compact / recovery / manifest 能力映射到 `StorageMaintenance`。
- [ ] 将 S2 在线空间回收通过 capability gate 接入，不绕过插件维护边界。
- [ ] 明确 `StorageMaintenanceResult` 的进度、跳过原因、失败原因和统计字段。
- [ ] 工具层或 SQL/API 入口调用前必须先检查 provider capability。

测试要求：

- [ ] `T-PLUGIN-R5-S1-COMPACT-BRIDGE-01`
- [ ] `T-PLUGIN-R5-S2-ONLINE-VACUUM-01`
- [ ] 必须运行 `.\gradlew.bat runMvStoreSpaceReclamationCheck`；涉及恢复交互时追加 `.\gradlew.bat runMvStoreRecoveryCheck`。

待拍板：

- S2 第一版是否只支持 MVStore。
- `vacuumOnline()` 是否需要 options / progress callback。
- crash-safe publish 的边界是否归入 R5，还是另起 S2 专项设计。

### R6 完整外部插件隔离

- [ ] 引入独立插件 classloader 或明确继续复用应用 classpath 的边界。
- [ ] 定义 jar 加载、资源释放、重复加载、关闭顺序和泄漏检查。
- [ ] 限制插件访问核心扩展点，仅开放 table/storage provider。
- [ ] 梳理敏感配置脱敏和异常传播规则。

测试要求：

- [ ] `T-PLUGIN-R6-CLASSLOADER-ISOLATION-01`
- [ ] `T-PLUGIN-R6-RESOURCE-CLOSE-01`

待拍板：

- 第一版是否加载外部 jar，还是只加载当前 classpath 中的类。
- 是否需要 classloader 泄漏检测作为专项入口。

### R7 插件依赖和版本范围增强

- [ ] 明确版本范围语法：Maven range、SemVer，或 H2 自定义最小/最大版本。
- [ ] 支持插件依赖拓扑排序、缺失依赖诊断、循环依赖诊断。
- [ ] 评估是否支持 optional dependency 和 capability version。
- [ ] 冲突诊断统一输出 plugin id、plugin version、provider type/id、来源。

测试要求：

- [ ] `T-PLUGIN-R7-VERSION-RANGE-SEMANTICS-01`
- [ ] `T-PLUGIN-R7-DEPENDENCY-ORDER-01`

待拍板：

- 是否引入第三方版本解析库，还是手写最小解析器。
- optional dependency 第一版是否进入范围。

### R8 ServiceLoader 真实资源发现样例

- [ ] 为测试或样例补充真实 `META-INF/services/org.h2.api.H2Plugin` 资源。
- [ ] 保持 `ServiceLoader` 默认关闭，只在显式配置时启用。
- [ ] 新增一个最小外部插件样例，覆盖注册、能力声明、加载失败诊断。
- [ ] 明确样例目录是否参与默认构建。

测试要求：

- [ ] `T-PLUGIN-R8-SERVICE-RESOURCE-01`
- [ ] `T-PLUGIN-R8-SAMPLE-PLUGIN-01`

待拍板：

- 样例插件放在主仓库、独立 sample 目录，还是单独子工程。
- test resources 是否需要为 `pluginTest` source set 单独建目录。

### R9 插件文档和帮助资源产品化

- [ ] 补充插件开发者指南：接口稳定性、capability 命名、错误码、测试模板。
- [ ] 评估是否把插件相关命令、函数或诊断表加入 `help.csv`。
- [ ] 补充网站 / README / docs 中的用户可见说明。
- [ ] 明确 experimental 与 stable API 的发布承诺。

测试要求：

- [ ] `T-PLUGIN-R9-HELP-RESOURCE-01`
- [ ] `T-PLUGIN-R9-DEVELOPER-GUIDE-01`

待拍板：

- 插件 SPI 是否在下一版对外宣称 stable。
- `help.csv` 是否作为 R9 的强制交付。

## 验收命令

每个代码阶段至少运行：

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat test
.\gradlew.bat compileJava
```

涉及 MVStore 持久化、恢复或空间回收时追加：

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

只改文档的阶段可以不跑 Gradle，但必须在提交说明或最终说明中明确“文档变更，未运行测试”。

### Table SPI 契约测试执行快照

- 交付项：
  - 在 `h2/src/test-plugin` 新增表 SPI 契约验证（新文件 `TableSpiContractTest`）。
  - 验证能力点：注册、通过 provider 创建表、参数透传、schema 上下文、DDL/DML 基础链路。
  - 覆盖默认 `ENGINE` 解析 + `WITH` 参数注入 + schema 默认参数注入 + 诊断表可观测。
- 验收测试：
  - `T-PLUGIN-F5-TABLE-SPI-REGISTER-01`
  - `T-PLUGIN-F5-TABLE-SPI-CREATE-01`
  - `T-PLUGIN-F5-TABLE-SPI-CRUD-01`
  - `T-PLUGIN-F5-TABLE-SPI-UPDATE-DELETE-01`
  - `T-PLUGIN-F5-TABLE-SPI-INDEX-PLAN-01`
  - `T-PLUGIN-F5-TABLE-SPI-DROP-01`
  - `T-PLUGIN-F5-TABLE-SPI-SCRIPT-01`
  - `T-PLUGIN-F5-TABLE-SPI-PARAMS-01`
  - `T-PLUGIN-F5-TABLE-SPI-SCHEMA-CONTEXT-01`
  - `T-PLUGIN-F5-TABLE-SPI-SCHEMA-PARAMS-01`
  - `T-PLUGIN-F5-TABLE-SPI-CONTEXT-01`
  - `T-PLUGIN-F5-TABLE-SPI-DIAGNOSTIC-01`
### H2-P0 至 H2-P3 收口快照

| 阶段 | 状态 | 产出 |
| --- | --- | --- |
| H2-P0 | [x] | 固定插件迁移优先级：table provider 优先，非 MVStore 主路径单独推进。 |
| H2-P1 | [x] | `TableSpiContractTest` 补齐 table provider 契约测试。 |
| H2-P2 | [x] | `h2db-plugin-developer-guide.md` / `.en.md` 补 table/index 迁移期稳定性。 |
| H2-P3 | [x] | 契约测试覆盖上下文和诊断；文档明确 provider 原型反馈规则。 |

### P10：Table / Index 迁移期适配层执行快照

- 交付项：
  - 新增 `TableProviderSupport`，集中提供只读 gate、storage engine 类型校验和 `createTable()` 失败诊断包装。
  - `Schema` 对 table provider 普通运行时异常进行统一包装，保留原始 cause，并输出 provider/table/params。
  - `TableSpiContractTest` 补充 provider 失败诊断和只读 gate 覆盖。
- 当前边界：
  - 不公开完整 `Index` SPI。
  - 不改变旧 `TableEngine` class-name 兼容路径。
  - 自定义 `Table` / `Index` 仍是迁移期受管内部 API，长期二进制稳定性后续再评估。

### 非 MVStore 主路径收口结论

当前 storage provider 已支持注册、加载、诊断、storage id 持久化、缺失 provider 策略、只读降级和 MVStore-backed 多 provider 验证。真正非 MVStore 主路径尚不能只靠 `StorageEngineProvider` 完成，因为 H2 系统元数据表、LOB、事务日志、临时结果和现有 `MVTable` 路径仍依赖 `Store`。

本轮已新增 `SystemCatalogProvider` / `SystemCatalogContext` 前置 SPI，并允许外部 `system_catalog` provider 通过安全白名单、注册和诊断表展示。下一实施阶段需要补系统表、LOB、事务日志和临时结果契约测试，再放开非 MVStore 作为生产主路径。该阶段不涉及 classloader 长生命周期隔离。
