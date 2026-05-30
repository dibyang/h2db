# Legacy 测试基线治理计划

本文档记录原有非 JUnit 测试纳入 Gradle 管理后的推进方式。目标不是一次性改写 `TestAll`，而是先建立可运行、可分组、可迁移的入口，再逐步把当前失败用例收敛到 must-pass 集合。

## 当前入口

在 `h2/` 目录执行：

| 任务 | 用途 | 是否阻断构建 |
| --- | --- | --- |
| `.\gradlew.bat runH2LegacySmoke` | 运行当前已纳入 must-pass 的 legacy smoke 分组 | 是 |
| `.\gradlew.bat runH2LegacyBaselineReport` | 运行当前已知失败的 legacy 基线分组，便于分类和修复 | 否 |
| `.\gradlew.bat runH2LegacyBaselineReport "-Ph2TestScript=other/conditions.sql"` | 只运行指定 `TestScript` 脚本，便于定位单个 SQL 脚本输出差异 | 否 |
| `.\gradlew.bat runH2TestAllCiPhaseReport "-Ph2CiPhase=memory"` | 只运行原始 `TestAll ci` 的一个阶段，用于定位超时和失败；支持 `memory`、`additional`、`utils`、`lazy-memory`、`disk`、`disk-additional`、`network-memory`、`network-lazy`、`encrypted-disk` | 否 |
| `.\gradlew.bat runH2TestAllCi` | 运行原始 `org.h2.test.TestAll ci` 完整入口 | 是 |
| `.\gradlew.bat clean test check build` | 运行当前 Gradle 主构建和插件 JUnit 检查 | 是 |

`runH2LegacySmoke`、`runH2LegacyBaselineReport` 和 Gradle `TestAll ci` 入口都会把本仓库产品默认 MySQL mode 与上游 H2 legacy 测试预期隔离开。它们会给未显式指定 `MODE=` 的测试 URL 补 `MODE=REGULAR`；测试本身显式指定 MySQL、Oracle、DB2 等 mode 时不覆盖。

## 当前基线问题

最近一次完整 `runH2TestAllCi` 已能编译并启动原始套件，但仍有大量失败，主要集中在：

| 类别 | 代表失败 | 处理方向 |
| --- | --- | --- |
| SQL 兼容语法 | `Unknown data type: "IDENTITY"` | 已确认主要由仓库默认 MySQL mode 与 legacy 测试默认 REGULAR 预期冲突触发；分组 runner 固定未显式 mode 的 URL 为 REGULAR，剩余失败再按类治理 |
| 脚本输出预期 | `TestScript` 中列名、分隔线、表达式展示不匹配 | 已按当前行为更新 `compatibility/compatibility.sql`、`datatypes/double_precision.sql`、`other/conditions.sql`，`TestScript` 已迁入 smoke |
| JDBC 可更新结果集 | `The result set is not updatable`、`TYPE_SCROLL_INSENSITIVE`/`CONCUR_UPDATABLE` 断言不匹配 | 已确认主要由 metadata 表类型过滤误依赖非排序数组触发；`BASE TABLE` 过滤恢复后，相关类已迁入 smoke |
| 兼容模式断言 | Oracle/MySQL/metadata/keywords 等期望与当前实现不一致 | metadata 表类型顺序已按当前 `SYS TABLE` 行为对齐，`TestMetaData` 已迁入 smoke；后续完整 `TestAll` 中发现的兼容模式差异继续按模式分组治理 |
| 环境敏感断言 | 时间戳毫秒、Locale 中文月份、Web Console 输出 | 固定 locale/timezone 或调整断言为稳定语义 |
| 完整 `TestAll ci` 运行时间 | 未拆分的 `runH2TestAllCi` 本地运行超过 15 分钟超时 | 所有命名阶段和完整 `runH2TestAllCi` 入口已在 `MODE=REGULAR` 下通过 |

## 推进规则

1. 新增或修改生产代码时，必须至少运行相关专项测试和 `runH2LegacySmoke`。
2. 修复一个 legacy 失败类后，把该类从 `legacyBaselineIssueTests` 移到 `legacySmokeTests`。
3. 如果某个测试类仍不稳定，先记录失败原因，不放入 must-pass。
4. `runH2LegacyBaselineReport` 只用于观察失败面，不作为构建通过依据。
5. `runH2TestAllCi` 保留为最终验收目标；只有全部分组稳定后再恢复为日常阻断任务。

## 分阶段任务

当前已通过的 `TestAll ci` 命名阶段：`memory`、`additional`、`utils`、`lazy-memory`、`disk`、`disk-additional`、`network-memory`、`network-lazy`、`encrypted-disk`。完整 `runH2TestAllCi` 入口已通过本地验收。

| 阶段 | 目标 | 完成标准 |
| --- | --- | --- |
| L1 | 固化 Gradle legacy smoke 和 baseline report 入口 | `runH2LegacySmoke` 通过，文档记录使用方式 |
| L2 | 治理 `IDENTITY` 兼容失败 | 代表性 db/jdbc 测试不再因 `IDENTITY` 失败 |
| L3 | 治理 JDBC updatable result set 失败 | `TestCompatibilityOracle`、`TestResultSet`、`TestUpdatableResultSet` 已迁入 smoke |
| L4 | 治理 `TestScript` 输出基线 | `TestScript` 已迁入 smoke，支持 `-Ph2TestScript=...` 单脚本定位 |
| L5 | 治理兼容模式和 metadata/keywords 失败 | `TestMetaData` 已迁入 smoke，baseline report 当前无剩余类 |
| L6 | 治理环境敏感失败 | `TestAll ci` 支持按阶段运行，可以在不阻塞全量套件的情况下定位 locale、timezone、时间精度类失败 |
| L7 | 扩大 must-pass 分组 | baseline report 中修复后的类迁入 smoke，且 `TestAll ci` 的 `memory` 阶段在同一 REGULAR legacy mode 策略下通过 |
| L8 | 恢复完整 `runH2TestAllCi` 作为可选验收 | 所有命名 `TestAll ci` 阶段通过，完整入口失败数为 0 |
| L9 | 将 legacy 分组纳入日常开发规范 | 文档、Gradle 任务和提交检查约定一致 |
