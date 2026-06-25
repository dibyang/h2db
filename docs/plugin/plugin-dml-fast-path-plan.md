# DML 执行入口插件增强计划

## 背景

当前 ADB 在底层 store 和事务层已有较高吞吐潜力，但通过完整 h2db JDBC 路径执行批量写入时，性能收益被 `PreparedStatement` 参数绑定、动态代理、H2 command/session/commit 路径、逐行 `Row` materialize 和 `Table.addRow()` 边界消耗。

已知长测数据：

| 场景 | 吞吐 |
| --- | ---: |
| ADB store | 250,000 ops/s |
| ADB txn | 172,413 ops/s |
| ADB 完整 JDBC `insert_batch100` 长测 | 40,453 ops/s |
| H2 完整 JDBC `insert_batch100` 长测 | 43,365 ops/s |
| 当前完整 JDBC 比例 | 0.93x |

结论：继续在 ADB 侧绕过外层 API 做优化，预计只能把完整 JDBC 路径拉回到 1.x/2.x 区间；如果目标是稳定达到 H2 原生完整 JDBC 的 3x 级别，需要 h2db 增强插件执行入口，让插件在受控语义下接管高频 DML 快路径。

## 目标

| 目标 | 说明 |
| --- | --- |
| DML 插件快路径 | 允许插件识别并接管特定 `INSERT` 计划，优先覆盖 `INSERT ... VALUES` 和 JDBC batch。 |
| 参数直达 | 插件在执行时直接读取 H2 已绑定参数，避免外部 `PreparedStatement` proxy 捕获 setter。 |
| 批量写入入口 | `TableEngineProvider` / 插件表能够接收批量参数视图，减少逐行 `Row` 构造和二次编码。 |
| 保持 JDBC 语义 | `autoCommit`、`rollback`、重复主键异常、update count、generated keys、batch failure 行为保持兼容。 |
| 可观测与可回退 | 插件未接管、SQL 不满足、能力缺失、运行失败时可回退或明确失败，并有诊断信息。 |
| 可验收性能 | 在固定长测下分阶段观察开销下降，最终尝试达到 H2 原生完整 JDBC 的 3x 级别。 |

## 非目标

- 不重写 H2 SQL parser / optimizer。
- 第一轮不覆盖 `INSERT SELECT`、`MERGE`、`UPDATE`、`DELETE`、触发器复杂路径和所有 generated keys 组合。
- 不改变现有 JDBC API 行为和异常类型对外承诺。
- 不让插件绕过 H2 事务边界自行提交或回滚。
- 不把 ADB 私有类型暴露为 h2db 公共 SPI。
- 不在第一阶段承诺所有第三方 table provider 都能使用 bulk 快路径。

## 现状流程

| 路径 | 当前关键类 | 当前问题 |
| --- | --- | --- |
| JDBC prepare | `org.h2.jdbc.JdbcConnection#prepareStatement()`、`JdbcPreparedStatement` | 插件无法在 prepare 阶段识别可接管计划。 |
| 参数绑定 | `JdbcPreparedStatement#setParameter()`、`CommandInterface` 参数列表 | 插件拿不到已绑定参数，只能在外部 proxy setter。 |
| 执行入口 | `JdbcPreparedStatement#executeUpdateInternal()`、`executeBatch()` | batch 逐元素执行，插件无法一次拿到批量参数。 |
| INSERT 执行 | `org.h2.command.dml.Insert` | 现有路径按表达式求值并逐行 `table.addRow(session, row)`。 |
| 表写入 | `org.h2.table.Table#addRow()`、`MVTable#addRow()` | 插件表缺少 bulk insert hook，只能适配逐行写入。 |
| 插件能力 | `TableEngineProvider`、`StorageEngineProvider`、`TransactionEventProvider` | 已有插件入口不包含 statement / prepare / DML 执行层能力。 |

## 核心约束

| 约束 | 要求 |
| --- | --- |
| Java 8 | 新 SPI 不能使用 Java 8 之后语法或 API。 |
| 兼容优先 | 不支持快路径的 SQL 必须保持现有执行路径。 |
| 事务一致 | 插件只接管执行实现，不接管事务边界。 |
| 错误一致 | 重复主键、类型转换、约束失败、batch partial failure 的对外异常和 update count 需保持一致。 |
| 参数只读 | 参数视图必须只读，插件不得修改 H2 参数状态。 |
| 能力显式 | 通过 capability 判断是否支持 DML fast path，不能用类名推断。 |
| 可测试 | 每个阶段必须有单元/集成/长测产出，可追踪到测试编号。 |

