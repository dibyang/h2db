# H2 插件化架构改造实施计划（阶段一）

## 意图

本计划用于推进插件化架构阶段一改造：先建立最小插件内核，让内置 MVStore storage/table 能力通过统一 provider/capability 路径接入，同时保持现有 SQL、磁盘格式、默认建表和 legacy `TableEngine` 行为兼容。

设计依据见 [h2db-plugin-architecture-rfc.md](h2db-plugin-architecture-rfc.md)。

## 范围

| 范围 | 内容 |
| --- | --- |
| In | SPI 骨架、内部 `PluginRegistry`、内置 MVStore storage/table provider、legacy `TableEngine` fallback、capability 查询、`StorageMaintenance` 边界、兼容测试。 |
| Out | 外部插件加载、第三方稳定 Storage SPI、storage engine id 持久化、S1 实现迁移、S2 实现、安全沙箱、热插拔、插件市场。 |

## 总体节奏

| 阶段 | 目标 | 状态 |
| --- | --- | --- |
| P0 文档与门禁 | 固定阶段一范围、测试编号、回滚边界 | [x] |
| P1 SPI 与 Registry 骨架 | 新增接口和内部注册中心，不接业务路径 | [x] |
| P2 MVStore Storage 适配 | 包装现有 `Store`，保留 `Database.getStore()` 过渡 | [x] |
| P3 MVStore Table Provider | 内置建表 provider 可调用现有 `Store.createTable()` | [x] |
| P4 默认建表路径切换 | `Schema.createTable()` 走 provider，legacy fallback 兼容 | [x] |
| P5 Capability 与 Maintenance 边界 | 暴露能力查询和 S2 gate，不迁移 S1 实现 | [ ] |
| P6 回归与文档收口 | 补齐测试、兼容说明和后续 backlog | [ ] |

## 执行门禁

| 门禁 | 要求 |
| --- | --- |
| G0 兼容门禁 | 无 `ENGINE` 普通建表 SQL 输出、元数据、重启后行为不变。 |
| G1 legacy 门禁 | 现有 `CREATE TABLE ... ENGINE className` 和 `DEFAULT_TABLE_ENGINE` 行为不变。 |
| G2 内置门禁 | 内置 `mvstore` provider id 不可被覆盖。 |
| G3 能力门禁 | S2 / compact 等维护入口不得绕过 capability 查询。 |
| G4 回滚门禁 | 阶段一不改磁盘格式；回滚代码后旧库仍按 MVStore 打开。 |
| G5 编译门禁 | `h2/` 下 `.\gradlew.bat compileJava` 通过。 |
| G6 测试门禁 | 后续每个生产代码实现步骤必须同步新增或更新测试代码；新增测试优先使用 JUnit，保持简单、直观、聚焦单一行为。 |

## 任务清单

### P0 文档与门禁

- [x] 确认 [h2db-plugin-architecture-rfc.md](h2db-plugin-architecture-rfc.md) 中“阶段一已确认决策”无需再调整。
- [x] 确认本计划只覆盖阶段一，不把外部插件加载、S1 迁移、S2 实现混入本轮。
- [x] 在测试登记中保留所有 `T-PLUGIN-*` 编号，新增风险必须先补测试编号。

### P1 SPI 与 Registry 骨架

- [x] 新增 `org.h2.api.H2Plugin`。
- [x] 新增 `org.h2.api.PluginProvider`。
- [x] 新增 `org.h2.api.TableEngineProvider`。
- [x] 新增 `org.h2.api.PluginCapability` 字符串常量类。
- [x] 新增 storage 相关实验接口：`StorageEngineProvider`、`StorageEngine`、`StorageMaintenance`。
- [x] 新增内部 `org.h2.engine.PluginRegistry`，支持按 provider type/id 注册和查找。
- [x] 新增内部 `org.h2.engine.BuiltinPlugins`，集中注册内置 MVStore provider。
- [x] 新增 provider id 冲突校验：内置 provider 不允许被覆盖。
- [x] 同步新增 JUnit 测试覆盖 registry 注册、查找、冲突和 capability 查询。

验收：

- [x] `T-PLUGIN-BUILTIN-REGISTRY-01`
- [x] `T-PLUGIN-PROVIDER-ID-CONFLICT-01`

### P2 MVStore Storage 适配

