# Legacy 测试基线治理计划

本文档记录原有非 JUnit 测试纳入 Gradle 管理后的推进方式。目标不是一次性改写 `TestAll`，而是先建立可运行、可分组、可迁移的入口，再逐步把当前失败用例收敛到 must-pass 集合。

## 当前入口

在 `h2/` 目录执行：

| 任务 | 用途 | 是否阻断构建 |
| --- | --- | --- |
| `.\gradlew.bat runH2LegacySmoke` | 运行当前已纳入 must-pass 的 legacy smoke 分组 | 是 |
| `.\gradlew.bat runH2LegacyBaselineReport` | 运行当前已知失败的 legacy 基线分组，便于分类和修复 | 否 |
| `.\gradlew.bat runH2TestAllCi` | 运行原始 `org.h2.test.TestAll ci` 完整入口 | 是 |
| `.\gradlew.bat clean test check build` | 运行当前 Gradle 主构建和插件 JUnit 检查 | 是 |

`runH2LegacySmoke` 和 `runH2LegacyBaselineReport` 通过 `org.h2.test.LegacyTestGroupRunner` 运行。该 runner 复用 `TestBase.runTest(TestAll)` 生命周期，只把测试类清单交给 Gradle 分组管理。

## 当前基线问题

最近一次完整 `runH2TestAllCi` 已能编译并启动原始套件，但仍有大量失败，主要集中在：

| 类别 | 代表失败 | 处理方向 |
| --- | --- | --- |
| SQL 兼容语法 | `Unknown data type: "IDENTITY"` | 明确当前 H2 版本对旧 `IDENTITY` 写法的兼容策略；修复 parser/模式，或统一更新测试脚本预期 |
| 脚本输出预期 | `TestScript` 中列名、分隔线、表达式展示不匹配 | 判断是输出行为已变更还是脚本预期陈旧，按目录分批更新 |
| JDBC 可更新结果集 | `The result set is not updatable`、`TYPE_SCROLL_INSENSITIVE`/`CONCUR_UPDATABLE` 断言不匹配 | 对齐 JDBC 行为设计，避免误把行为变化当成测试基线修复 |
| 兼容模式断言 | Oracle/MySQL/metadata/keywords 等期望与当前实现不一致 | 按兼容模式分组治理，保留每个模式的最小回归集 |
| 环境敏感断言 | 时间戳毫秒、Locale 中文月份、Web Console 输出 | 固定 locale/timezone 或调整断言为稳定语义 |

## 推进规则

1. 新增或修改生产代码时，必须至少运行相关专项测试和 `runH2LegacySmoke`。
2. 修复一个 legacy 失败类后，把该类从 `legacyBaselineIssueTests` 移到 `legacySmokeTests`。
3. 如果某个测试类仍不稳定，先记录失败原因，不放入 must-pass。
4. `runH2LegacyBaselineReport` 只用于观察失败面，不作为构建通过依据。
5. `runH2TestAllCi` 保留为最终验收目标；只有全部分组稳定后再恢复为日常阻断任务。

## 分阶段任务

| 阶段 | 目标 | 完成标准 |
| --- | --- | --- |
| L1 | 固化 Gradle legacy smoke 和 baseline report 入口 | `runH2LegacySmoke` 通过，文档记录使用方式 |
| L2 | 治理 `IDENTITY` 兼容失败 | 代表性 db/jdbc 测试不再因 `IDENTITY` 失败 |
| L3 | 治理 JDBC updatable result set 失败 | `TestResultSet`、`TestUpdatableResultSet`、Web 相关失败有明确结论并收敛 |
| L4 | 治理 `TestScript` 输出基线 | 脚本输出差异按目录归档并分批迁移 |
| L5 | 治理兼容模式和 metadata/keywords 失败 | Oracle/MySQL/metadata 类失败不再混在通用失败面 |
| L6 | 治理环境敏感失败 | locale、timezone、时间精度类失败稳定化 |
| L7 | 扩大 must-pass 分组 | baseline report 中修复后的类迁入 smoke |
| L8 | 恢复完整 `runH2TestAllCi` 作为可选验收 | 完整入口失败数为 0 或仅剩明确豁免 |
| L9 | 将 legacy 分组纳入日常开发规范 | 文档、Gradle 任务和提交检查约定一致 |