## 接口方向

第一轮建议新增三类受控接口，命名可在设计评审时调整：

| 接口 | 作用 | 稳定性建议 |
| --- | --- | --- |
| `DmlExecutionProvider` | 插件级 DML 计划识别和执行入口。 | 实验 SPI |
| `DmlExecutionContext` | 提供 session、table、columns、mode、trace、事务状态等执行上下文。 | 实验 SPI |
| `BoundParameterView` / `BoundParameterBatchView` | 只读参数视图，单行或 batch 形式暴露 `Value`。 | 实验 SPI |
| `BulkInsertTable` | 表级批量写入能力，由插件表可选实现。 | 受管迁移 API |

能力常量建议：

| Capability | 含义 |
| --- | --- |
| `dml.insert.values.fastPath` | 支持 `INSERT ... VALUES` 快路径。 |
| `dml.insert.batch.fastPath` | 支持 JDBC batch insert 快路径。 |
| `table.bulkInsert` | 表实现支持批量写入。 |
| `parameters.bound.view` | provider 可读取 H2 已绑定参数视图。 |

## 语义边界

| 语义 | 要求 |
| --- | --- |
| `autoCommit=true` | H2 外层仍负责每条 statement 或 batch 的提交语义。 |
| `autoCommit=false` | 插件写入必须进入当前 session transaction，可 rollback。 |
| update count | 成功行数必须与 H2 原生路径一致；失败 batch 元素按 JDBC 规则返回或抛出。 |
| 重复主键 | 插件必须映射为 H2 当前唯一约束异常，错误码和 SQLState 尽量保持一致。 |
| generated keys | V1 可只支持无 generated keys 请求；支持前必须补兼容测试。 |
| trigger / delta table | V1 可不接管有触发器、delta change collector 或复杂约束的表。 |
| fallback | provider 返回不接管时，H2 继续原生 `Insert` 路径。 |

## 分阶段实施计划

| 阶段 | 目标 | 产出 | 验收 |
| --- | --- | --- | --- |
| P0 基线固化 | 固化当前 H2/ADB/JDBC 长测口径，避免后续性能结论漂移。 | 基线报告、固定配置、结果记录模板、长测命令。 | 可复跑得到 H2 `insert_batch100` 与 ADB 当前路径数据，误差范围有记录。 |
| P1 设计冻结 | 确认 SPI 包位置、能力名、回退策略和第一轮 SQL 范围。 | RFC 更新、接口草案、兼容矩阵、风险登记。 | 评审通过；明确 V1 只覆盖简单 INSERT VALUES / JDBC batch。 |
| P2 prepare 识别入口 | 在 prepare/command 层识别可接管的 `Insert` 计划，但不改变执行行为。 | `DmlExecutionProvider` 注册与 plan match 原型、诊断日志、测试。 | 插件能识别目标 INSERT，非目标 SQL 不受影响。 |
| P3 参数视图 | 在执行时向 provider 暴露只读单行参数视图，避免外部 proxy setter。 | `BoundParameterView`、参数索引/类型/NULL 测试、只读约束。 | 插件可读取 `executeUpdate()` 已绑定参数；普通 PreparedStatement 行为不变。 |
| P4 batch 参数视图 | 将 JDBC batch 参数集合以只读批量视图暴露给 provider。 | `BoundParameterBatchView`、batch empty/partial failure/clearBatch 测试。 | `executeBatch()` 可一次传入 batch 参数；原生 batch 失败语义保持。 |
| P5 表级 bulk insert | 为插件表增加可选 bulk insert hook，打通 provider 到 table 的批量写入口。 | `BulkInsertTable` 原型、插件表测试、回退到逐行路径。 | 插件表能收到批量参数并返回 update count；非插件表仍走原生路径。 |
| P6 事务与异常兼容 | 补齐 autoCommit、rollback、唯一约束、类型转换、只读表等语义门禁。 | 兼容测试矩阵、错误映射、事务回滚测试。 | 快路径与原生路径在指定场景下结果一致。 |
| P7 性能长测 | 用 ADB 新 hook 路径跑完整 JDBC `insert_batch100` 长测。 | 性能报告、火焰图/采样、瓶颈清单。 | 目标先超过 H2 原生 1.5x；若未达到，输出剩余瓶颈。 |
| P8 3x 冲刺 | 针对 P7 剩余瓶颈做有限优化，不扩大 SQL 范围。 | 优化清单、二次长测报告、是否达成 3x 的结论。 | 达到 H2 原生 3x，或明确还需哪些 h2db/ADB 边界继续改造。 |
| P9 文档与发布收口 | 固化 SPI 使用方式、非支持场景、回退和诊断。 | 开发者文档、release note、示例插件。 | 文档可指导 ADB 移除 PreparedStatement proxy 并接入新 hook。 |

