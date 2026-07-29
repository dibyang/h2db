# DML 执行入口插件增强 RFC

[English](plugin-dml-fast-path-rfc.en.md)

## 背景

本 RFC 固化 [plugin-dml-fast-path-plan.md](plugin-dml-fast-path-plan.md) 的 P1 设计冻结结果。P1 的目的不是实现完整快路径，而是把 V1 的 SPI 边界、能力名、SQL 范围、fallback 和 JDBC 兼容要求固定下来，避免后续实现阶段不断扩大范围。

P0 基线见：[dml-fast-path-p0-baseline.md](../perf/dml-fast-path-p0-baseline.md)。

## 目标

| 目标 | 决策 |
| --- | --- |
| SPI 包位置 | DML fast path 第一轮放在 `org.h2.api`，但文档标为 experimental SPI。 |
| V1 SQL 范围 | 单条 fast path 仅覆盖简单 `INSERT ... VALUES`；`PreparedStatement` batch 暂按元素进入标准 command lifecycle。 |
| 参数视图 | 暴露只读 `Value` 参数视图；优先 cursor/view，不复制大批量数组。 |
| bulk 表入口 | 插件表可选实现 bulk insert 能力，普通表不受影响。 |
| fallback | provider 不接管或能力不足时回到 H2 原生 `Insert` 路径。 |
| 事务边界 | H2 `SessionLocal` / transaction lifecycle 仍负责提交和回滚。 |

> 当前正确性收敛状态：批量参数视图和 capability 继续作为 experimental SPI
> 保留，但 `executeBatch()` 暂不把整个 batch 一次性交给 provider。H2 按元素
> 进入标准 command lifecycle，并可继续使用已支持的单条 fast path。只有后续
> Batch SPI 能完整表达部分失败、`updateCounts`、回滚和 generated keys 语义后，
> 才恢复整批接管。

## 非目标

- V1 不覆盖 `INSERT SELECT`、`MERGE`、`UPDATE`、`DELETE`。
- V1 不接管带触发器、delta change collector、复杂 generated keys 需求的路径。
- V1 不暴露 ADB 私有类型。
- V1 不改变磁盘格式、JDBC 公共 API、wire protocol。
- V1 不允许插件绕过 H2 权限、约束、事务和错误体系。

## 新增 SPI 草案

### `DmlExecutionProvider`

```java
package org.h2.api;

public interface DmlExecutionProvider extends PluginProvider {

    String TYPE = "dml-execution";

    DmlExecutionPlan prepareDml(DmlPrepareContext context);
}
```

约束：

- `prepareDml()` 只做计划识别和轻量元数据检查，不执行写入。
- 不支持的计划返回 `DmlExecutionPlan.none()` 或等价空计划。
- provider 不得缓存 `SessionLocal`、`Command`、`Parameter` 等请求期可变对象。
- H2 按 provider ID 稳定顺序评估候选；零匹配回退原生路径，一个匹配接管，多个匹配以配置歧义失败，不依赖 registry map 遍历顺序。
- `getTableEngineProviderId()`返回建表时实际选择的 table-engine provider ID，provider 应优先据此收窄归属。

### `DmlExecutionPlan`

```java
package org.h2.api;

public interface DmlExecutionPlan {

    boolean isSupported();

    long execute(DmlExecutionContext context);
}
```

约束：

- `execute()` 返回 update count。
- `execute()` 只能在 H2 当前 session/transaction 中执行。
- provider 抛出的异常必须由 H2 包装或映射到当前错误体系。

### `DmlPrepareContext`

```java
package org.h2.api;

public interface DmlPrepareContext {

    String getStatementType();

    String getTableName();

    String getTableEngineProviderId();

    int getColumnCount();

    boolean isBatchCapable();

    boolean requestsGeneratedKeys();
}
```

V1 中 `getStatementType()` 只要求识别 `INSERT_VALUES`。

### `DmlExecutionContext`

```java
package org.h2.api;

public interface DmlExecutionContext {

    Object getSession();

    Object getTable();

    BoundParameterView getParameters();

    BoundParameterBatchView getBatchParameters();

    boolean isBatch();

    boolean isAutoCommit();
}
```

`getSession()` 和 `getTable()` 第一轮可作为受管迁移 API 返回 H2 内部对象；文档必须说明不承诺长期二进制稳定。后续如果多个 provider 需要稳定能力，再逐步收窄成稳定方法。

### 参数视图

```java
package org.h2.api;

public interface BoundParameterView {

    int size();

    Value getValue(int index);
}

public interface BoundParameterBatchView {

    int size();

    BoundParameterView get(int rowIndex);
}
```

约束：