- [x] 新增 `org.h2.mvstore.db.MVStoreStorageEngineProvider`。
- [x] 新增 `org.h2.mvstore.db.MVStoreStorageEngine`，内部持有现有 `Store`。
- [x] `MVStoreStorageEngine` 委托现有 `Store.flush()`、`Store.closeImmediately()` 等生命周期方法。
- [x] `Database` 初始化时创建 `PluginRegistry` 和 `StorageEngine`。
- [x] `Database` 过渡期保留 `private Store store` 和 `getStore()`，由 `MVStoreStorageEngine` 暴露现有 `Store`。
- [x] 保证只读、加密、内存库、持久库打开路径与当前行为一致。
- [x] 同步新增 JUnit 测试覆盖 MVStore storage provider 打开路径和基础 capability。

验收：

- [x] `T-PLUGIN-OLD-DATABASE-MVSTORE-01`
- [x] `T-PLUGIN-READONLY-OPEN-01`
- [x] `T-PLUGIN-ENCRYPTED-OPEN-01`
- [x] `T-PLUGIN-PERSISTENT-FLAG-01`

### P3 MVStore Table Provider

- [x] 新增 `org.h2.mvstore.db.MVStoreTableEngineProvider`。
- [x] provider 校验当前 storage engine 为内置 MVStore 兼容实现。
- [x] provider 调用现有 `Store.createTable(CreateTableData)` 创建 `MVTable`。
- [x] 保持 `MVTable` 不直接感知插件 registry。
- [x] 增加测试验证 provider 创建表与旧 `Store.createTable()` 行为一致。
- [x] 同步新增 JUnit 测试覆盖 provider 建表和默认 MVStore 行为。

验收：

- [x] `T-PLUGIN-BUILTIN-MVSTORE-PROVIDER-01`
- [x] `T-PLUGIN-DEFAULT-TABLE-ENGINE-01`

### P4 默认建表路径切换

- [x] 修改 `Schema.createTable()`：无显式 `ENGINE` 且无 `DEFAULT_TABLE_ENGINE` 时解析到内置 `mvstore` provider。
- [x] 不把系统默认 `mvstore` 写回 `CreateTableData.tableEngine`。
- [x] registry 命中 provider id 时走 `TableEngineProvider.createTable()`。
- [x] registry 未命中时保留旧 `Database.getTableEngine(tableEngine).createTable(data)` 行为。
- [x] 确认 `CreateTable.update()` 权限规则保持当前 custom engine admin 约束。
- [x] 确认 `TableBase.getCreateSQL()` 不因系统默认 provider 多输出 `ENGINE mvstore`。
- [x] 同步新增 JUnit 测试覆盖默认建表、legacy class name、`DEFAULT_TABLE_ENGINE` 和 `SCRIPT` 输出兼容。

验收：

- [x] `T-PLUGIN-CREATE-SQL-COMPAT-01`
- [x] `T-PLUGIN-LEGACY-TABLE-ENGINE-01`
- [x] `T-PLUGIN-DEFAULT-TABLE-ENGINE-CLASSNAME-01`

### P5 Capability 与 Maintenance 边界

- [ ] `StorageEngine.supports(String capability)` 支持阶段一 capability 查询。
- [ ] `StorageMaintenance` 第一轮提供边界，`vacuumOnline()` 默认返回 `UNSUPPORTED` 或等价结果。
- [ ] 内置 MVStore storage provider 声明基础能力：persistent、transactional、mvcc、backup 等。
- [ ] S2 相关能力只作为 gate 保留，不实现 S2：`storage.vacuum.online`、`storage.publish.crashSafe`、`storage.truncate.safe`。
- [ ] 确保后续 S2 入口必须先检查 storage capability。
- [ ] 同步新增 JUnit 测试覆盖 unsupported maintenance capability 和 S2 capability gate。

验收：

- [ ] `T-PLUGIN-CAPABILITY-UNSUPPORTED-01`
- [ ] `T-PLUGIN-S2-CAPABILITY-GATE-01`

### P6 回归与文档收口

- [ ] 补充或更新插件化设计文档中的实际类名、边界和测试结果。
- [ ] 确认后续阶段能力清单仍保留外部插件加载、第三方稳定 Storage SPI、storage engine id 持久化、S1 迁移、S2 实现等延期项。
- [ ] 在 `h2/` 目录运行编译验证。
- [ ] 运行插件化相关最小测试入口；若新增 Gradle 专项入口，记录命令。
- [ ] 记录剩余风险和后续阶段建议。

验收：

```powershell
.\gradlew.bat compileJava
```

计划新增或确认专项测试入口：

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

如果专项入口尚未实现，必须说明实际替代命令和覆盖范围。

## 测试编号