## 阶段验收细则

### P0 基线固化

| 项 | 内容 |
| --- | --- |
| 产出文件 | [dml-fast-path-p0-baseline.md](../perf/dml-fast-path-p0-baseline.md)。 |
| 指标 | H2 完整 JDBC、ADB 完整 JDBC、ADB txn、ADB store。 |
| 目标 | 后续比较统一使用同一机器、JDK、配置、数据规模和 batch size。 |
| 退出条件 | 当前 `0.93x` 比例有可复现证据，报告记录 commit、命令和配置。 |

### P1 设计冻结

| 项 | 内容 |
| --- | --- |
| 产出文件 | 本计划更新、接口草案、兼容矩阵。 |
| 目标 | 防止范围膨胀到全 SQL 执行器改造。 |
| 退出条件 | V1 SQL 范围、fallback 行为、generated keys 处理策略确认。 |

### P2 prepare 识别入口

| 项 | 内容 |
| --- | --- |
| 产出代码 | provider 注册、`Insert` 计划识别、能力查询。 |
| 测试编号 | `T-DML-HOOK-PREPARE-01`、`T-DML-HOOK-FALLBACK-01`。 |
| 目标 | 只建立识别能力，不改变执行结果。 |
| 退出条件 | 普通 H2 `INSERT`、`SELECT`、`UPDATE`、legacy table 行为不变。 |

### P3/P4 参数视图

| 项 | 内容 |
| --- | --- |
| 产出代码 | 单行和 batch 参数只读视图。 |
| 测试编号 | `T-DML-HOOK-PARAM-01`、`T-DML-HOOK-BATCH-01`、`T-DML-HOOK-BATCH-FAIL-01`。 |
| 目标 | ADB 不再需要外层 `PreparedStatement` proxy 捕获 setter。 |
| 退出条件 | 参数 NULL、类型转换、未绑定参数、clearParameters、clearBatch 行为一致。 |

### P5 表级 bulk insert

| 项 | 内容 |
| --- | --- |
| 产出代码 | `BulkInsertTable` 或等价能力接口。 |
| 测试编号 | `T-DML-HOOK-BULK-TABLE-01`、`T-DML-HOOK-BULK-FALLBACK-01`。 |
| 目标 | 插件表一次接收批量数据，减少 H2 `Row` 逐行构造成本。 |
| 退出条件 | 插件表 update count 与原生路径一致，普通表不受影响。 |

### P6 事务与异常兼容

| 项 | 内容 |
| --- | --- |
| 产出代码 | 错误映射、事务回滚、只读/约束失败处理。 |
| 测试编号 | `T-DML-HOOK-TXN-ROLLBACK-01`、`T-DML-HOOK-AUTOCOMMIT-01`、`T-DML-HOOK-DUPKEY-01`。 |
| 目标 | 快路径不牺牲 JDBC 语义。 |
| 退出条件 | 指定兼容矩阵全部通过。 |

### P7/P8 性能验收

| 项 | 内容 |
| --- | --- |
| 产出文件 | 长测报告、采样报告、瓶颈清单。 |
| 阶段目标 | P7 先超过 H2 原生 1.5x；P8 再冲刺 3x。 |
| 失败处理 | 如果未达标，必须说明剩余瓶颈来自 h2db、ADB 编码、事务、IO、锁还是测试配置。 |
| 退出条件 | 达成目标，或给出下一轮必须新增的 h2db hook。 |

## 测试矩阵