- `index` 和 `rowIndex` 使用 0 基下标。
- 视图只读。
- 视图生命周期只在当前 `executeUpdate()` / `executeBatch()` 调用内有效。
- 插件不得保存视图供异步线程使用。

### `BulkInsertTable`

```java
package org.h2.api;

public interface BulkInsertTable {

    long addRows(DmlExecutionContext context, BoundParameterBatchView rows);
}
```

约束：

- 只作为插件表可选能力。
- 不支持时走普通 `Table.addRow()` 或原生 `Insert` 路径。
- 不负责提交或回滚。

## Capability 决策

| Capability | 阶段 | 含义 |
| --- | --- | --- |
| `dml.insert.values.fastPath` | P2 | 支持简单 `INSERT ... VALUES` 计划识别。 |
| `parameters.bound.view` | P3 | 支持读取已绑定参数视图。 |
| `dml.insert.batch.fastPath` | P4 | 支持 JDBC batch insert 参数视图。 |
| `table.bulkInsert` | P5 | 表实现支持批量写入入口。 |

这些能力应加入 `PluginCapability` 字符串常量。调用方必须先检查 capability，不能使用 `instanceof` 或 provider 类名推断。

## V1 SQL 范围

| SQL 形态 | V1 行为 |
| --- | --- |
| `INSERT INTO T VALUES (?, ?)` | 可接管。 |
| `INSERT INTO T(C1, C2) VALUES (?, ?)` | 可接管。 |
| `PreparedStatement#addBatch()` + `executeBatch()` | 暂按元素执行；整批接管等待 Batch SPI V2。 |
| 多 VALUES 常量混合参数 | P2 可识别；P3/P4 根据参数视图能力决定是否接管。 |
| `INSERT SELECT` | fallback。 |
| `MERGE` | fallback。 |
| `UPDATE` / `DELETE` | fallback。 |
| generated keys requested | V1 fallback。 |
| 触发器 / delta collector 参与 | V1 fallback。 |

## 兼容矩阵

| 场景 | V1 要求 | 验收编号 |
| --- | --- | --- |
| 普通 INSERT 不匹配 | 原生执行路径不变 | `T-DML-HOOK-FALLBACK-01` |
| 参数未绑定 | 与原生 PreparedStatement 相同错误 | `T-DML-HOOK-PARAM-01` |
| `clearParameters()` 后执行 | 与原生路径一致 | `T-DML-HOOK-PARAM-01` |
| `clearBatch()` 后执行 | 空 batch 语义一致 | `T-DML-HOOK-BATCH-01` |
| batch 中重复主键 | update count / exception 语义一致 | `T-DML-HOOK-BATCH-FAIL-01` |
| `autoCommit=false` rollback | 快路径写入可回滚 | `T-DML-HOOK-TXN-ROLLBACK-01` |
| `autoCommit=true` | 提交语义一致 | `T-DML-HOOK-AUTOCOMMIT-01` |
| generated keys 请求 | V1 fallback，不改变结果 | `T-DML-HOOK-GENERATED-KEYS-01` |

## 回退策略

| 情况 | 行为 |
| --- | --- |
| provider 未注册 | 原生路径。 |
| provider 无对应 capability | 原生路径。 |
| SQL 不在 V1 范围 | 原生路径。 |
| 表不支持 bulk | 原生路径或逐行路径。 |
| generated keys 请求 | 原生路径。 |
| provider 识别后执行失败 | 按 H2 当前错误体系抛出，不静默重试原生路径，避免重复写入。 |

## 风险登记

| 风险 | 缓解 |
| --- | --- |
| SPI 过早稳定导致后续兼容压力 | 标记 experimental；P9 再决定是否纳入稳定文档。 |
| 参数视图被插件异步保存 | 文档禁止；测试用 provider 验证生命周期假设；必要时视图实现做执行后失效检查。 |
| batch partial failure 复杂 | P4/P6 覆盖失败矩阵；复杂场景先 fallback。 |
| generated keys 隐性改变 | V1 对 generated keys 请求一律 fallback。 |
| 插件写入绕过 H2 约束 | V1 仅允许明确可接管的插件表；普通 MVTable 不默认走 provider 写入。 |

## P1 验收

| 要求 | 证据 |
| --- | --- |
| SPI 包位置确认 | 本文 `SPI 包位置` 决策。 |
| capability 名称确认 | `Capability 决策` 表。 |
| V1 SQL 范围确认 | `V1 SQL 范围` 表。 |
| fallback 策略确认 | `回退策略` 表。 |
| generated keys 策略确认 | `V1 SQL 范围` 和 `回退策略` 表。 |
| 兼容矩阵确认 | `兼容矩阵` 表。 |

P1 完成后，P2 才能开始修改生产代码。
