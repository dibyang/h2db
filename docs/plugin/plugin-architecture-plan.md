# 插件化改造推进计划

本文档记录 H2 插件化改造的后续实施任务。当前目标是先把已有插件骨架稳定成可持续演进的基础设施，再逐步让存储引擎和表引擎都通过插件机制扩展；内部内置能力也继续走同一套 provider/registry 路径。

## 范围

本轮纳入范围：

| 领域 | 内容 |
| --- | --- |
| 插件入口 | `H2Plugin`、`PluginProvider`、默认 `ServiceLoader` 自动发现 |
| 注册中心 | provider type/id 唯一性、来源、能力、诊断快照 |
| 存储引擎 | `StorageEngineProvider`、`StorageEngine`、持久化 storage engine id、只读降级策略 |
| 表引擎 | `TableEngineProvider`、`TableEngineContext`、内置 MVStore table engine provider 路径 |
| 事务事件 | `TransactionEventProvider`、`TransactionContext`、commit / rollback 事件诊断 |
| 数据库生命周期 | `DatabaseLifecycleProvider`、`DatabaseLifecycleContext`、close 事件诊断 |
| 测试底座 | JUnit `pluginTest`、legacy smoke、完整 `TestAll ci` 阶段复核规则 |
| 对外文档 | 中文文档和英文副本同步更新 |

本轮暂不纳入范围：

| 能力 | 后续阶段 |
| --- | --- |
| 热加载、卸载、在线替换插件 | 插件生命周期稳定后单独设计 |
| 插件包 manifest、签名、权限沙箱 | 外部插件分发阶段设计 |
| 非 storage/table/system catalog/JDBC URL prefix/transaction event 的新扩展点 | parser、function、auth、optimizer、wire protocol 等当前规划不纳入 |
| 自动性能基线和大资源慢测 | 测试底座慢测阶段补齐 |

## 测试门禁

每个插件化阶段至少运行：

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

生产代码变更、存储/表引擎行为变更、或者提交前需要更高信心时运行：

```powershell
.\gradlew.bat clean test check build runH2LegacySmoke
```

阶段完成或风险较高时运行完整验收：

```powershell
.\gradlew.bat runH2TestAllCi
```

如果完整验收在 network phase 出现连接超时，先用 `runH2TestAllCiPhaseReport` 单阶段复跑并记录结果。

## 阶段任务