| 编号 | 内容 | 阶段 |
| --- | --- | --- |
| `T-DML-HOOK-PREPARE-01` | 插件能识别简单 `INSERT ... VALUES` 计划。 | P2 |
| `T-DML-HOOK-FALLBACK-01` | 不支持 SQL 形态时回到原生路径。 | P2 |
| `T-DML-HOOK-PARAM-01` | executeUpdate 单行参数视图与 H2 参数值一致。 | P3 |
| `T-DML-HOOK-BATCH-01` | executeBatch 参数视图按 addBatch 顺序暴露。 | P4 |
| `T-DML-HOOK-BATCH-FAIL-01` | batch 中单条失败时 update count / exception 语义一致。 | P4 |
| `T-DML-HOOK-BULK-TABLE-01` | 插件表 bulk insert hook 能接收批量写入。 | P5 |
| `T-DML-HOOK-BULK-FALLBACK-01` | 表不支持 bulk 时回退逐行或原生路径。 | P5 |
| `T-DML-HOOK-TXN-ROLLBACK-01` | autoCommit=false 下 rollback 能撤销快路径写入。 | P6 |
| `T-DML-HOOK-AUTOCOMMIT-01` | autoCommit=true 下提交语义与原生一致。 | P6 |
| `T-DML-HOOK-DUPKEY-01` | 重复主键异常映射与原生一致。 | P6 |
| `T-DML-HOOK-GENERATED-KEYS-01` | generated keys 不支持时明确 fallback，支持后结果一致。 | P6/P9 |
| `T-DML-HOOK-PERF-01` | ADB 新 hook 路径跑 `insert_batch100` 长测并生成报告。 | P7 |

## 性能目标

| 阶段 | 指标 | 目标 |
| --- | --- | --- |
| P0 | ADB 当前完整 JDBC / H2 完整 JDBC | 固化当前约 0.93x 基线。 |
| P3 | 移除 proxy setter 捕获后 | 观察 CPU overhead 降低，目标进入 1.x。 |
| P5 | 表级 bulk insert 打通后 | 目标达到或超过 H2 原生 1.5x。 |
| P7 | 新 hook 完整长测 | 目标稳定超过 H2 原生 1.5x。 |
| P8 | 有限优化后 | 目标尝试稳定达到 H2 原生 3x。 |

性能报告必须同时记录：

- h2db commit / ADB commit。
- JDK、机器、磁盘、OS。
- JDBC URL、batch size、行宽、主键策略、事务模式。
- 是否开启 generated keys、autoCommit、索引、约束。
- CPU、GC、IO、锁等待或采样热点。

## 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 快路径破坏 JDBC 语义 | 用户可见行为回归 | P6 前不得进入发布候选；兼容矩阵必须通过。 |
| SPI 过度绑定 ADB | h2db 插件生态不可复用 | 接口只暴露 H2 通用 `Value` / 参数视图和 table capability。 |
| 批量失败语义复杂 | batch update count 不一致 | V1 可先 fallback 复杂失败模式，逐步扩大。 |
| generated keys 支持成本高 | 批量插入场景结果不一致 | V1 明确 unsupported/fallback，后续单独补。 |
| Row materialize 未完全绕过 | 性能达不到 3x | P7 报告必须拆分 Row、Value、编码、事务、IO 开销。 |
| 插件绕过权限/触发器 | 数据库语义破坏 | 有 trigger、delta collector、复杂约束时 V1 默认不接管。 |

## 回滚策略

- 所有快路径必须由 capability gate 控制。
- provider 返回不接管时必须走原生路径。
- 可以增加数据库级或系统级开关禁用 DML fast path。
- 不新增磁盘格式时，代码回滚不影响旧库打开。
- 一旦发现语义不一致，优先关闭快路径，不回滚插件架构基础设施。

## 后续开放问题

| 问题 | 初步建议 |
| --- | --- |
| SPI 放在 `org.h2.api` 还是内部实验包 | 先放实验 SPI，文档标明不承诺二进制稳定。 |
| 参数视图暴露 `Value[][]` 还是 cursor | V1 建议 cursor / view，避免大批量复制数组。 |
| 是否支持 TCP server 路径 | 需要支持；服务端已绑定参数后应能走同一 command 快路径。 |
| generated keys 何时支持 | V1 fallback；P9 或后续单独设计。 |
| `INSERT SELECT` 是否纳入 | 不进 V1，后续单独评估。 |
| 快路径错误是否允许插件自定义 | 不建议；应映射成 H2 当前错误体系。 |
