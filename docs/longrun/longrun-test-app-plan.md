# H2 长稳压测独立应用推进计划

本文是 `longrun-test-app-design.md` 的可追踪实施计划。目标是在不污染 H2 主 jar、不进入默认 CI 的前提下，逐步交付独立的 `h2-longrun.jar`，用于 MVStore / H2 SQL 真实访问模拟、长时间运行、加压、故障注入和 S2 自动空间整理验收。

## 已拍板决策

| 决策项 | 结论 |
| --- | --- |
| 源码目录 | 使用 `h2/src/longrun`，与 `src/main`、`src/test` 分离。 |
| jar 名称 | 使用 `h2-longrun.jar`。 |
| 首版 smoke 时长 | 默认 5 分钟。 |
| 首版 workload | 先做 MVStore，再扩展 SQL。 |
| 配置格式 | 首版使用 `.properties`。 |
| 执行方式 | 所有 longrun 任务显式执行，不进入默认 `build` / `check`。 |
| 提交流程 | 每个 LR 阶段完成并通过对应验证后，本地提交一次。 |

## 范围

| 范围 | 说明 |
| --- | --- |
| In | 独立 source set、独立 jar、zip 和 tar.gz 发布包、CLI、配置、MVStore workload、metrics、状态文件、一致性校验、S2 reclamation 观察、crash harness、SQL workload、external mode。 |
| Out | 不打进 H2 主 jar；不替代 JUnit、legacy smoke、`TestAll ci`；不把 30 天长跑加入默认 CI；不把长测生成的数据库作为生产数据。 |

## 阶段总览

| 阶段 | 状态 | 目标 | 交付物 | 验证 | 提交要求 |
| --- | --- | --- | --- | --- | --- |
| LR1 | Done | 独立 jar 骨架 | `src/longrun`、`longRunTestJar`、CLI、样例 config | jar 可构建，`--help` 可运行 | 已提交 |
| LR2 | Done | MVStore 5 分钟 smoke workload | MVStore 读写删改、checksum、状态文件、metrics | `runLongRunSmoke` 默认 5 分钟通过 | 已提交 |
| LR3 | Done | S2 自动空间整理观察 | `ReclamationObserver`、文件大小、fill rate、diagnostic metrics | workload 期间记录 housekeeping 结果 | 已提交 |
| LR4 | Done | 一致性和 reopen 验证 | ledger、counters、full scan、reopen verify | 重启后数据模型一致 | 已提交 |
| LR5 | Done | crash harness | 父子进程 kill / restart / resume | 多轮 crash 后恢复校验通过 | 已提交 |
| LR6 | Done | SQL workload | JDBC 表模型、事务、索引、范围扫描、批量写 | SQL smoke 通过 | 已提交 |
| LR7 | Done | 夜间与 30 天 soak 配置 | 12 小时和 30 天样例配置、报告归档、metrics 滚动 | 本地缩短版通过 | 已提交 |
| LR8 | Done | external mode | `--h2-jar` 指定候选 H2 jar | 可对指定 jar 跑 smoke | 已提交 |
| LR9 | Done | 发布包 | 包含 jar、脚本、配置和 README 的 `h2-longrun.zip` / `h2-longrun.tar.gz` | 发布包可构建，解压后脚本可跑 smoke | 已提交 |
| LR10 | Done | 报告分析器 | 正常结束后自动生成 Markdown 和 properties 摘要，`report` 命令用于重跑旧数据分析 | 已完成的运行可分析为 PASS/WARN/FAIL | 已提交 |

## LR1 独立 jar 骨架

| 项目 | 内容 |
| --- | --- |
| 目标 | 建立完全独立的 longrun 源码、构建和运行入口。 |
| 主要文件 | `h2/build.gradle`、`h2/src/longrun/org/h2/test/longrun/LongRunTestApp.java`、`LongRunConfig.java`、样例配置文件。 |
| 必做任务 | 新增 `longrun` source set；新增 `longRunTestJar`；新增 `runLongRunSmoke` 占位任务；实现 `--help`、`--config` 基础解析；输出版本、工作目录和配置摘要。 |
| 验证 | `.\gradlew.bat longRunTestJar` 通过；`java -jar build/libs/h2-longrun.jar --help` 可运行；不影响 `.\gradlew.bat compileJava`。 |
| 风险控制 | 不修改 H2 主 jar task；不把 longrun source set 加入默认 test/check。 |