| 阶段 | 状态 | 目标 | 主要任务 | 验证 |
| --- | --- | --- | --- | --- |
| P1 | 已完成 | 固化后续推进计划 | 建立中英文阶段计划、绑定测试门禁、列出暂不纳入能力 | `runPluginArchitectureCheck` |
| P2 | 已完成 | 补齐 SPI 契约边界 | 检查 `H2Plugin`、provider 的 null、空 id/version、空 provider 集合和 source 契约；补 JUnit | `runPluginArchitectureCheck`、日常门禁 |
| P3 | 已完成 | 加固插件加载诊断 | 覆盖显式 class、ServiceLoader 开关、版本范围、无效描述符、依赖缺失、循环依赖、非法 provider type | `runPluginArchitectureCheck`、日常门禁 |
| P4 | 已完成 | 收敛内置插件路径 | 确认 MVStore storage/table engine 通过 builtin provider 注册和解析；默认建表不走 legacy `TableEngine` 缓存 | `runPluginArchitectureCheck`、`runH2LegacySmoke` |
| P5 | 已完成 | 完善表引擎插件化 | 覆盖外部 `TableEngineProvider` SQL 建表、schema default params、内置与 legacy 路径兼容 | `runPluginArchitectureCheck`、`runH2LegacySmoke` |
| P6 | 已完成 | 完善存储引擎插件化 | 覆盖 storage id 持久化、请求/持久化不匹配、secondary/default 防误开、缺失 provider、只读降级、维护能力 | `runPluginArchitectureCheck` |
| P7 | 已完成 | 完善可观测性 | 确认内置和外部配置插件在 `INFORMATION_SCHEMA.PLUGINS`、`PLUGIN_PROVIDERS`、`PLUGIN_CAPABILITIES` 中输出稳定 | `runPluginArchitectureCheck` |
| P8 | 已完成 | 阶段验收与收口 | 已跑日常门禁；本轮改动集中在插件 SPI、加载、表/存储 provider 测试与诊断覆盖，未额外触发完整 `TestAll ci` | `clean test check build runH2LegacySmoke` |
| P10 | 已完成 | Table / Index 迁移期适配层 | 新增 `TableProviderSupport`，统一只读 gate、storage 类型校验和 provider 失败诊断；补契约测试和文档 | `runPluginArchitectureCheck`、`runH2LegacySmoke` |
| P11 | 已完成 | 事务事件扩展能力 | 新增 `TransactionEventProvider` / `TransactionContext`，接入 commit / rollback 前后事件，补诊断和失败测试 | `runPluginArchitectureCheck`、`runH2LegacySmoke` |
| P13 | 已完成 | 数据库生命周期扩展能力 | 新增 `DatabaseLifecycleProvider` / `DatabaseLifecycleContext`，接入数据库关闭回调，补诊断和失败测试 | `runPluginArchitectureCheck` |
| P14 | 已完成 | 插件版本并存与依赖解析 | 允许同 plugin id 的不同版本在 provider id 不冲突时并存；依赖版本支持精确版本、`*` 和区间匹配 | `runPluginArchitectureCheck` |
| P15 | 已完成 | 多版本诊断闭环 | `INFORMATION_SCHEMA.PLUGINS` 按 plugin id/version 输出，`PLUGIN_CAPABILITIES` 能力行包含 `PLUGIN_VERSION` | `runPluginArchitectureCheck` |
| P16 | 已完成 | 依赖诊断闭环 | 注册中心保存插件依赖快照并暴露 `INFORMATION_SCHEMA.PLUGIN_DEPENDENCIES`；加固非法依赖描述符 | `runPluginArchitectureCheck` |

## 当前收口状态

P1-P8、P10、P11、P13、P14、P15 与 P16 已完成。插件化基础设施当前具备：

| 能力 | 状态 |
| --- | --- |
| 插件注册中心 | 支持 provider 注册、唯一性校验、能力和依赖诊断、无效描述符拒绝 |
| 插件加载 | 支持默认 `ServiceLoader` 自动发现、版本范围、依赖排序、依赖缺失和依赖环诊断 |
| 内置插件 | MVStore storage/table provider 通过 builtin registry 路径注册和解析 |
| 表引擎扩展 | 支持外部 `TableEngineProvider` 通过 SQL engine id 建表，并传递 table/schema params |
| Table provider 支撑层 | `TableProviderSupport` 覆盖只读 gate、storage 类型校验和 provider 失败诊断包装 |
| 存储引擎扩展 | 支持 storage provider 解析、storage id 持久化、不匹配拒绝、只读降级和维护能力边界 |
| 事务事件扩展 | 支持 transaction provider 监听 commit / rollback 前后事件，并在失败时输出 provider/event/session 诊断 |
| 数据库生命周期扩展 | 支持 lifecycle provider 监听数据库关闭事件，不再需要通过 URL 注入 `DATABASE_EVENT_LISTENER` 做插件关闭适配 |
| 版本解析 | 插件依赖支持精确版本、`*` 和区间匹配；同 plugin id 的多个版本可在 provider id 不冲突时并存 |
| 可观测性 | `INFORMATION_SCHEMA.PLUGINS`、`PLUGIN_PROVIDERS`、`PLUGIN_CAPABILITIES`、`PLUGIN_DEPENDENCIES` 覆盖内置、外部配置和多版本插件 |
| 测试门禁 | `runPluginArchitectureCheck` 与日常门禁已通过 |

## 推进规则

1. 每个阶段完成后本地提交一次，提交信息使用阶段目标描述。
2. 新增生产代码必须同步新增或更新 JUnit 测试；legacy 测试只用于补充兼容面。
3. 对外文档变更必须同步英文副本。
4. 不在本阶段实现的插件能力必须写入“暂不纳入范围”或后续阶段。
5. 不改变磁盘格式、SQL 兼容行为或默认配置，除非阶段文档明确说明迁移和回滚路径。
