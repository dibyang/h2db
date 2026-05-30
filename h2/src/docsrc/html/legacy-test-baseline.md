# Legacy 测试基线治理计划

本文档记录原有非 JUnit 测试纳入 Gradle 管理后的推进方式。目标不是一次性改写 `TestAll`，而是建立可运行、可分组、可迁移、可验收的入口，让后续插件化改造每个阶段都有明确测试底座。

## 当前入口

在 `h2/` 目录执行：

| 任务 | 用途 | 是否阻断构建 |
| --- | --- | --- |
| `.\gradlew.bat runH2LegacySmoke` | 运行当前已纳入 must-pass 的 legacy smoke 分组 | 是 |
| `.\gradlew.bat runH2LegacyBaselineReport` | 运行当前仍需观察的 legacy 基线分组；当前清单为空，用于后续回归治理 | 否 |
| `.\gradlew.bat runH2LegacyBaselineReport "-Ph2TestScript=other/conditions.sql"` | 只运行指定 `TestScript` 脚本，便于定位单个 SQL 脚本输出差异 | 否 |
| `.\gradlew.bat runH2TestAllCiPhaseReport "-Ph2CiPhase=memory"` | 只运行原始 `TestAll ci` 的一个阶段；支持 `memory`、`additional`、`utils`、`lazy-memory`、`disk`、`disk-additional`、`network-memory`、`network-lazy`、`encrypted-disk` | 否 |
| `.\gradlew.bat runH2TestAllCi` | 运行原始 `org.h2.test.TestAll ci` 完整入口 | 是，作为完整验收 |
| `.\gradlew.bat listH2LegacySlowTests` | 列出已纳入管理、但不进入日常门禁的慢测/性能测 | 否 |
| `.\gradlew.bat clean test check build` | 运行当前 Gradle 主构建和插件 JUnit 检查 | 是 |

`runH2LegacySmoke`、`runH2LegacyBaselineReport` 和 Gradle `TestAll ci` 入口都会把本仓库产品默认 MySQL mode 与上游 H2 legacy 测试预期隔离开。它们会给未显式指定 `MODE=` 的测试 URL 补 `MODE=REGULAR`；测试本身显式指定 MySQL、Oracle、DB2 等 mode 时不覆盖。

## 当前基线状态

所有命名阶段已通过：`memory`、`additional`、`utils`、`lazy-memory`、`disk`、`disk-additional`、`network-memory`、`network-lazy`、`encrypted-disk`。完整 `runH2TestAllCi` 曾通过本地验收，但 2026-05-30 15:17 复跑时在 `net memory` 的 `TestMultiThreadedKernel` 出现一次 `localhost:9092` 连接超时；随后单独复跑 `network-memory` 阶段通过。该问题先作为完整套件下的网络偶发项纳入测试底座治理。

已治理完成或纳入管理的历史问题：

| 类别 | 代表问题 | 当前状态 |
| --- | --- | --- |
| SQL 兼容语法 | `Unknown data type: "IDENTITY"` | 已通过 REGULAR legacy mode 策略隔离仓库默认 MySQL mode 影响 |
| 脚本输出预期 | `TestScript` 中列名、分隔线、表达式展示不匹配 | 已按当前行为更新相关脚本基线，`TestScript` 已迁入 smoke |
| JDBC 可更新结果集 | `The result set is not updatable`、结果集类型/并发断言不匹配 | 已恢复 `BASE TABLE` 过滤语义，相关类已迁入 smoke |
| 兼容模式断言 | Oracle/MySQL/metadata/keywords 等期望与当前实现不一致 | 已对齐 metadata 表类型顺序，`TestMetaData` 已迁入 smoke |
| 环境敏感断言 | 时间戳毫秒、Locale 中文月份、Web Console 输出、network phase 端口连接超时 | 已通过阶段化入口隔离定位；`network-memory` 单阶段复跑通过，完整套件下的偶发网络超时继续记录 |
| 完整 `TestAll ci` 运行时间 | 本地完整运行约 16 分钟 | 保留为完整验收，不作为每次小改动的默认快速反馈 |

`TestUpgrade` 在没有系统 `mvn` 命令时可能打印类似 `"mvn is not recognized"` 的输出。该输出来自工具先尝试 Maven、本地缺失后回退到直接下载 Maven Central artifact 的路径；只要 Gradle 任务最终通过，此输出属于非阻断环境噪声。

## 测试分层

日常开发门禁：

```powershell
.\gradlew.bat clean test check build runH2LegacySmoke
```