## LR2 MVStore 5 分钟 smoke workload

| 项目 | 内容 |
| --- | --- |
| 目标 | 形成可运行的 MVStore 基础长测闭环。 |
| 主要文件 | `WorkloadRunner`、`WorkloadProfile`、`mvstore/MVStoreWorkload`、`DataInvariantChecker`、`MetricsReporter`、`LongRunState`。 |
| 必做任务 | 支持固定 seed；实现 put/get/remove/update；value 带 checksum/version；周期写状态文件；输出 metrics；默认 smoke 时长 5 分钟。 |
| 验证 | `.\gradlew.bat runLongRunSmoke` 默认跑 5 分钟通过；失败时保留 work dir；相同 seed 的短运行可复现基础操作序列。 |
| 风险控制 | 设置默认小数据量和磁盘上限；状态文件先使用临时文件再替换。 |

## LR3 S2 自动空间整理观察

| 项目 | 内容 |
| --- | --- |
| 目标 | 在真实 workload 中观察 S2 自动空间整理效果和诊断。 |
| 主要文件 | `ReclamationObserver`、`MetricsReporter`、MVStore workload 配置。 |
| 必做任务 | 周期调用或观察 `runOnlineReclamationHousekeeping()`；记录 status、message、file size、fill rate、chunk fill rate、candidate chunks、shrink bytes。 |
| 验证 | smoke 期间 metrics 中出现 reclamation 记录；无 candidate、success、backoff 等结果可区分。 |
| 风险控制 | 观察与触发频率必须可配置；默认低频，避免压过前台 workload。 |

## LR4 一致性和 reopen 验证

| 项目 | 内容 |
| --- | --- |
| 目标 | 证明长测不是只看“不崩”，而是持续验证数据正确性。 |
| 主要文件 | `DataInvariantChecker`、`LongRunState`、`mvstore/MVStoreModel`。 |
| 必做任务 | 引入 `ledger`、`counters`；支持 fast check 和 full scan；周期 close/reopen；恢复后验证已提交数据和 counter 一致。 |
| 验证 | reopen 验证通过；人为破坏 checksum 时测试失败并保留现场。 |
| 风险控制 | full scan 间隔可配置；大数据量下不能每轮全扫。 |

## LR5 crash harness

| 项目 | 内容 |
| --- | --- |
| 目标 | 覆盖真实进程级异常退出和恢复。 |
| 主要文件 | `CrashHarness`、`LongRunTestApp` 子进程模式、pid/state 文件。 |
| 必做任务 | 父进程启动 worker；随机 kill worker；worker `--resume=true` 恢复；恢复后先校验再继续 workload。 |
| 验证 | 多轮 kill/restart 后最终校验通过；失败时保留数据库、状态、最近操作和 metrics。 |
| 风险控制 | 明确 parent/worker 角色，避免误杀父进程；Windows / Linux 命令分别处理。 |

## LR6 SQL workload

| 项目 | 内容 |
| --- | --- |
| 目标 | 把长测从 MVStore 扩展到 JDBC / SQL 层。 |
| 主要文件 | `sql/SqlWorkload`、`sql/SqlModel`、SQL schema 初始化。 |
| 必做任务 | 实现 `LONGRUN_DATA`、`LONGRUN_LEDGER`、`LONGRUN_COUNTERS`；覆盖事务提交/回滚、索引查询、范围扫描、批量写。 |
| 验证 | SQL smoke 通过；回滚数据不可见；ledger/counters 一致。 |
| 风险控制 | 首版 SQL workload 不覆盖全部 SQL 特性，避免范围失控。 |

## LR7 夜间与 30 天 soak 配置

