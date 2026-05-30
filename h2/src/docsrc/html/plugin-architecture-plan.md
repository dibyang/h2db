# 插件化改造推进计划

本文档记录 H2 插件化改造的后续实施任务。当前目标是先把已有插件骨架稳定成可持续演进的基础设施，再逐步让存储引擎和表引擎都通过插件机制扩展；内部内置能力也继续走同一套 provider/registry 路径。

## 范围

本轮纳入范围：

| 领域 | 内容 |
| --- | --- |
| 插件入口 | `H2Plugin`、`PluginProvider`、显式 class 加载、`PLUGIN_PATHS`、可选 `ServiceLoader` |
| 注册中心 | provider type/id 唯一性、来源、能力、诊断快照 |
| 存储引擎 | `StorageEngineProvider`、`StorageEngine`、持久化 storage engine id、只读降级策略 |
| 表引擎 | `TableEngineProvider`、`TableEngineContext`、内置 MVStore table engine provider 路径 |
| 测试底座 | JUnit `pluginTest`、legacy smoke、完整 `TestAll ci` 阶段复核规则 |
| 对外文档 | 中文文档和英文副本同步更新 |

本轮暂不纳入范围：

| 能力 | 后续阶段 |
| --- | --- |
| 热加载、卸载、在线替换插件 | 插件生命周期稳定后单独设计 |
| 插件包 manifest、签名、权限沙箱 | 外部插件分发阶段设计 |
| 多版本插件并存与版本解算器 | dependency/version 语义扩展阶段设计 |
| 非 storage/table 的新扩展点 | parser、function、auth 等扩展点另行评审 |
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
| P8 | 未开始 | 阶段验收与收口 | 跑日常门禁，按风险决定是否跑完整 `TestAll ci`，更新状态并提交 | 日常门禁，必要时完整验收 |

## 推进规则

1. 每个阶段完成后本地提交一次，提交信息使用阶段目标描述。
2. 新增生产代码必须同步新增或更新 JUnit 测试；legacy 测试只用于补充兼容面。
3. 对外文档变更必须同步英文副本。
4. 不在本阶段实现的插件能力必须写入“暂不纳入范围”或后续阶段。
5. 不改变磁盘格式、SQL 兼容行为或默认配置，除非阶段文档明确说明迁移和回滚路径。