适用范围：插件化改造每个小阶段、生产代码变更、测试底座变更。该命令覆盖 Gradle 主构建、插件 JUnit 检查、以及已纳入 must-pass 的 legacy smoke。

完整验收：

```powershell
.\gradlew.bat runH2TestAllCi
```

适用范围：阶段完成、本地提交前需要确认没有破坏原有 H2 legacy 套件时使用。该任务耗时较长，应作为阶段验收，而不是每次编辑后的快速反馈。若失败集中在 network phase，先用对应 `runH2TestAllCiPhaseReport` 单阶段复核并记录是否为偶发环境问题。

分阶段排查：

```powershell
.\gradlew.bat runH2TestAllCiPhaseReport "-Ph2CiPhase=memory"
```

适用范围：完整验收失败、超时或需要缩小问题范围时使用。该任务是观察型报告入口，失败不会阻断 Gradle 构建。

慢测/性能测管理：

```powershell
.\gradlew.bat listH2LegacySlowTests
```

当前纳入管理但不进入日常门禁的测试包括：

| 测试类 | 原因 | 后续处理 |
| --- | --- | --- |
| `org.h2.test.db.TestLargeBlob` | 大对象边界测试，资源消耗高 | 后续放入专门大资源验收任务 |
| `org.h2.test.store.TestDefrag` | 依赖 `big` 且非 `ci` 配置 | 后续放入专门存储慢测任务 |
| `org.h2.test.store.TestMVStoreBenchmark` | 基准测试，不适合日常构建稳定性判断 | 后续放入性能基线任务 |
| `org.h2.test.db.TestSubqueryPerformanceOnLazyExecutionMode` | 性能/懒执行路径验证 | 后续放入性能回归任务 |

## 推进规则

1. 新增或修改生产代码时，至少运行相关专项测试和 `runH2LegacySmoke`。
2. 插件化改造每个阶段提交前，运行日常开发门禁。
3. 阶段完成或风险较高的存储、表引擎、SQL 行为改动提交前，运行完整 `runH2TestAllCi`。
4. 修复一个 legacy 失败类后，把该类从 `legacyBaselineIssueTests` 移到 `legacySmokeTests`。
5. 如果某个测试类仍不稳定，先记录失败原因，不放入 must-pass。
6. `runH2LegacyBaselineReport` 和 `runH2TestAllCiPhaseReport` 只用于观察和排查，不作为构建通过依据。
7. 慢测/性能测先通过 `listH2LegacySlowTests` 统一纳入管理，后续再拆成可配置、可限资源的专门任务。

## 分阶段任务

当前已通过的 `TestAll ci` 命名阶段：`memory`、`additional`、`utils`、`lazy-memory`、`disk`、`disk-additional`、`network-memory`、`network-lazy`、`encrypted-disk`。完整 `runH2TestAllCi` 作为验收入口保留；当前已记录一次完整套件下 `network-memory` 偶发连接超时，单阶段复跑通过。

| 阶段 | 目标 | 完成标准 |
| --- | --- | --- |
| L1 | 固化 Gradle legacy smoke 和 baseline report 入口 | `runH2LegacySmoke` 通过，文档记录使用方式 |
| L2 | 治理 `IDENTITY` 兼容失败 | 代表性 db/jdbc 测试不再因 `IDENTITY` 失败 |
| L3 | 治理 JDBC updatable result set 失败 | `TestCompatibilityOracle`、`TestResultSet`、`TestUpdatableResultSet` 迁入 smoke |
| L4 | 治理 `TestScript` 输出基线 | `TestScript` 迁入 smoke，支持 `-Ph2TestScript=...` 单脚本定位 |
| L5 | 治理兼容模式和 metadata/keywords 失败 | `TestMetaData` 迁入 smoke，baseline report 当前无剩余类 |
| L6 | 治理环境敏感失败 | `TestAll ci` 支持按阶段运行，便于定位 locale、timezone、时间精度类失败 |
| L7 | 扩大 must-pass 分组 | 修复后的类迁入 smoke，`TestAll ci` `memory` 阶段通过 |
| L8 | 恢复完整 `runH2TestAllCi` 作为验收 | 所有命名 `TestAll ci` 阶段通过；完整入口作为阶段验收，若出现 network 偶发超时，需单阶段复跑并记录 |
| L9 | 将 legacy 分组纳入日常开发规范 | 日常门禁、完整验收、慢测/性能测管理、环境噪声说明已写入文档，并有 Gradle 可发现入口 |