| 项目 | 内容 |
| --- | --- |
| 目标 | 让应用可以真正用于夜间和 30 天长跑。 |
| 主要文件 | `src/longrun/resources` 样例配置、报告归档逻辑、metrics rollover。 |
| 必做任务 | 提供 5 分钟、12 小时、30 天三档配置；metrics 按天滚动；最终报告汇总吞吐、延迟、错误、空间趋势和 reclamation 结果。 |
| 验证 | 本地缩短版 soak 通过；配置能限制最大 DB 大小、最大错误数、输出目录。 |
| 风险控制 | 默认不启动 30 天任务；长跑必须显式指定配置。 |

## LR8 external mode

| 项目 | 内容 |
| --- | --- |
| 目标 | 支持对候选发布版 `h2.jar` 做长稳压测。 |
| 主要文件 | CLI、classpath 启动逻辑、父子进程模式。 |
| 必做任务 | 支持 `--h2-jar`；记录目标 jar 路径、版本、校验摘要；external mode 下可运行 smoke。 |
| 验证 | 对指定 `h2.jar` 运行 smoke 成功；错误 classpath 能给出清晰诊断。 |
| 风险控制 | 不在 LR1-LR7 期间引入复杂 classloader；external mode 单独阶段处理。 |

## LR9 发布包

| 项目 | 内容 |
| --- | --- |
| 目标 | 把 longrun 应用从开发者 jar 变成更适合运维和长时间运行的发布包。 |
| 主要文件 | `h2/build.gradle`、`h2/src/longrun/dist/bin`、`h2/src/longrun/dist/README*.md`、`src/longrun/resources`。 |
| 必做任务 | 新增 `longRunTestDistZip`、`longRunTestDistTar` 和 `longRunTestDist`；发布包内使用顶层 `h2-longrun/` 目录；打包 `lib/h2-longrun.jar`、`bin` 脚本、`config` 配置和中英文 README；Linux 脚本默认后台 `start`，支持 `run/status/logs/watch/stop/restart`，并支持启动日志 rotate/append/truncate 策略；脚本必须保持 `--h2-jar` classpath 行为。 |
| 验证 | 构建两个发布包，解压后使用包内脚本跑缩短版 smoke。 |
| 风险控制 | 不把 distribution 发布包挂入默认 `build` / `check`；长跑仍然显式执行；发布包配置写入 `work/`。 |

## LR10 报告分析器

| 项目 | 内容 |
| --- | --- |
| 目标 | 把长测原始产物转换成简明的 PASS/WARN/FAIL 报告。 |
| 主要文件 | `ReportAnalyzer`、`CommandLineOptions`、`LongRunTestApp`、`bin/h2-longrun`、README 文件。 |
| 必做任务 | 正常结束后自动生成报告；保留 `report --work-dir <dir> [--log-file <file>]` 用于重跑旧数据分析；读取 `final-report.properties`、`metrics/*.csv` 和日志；把 Markdown summary 打印到 stdout；写出 `report/summary.md` 与 `report/summary.properties`；用适中的默认阈值识别 final report 缺失、可疑日志行、metrics 缺失、吞吐过低、基于 `RUNNING` metric 样本的吞吐跌幅、最终文件过大、每百万操作空间占用过高、MVStore 空间放大率超过 5x、无效 reclamation、backoff 占比过高和 reclamation 事件数量。 |
| 验证 | 跑 smoke 后生成报告，并确认 summary 文件写出。 |
| 风险控制 | 报告分析保持本地只读，除了写入 `report/` 输出目录。 |

## 阶段通用完成标准

每个阶段完成前必须满足：

1. 文档状态从 `Planned` 更新为 `Done`。
2. 新增或修改代码配套测试或 smoke 验证。
3. 运行该阶段最小验证命令。
4. 工作区只包含本阶段相关变更。
5. 本地提交一次，提交信息说明阶段目标。

## 后续建议

LR1-LR4 是最小可用闭环；LR5 覆盖异常退出，LR6 扩展 SQL 层，LR7-LR8 进入长期运行和发布候选验证，LR9 把工具变成可分发的独立包。