新增测试代码原则：

- 优先使用 JUnit，测试类和断言保持简单直观。
- 每个测试聚焦一个行为，不把多个阶段的大量行为塞进一个长测试。
- 生产代码每个阶段性改动必须有对应测试编号和测试方法。
- 只有 H2 现有测试体系无法覆盖的场景，才补充非 JUnit 的专项入口；专项入口也要说明覆盖范围。

| 编号 | 内容 | 阶段 |
| --- | --- | --- |
| `T-PLUGIN-BUILTIN-REGISTRY-01` | 启动时内置 `mvstore` table/storage provider 注册成功。 | P1 |
| `T-PLUGIN-PROVIDER-ID-CONFLICT-01` | provider id 冲突给出明确错误，不静默覆盖。 | P1 |
| `T-PLUGIN-OLD-DATABASE-MVSTORE-01` | 旧库未记录 storage engine id 时仍按 MVStore 打开。 | P2 |
| `T-PLUGIN-READONLY-OPEN-01` | 只读打开时 storage provider 正确传递 readOnly 设置。 | P2 |
| `T-PLUGIN-ENCRYPTED-OPEN-01` | 加密库打开仍通过 MVStore provider 传递 key。 | P2 |
| `T-PLUGIN-PERSISTENT-FLAG-01` | 内存库、持久库对 storage capability 的声明正确。 | P2 |
| `T-PLUGIN-BUILTIN-MVSTORE-PROVIDER-01` | `mvstore` provider 创建的表与旧 `Store.createTable()` 行为一致。 | P3 |
| `T-PLUGIN-DEFAULT-TABLE-ENGINE-01` | 未指定 `ENGINE` 时行为与当前 MVStore 建表一致。 | P3/P4 |
| `T-PLUGIN-CREATE-SQL-COMPAT-01` | 系统默认 `mvstore` 不导致 `SCRIPT` 输出额外 `ENGINE mvstore`。 | P4 |
| `T-PLUGIN-LEGACY-TABLE-ENGINE-01` | 旧 `CREATE TABLE ... ENGINE className` 仍可创建表。 | P4 |
| `T-PLUGIN-DEFAULT-TABLE-ENGINE-CLASSNAME-01` | `DEFAULT_TABLE_ENGINE` 使用旧类名时仍按旧逻辑创建表。 | P4 |
| `T-PLUGIN-CAPABILITY-UNSUPPORTED-01` | 不支持的 capability 返回 `UNSUPPORTED`，不进入 MVStore 私有逻辑。 | P5 |
| `T-PLUGIN-S2-CAPABILITY-GATE-01` | S2 入口必须先检查 `storage.vacuum.online`。 | P5 |

## 风险登记

| 风险 | 影响 | 缓解 | 状态 |
| --- | --- | --- | --- |
| 默认建表 SQL 输出变化 | `SCRIPT` / dump 兼容性破坏 | 不写回系统默认 `mvstore` 到 `CreateTableData.tableEngine`，补 `T-PLUGIN-CREATE-SQL-COMPAT-01` | [ ] |
| legacy `TableEngine` 行为变化 | 破坏已有用户扩展 | registry 未命中时 fallback 到旧类名加载，补 legacy 测试 | [ ] |
| `Database.getStore()` 一次性移除风险过高 | 大范围编译和行为回归 | 阶段一保留过渡字段和方法 | [ ] |
| storage SPI 过早公开 | 后续兼容成本高 | 阶段一标 experimental/internal，仅服务 S2 边界 | [ ] |
| capability 粒度不足 | S2 无法安全 gate | 使用明确字符串常量，单独列出 S2 相关能力 | [ ] |
| 外部插件加载被误纳入阶段一 | 范围失控 | 本计划明确 Out，后续阶段单独 RFC | [ ] |

## 后续阶段待办

以下不属于阶段一，后续单独设计和计划：

- [ ] 外部插件加载：URL 参数、系统属性、database setting、`ServiceLoader` 取舍。
- [ ] 第三方稳定 Storage SPI：公开接口、版本承诺、兼容策略。
- [ ] storage engine id 持久化：URL、setting、store header 或独立元数据。
- [ ] 缺失 storage 插件处理：失败、只读降级或可配置策略。
- [ ] 外部插件安全边界：classloader、权限、敏感配置脱敏。
- [ ] S1 迁入 `StorageMaintenance`。
- [ ] S2 在线空间回收实现。
- [ ] 插件诊断与可观测性：`INFORMATION_SCHEMA`、trace、capability 列表。
