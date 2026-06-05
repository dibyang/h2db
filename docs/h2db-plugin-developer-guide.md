# H2 插件开发者指南

本文档面向插件作者，说明当前插件 SPI 的可用范围、稳定性边界和最小实现方式。更偏使用者视角的自动发现、URL provider 选择和 SQL 示例见 [plugin-usage.md](plugin/plugin-usage.md)。

## 当前支持范围

H2 插件通过 `org.h2.api.H2Plugin` 暴露插件描述，通过 provider 扩展具体能力。当前已支持：

- `TableEngineProvider`：扩展表引擎创建入口。
- `StorageEngineProvider`：扩展数据库级 storage engine。
- `StorageMaintenance`：暴露 compact / vacuum 等维护能力。
- `SystemCatalogProvider`：非 MVStore 主路径前置扩展点，用于收敛系统元数据目录所有权。
- `JdbcUrlPrefixProvider`：Driver 级 URL 前缀扩展点，用于把 `jdbc:vendor:*` 等自定义 URL 映射到 `jdbc:h2:*`。
- `TransactionEventProvider`：事务生命周期扩展点，用于监听 commit / rollback 前后事件。
- 自动发现：H2 读取 classpath 上的 `META-INF/services/org.h2.api.H2Plugin`。

插件加载不再依托 JDBC URL 参数；插件 jar 只要进入应用 classpath 并发布 `ServiceLoader` 文件，就会在 Driver 早期路径和数据库打开路径被发现。

当前插件机制是数据库打开期的静态加载模型。插件加载完成后 registry 固定，运行中不支持热加载、卸载、在线替换或多版本并存。

## API 稳定性承诺

当前插件化 API 分为三层：

| 层级 | 范围 | 承诺 |
| --- | --- | --- |
| 稳定 SPI | `H2Plugin`、`PluginProvider`、`TableEngineProvider`、`StorageEngineProvider`、`SystemCatalogProvider`、`JdbcUrlPrefixProvider`、`TransactionEventProvider`、`StorageMaintenance`、capability 字符串 | 作为插件入口保持源码兼容，新增能力优先通过默认方法或新 capability 扩展。 |
| 受管迁移 API | `TableEngineContext`、`StorageEngineContext`、`CreateTableData`、`Table` / `Index` 相关内部类型 | 插件迁移期可用，但升级 H2 小版本前必须跑契约测试，不承诺长期二进制兼容。 |
| 内部实现 | parser、optimizer、JDBC server、MVStore 物理结构、`Database` 深层生命周期 | 不作为插件 API 暴露，插件不得依赖其调用顺序或字段布局。 |

非 MVStore storage engine 当前可以作为 `StorageEngineProvider` 被注册、诊断和纳入 capability 管理，`SystemCatalogProvider` 也已作为系统元数据目录前置 SPI 接入 provider 白名单和诊断表。但完整替代 H2 主存储路径还需要系统元数据表、LOB、事务日志和临时结果等核心对象脱离 `Store`。因此第一版稳定承诺仍是：生产可用的主路径 storage engine 必须是 MVStore-backed；非 MVStore 主路径将在补齐 system catalog provider 的系统表契约后单独进入实现阶段。

## 插件描述

插件类必须实现 `H2Plugin`，并提供稳定的 `plugin id`、版本、展示名称和 provider 列表。`plugin id` 是依赖解析和诊断输出的主键，发布后不应随意修改。

版本范围支持三类写法：

- `*`：兼容当前 H2。
- 精确版本：例如 `2.2`。
- 区间：例如 `[2.2,3.0)`、`[2.2,2.3]`。

依赖通过 `PluginDependency` 声明。加载多个自动发现插件时，H2 会按依赖顺序注册；缺失依赖或循环依赖会导致启动失败。

最小插件描述示例：

```java
package com.acme;

import java.util.Arrays;
import org.h2.api.H2Plugin;
import org.h2.api.PluginProvider;

public final class AcmePlugin implements H2Plugin {
    public String getId() {
        return "com.acme.plugin";
    }

    public String getVersion() {
        return "1.0.0";
    }

    public String getDisplayName() {
        return "Acme Plugin";
    }

    public Iterable<? extends PluginProvider> getProviders() {
        return Arrays.asList(new AcmeTableProvider());
    }

    public String getH2VersionRange() {
        return "[2.2,3.0)";
    }
}
```

插件类必须提供 public 无参构造器。插件 id、版本、provider type 和 provider id 都不能为空；provider 列表不能为 null，且至少包含一个 provider。

## Provider 约束

外部插件当前只允许注册 table、storage、system catalog、JDBC URL prefix 和 transaction event provider。SQL parser、function、auth、optimizer、wire protocol 等核心扩展点当前规划不纳入。

provider id 在同一 provider type 下必须唯一。内置 provider 不允许被外部插件覆盖。

建议使用反向域名或明确产品前缀作为插件 id，例如 `com.acme.plugin`；provider id 建议保持短小稳定，例如 `acme_table`、`acme_storage`。内置 provider id 如 `mvstore`、`mvstore_secondary` 视为保留名称。

`PluginProvider.supports(String capability)` 必须是无副作用查询，不应打开文件、启动线程或修改数据库状态。当前已知 capability 如下：

| Capability | 含义 |
| --- | --- |
| `table.create` | provider 可创建表 |
| `system.catalog` | provider 可承接系统元数据目录 |
| `transaction.events` | provider 可监听事务 commit / rollback 事件 |
| `storage.persistent` | storage 支持持久化数据库 |
| `storage.transactional` | storage 支持事务 |
| `storage.mvcc` | storage 支持 MVCC |
| `storage.backup` | storage 支持一致性备份 |
| `storage.compact.closed` | storage 支持关闭态 compact |
| `storage.compact.online.maintenance` | storage 支持维护态在线 compact |
| `storage.vacuum.online` | storage 支持在线空间回收 |
| `storage.publish.crashSafe` | storage 支持 crash-safe metadata publish |
| `storage.truncate.safe` | storage 支持安全物理截断 |

新增 capability 应使用稳定字符串，优先沿用 `table.*` 或 `storage.*` 命名空间。

## Driver 级插件加载

`JdbcUrlPrefixProvider` 用于在数据库打开前注册新的 JDBC URL 前缀，例如把自定义 `jdbc:vendor:*` URL 映射为标准 `jdbc:h2:*` URL。因为 `Driver.acceptsURL()` 和 `Driver.connect()` 发生在 `Database` 创建之前，这类 provider 不能依赖数据库 URL 里的插件加载参数才加载。

Driver 级插件只通过 `ServiceLoader` 自动发现。若同一个插件还提供 table/storage provider，Driver 早期 URL 解析和 Database 打开后的 provider 注册会使用同一个 classpath 服务文件。

`JdbcUrlPrefixProvider.toH2Url()` 必须返回以 `jdbc:h2:` 开头的 URL；返回其他前缀会导致连接失败。映射后的 URL 会继续走 H2 原有连接、权限、storage 和 table provider 流程。

## Transaction Event Provider 约束

事务事件 provider 实现 `TransactionEventProvider`，provider type 为 `transaction`。H2 会在存在真实事务的 commit / rollback 前后触发回调：

- `beforeCommit(TransactionContext)`
- `afterCommit(TransactionContext)`
- `beforeRollback(TransactionContext)`
- `afterRollback(TransactionContext)`

`TransactionContext` 暴露当前 `Database`、session id、是否 DDL 边界、当前 auto-commit 状态和事件创建时是否存在事务。它不暴露底层 `Transaction` 对象，也不开放 parser、optimizer、session lifecycle 或 wire protocol 内部细节。

provider 应遵守以下规则：

- 回调必须快速、可重入，并避免长时间持有外部锁。
- `beforeCommit()` 抛错会阻止本次 commit；用于外部资源预提交校验时必须确保失败路径可重试。
- `afterCommit()` 抛错不会回滚已经完成的 H2 事务，只会把 provider 诊断返回给调用方。
- rollback 回调不应执行业务写入；若抛错，调用方会收到带 provider/event/session 的诊断。

## Table Provider 约束

表引擎 provider 实现 `TableEngineProvider`。SQL 中的 `ENGINE` 名称对应 provider id：

```sql
CREATE TABLE TEST(ID INT) ENGINE "acme_table";
CREATE TABLE TEST(ID INT) ENGINE "acme_table" WITH "param1", "param2";
```

当 `ENGINE "provider_id"` 用作 H2 table provider 路由时，建议在 `MODE=REGULAR` 下验证。MySQL 兼容模式中的 table option `ENGINE` 会按 MySQL 语义处理，不代表 H2 插件 table provider 路由。

最小 table provider 示例：

```java
package com.acme;

import org.h2.api.PluginCapability;
import org.h2.api.TableEngineContext;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.mvstore.db.MVStoreBackedStorageEngine;
import org.h2.table.Table;

public final class AcmeTableProvider implements TableEngineProvider {
    public String getType() {
        return TYPE;
    }

    public String getId() {
        return "acme_table";
    }

    public boolean supports(String capability) {
        return PluginCapability.TABLE_CREATE.equals(capability);
    }

    public Table createTable(CreateTableData data, TableEngineContext context) {
        MVStoreBackedStorageEngine storage = (MVStoreBackedStorageEngine) context.getStorageEngine();
        return storage.getStore().createTable(data);
    }
}
```

`TableEngineContext` 提供当前 `Database`、目标 `Schema`、当前 `StorageEngine`、storage engine id、trace、`WITH` 参数、持久化状态和只读状态。表未指定 `WITH` 参数时，会继承 schema default params。

`TableProviderSupport` 提供 table provider 常用的薄支撑方法：

- `requireWritable(...)`：在只读数据库中拒绝创建或修改外部表资源，并输出 provider/table/params 诊断。
- `requireStorageEngine(...)`：校验当前 storage engine 类型，避免 provider 手写不带诊断的强制类型转换。
- `createTableException(...)`：包装 `createTable()` 失败，保留原始 cause，并在错误消息中包含 provider id、table name 和参数摘要。

旧的 `org.h2.api.TableEngine` class-name 路径仍保留用于兼容；新增扩展建议使用 `TableEngineProvider`。

### 自定义 Table / Index 迁移期稳定性

`TableEngineProvider` 的公开 SPI 只稳定到“接收 `CreateTableData` 并返回 `Table`”这一层。自定义 `Table` / `Index` 仍需要依赖 H2 内部对象，当前只作为迁移期受管接口，不承诺长期二进制兼容。

| 类型 | 迁移期等级 | 使用建议 |
| --- | --- | --- |
| `TableEngineProvider` | SPI | 可作为插件入口使用，provider id 和 capability 应保持稳定。 |
| `TableEngineContext` | SPI | 优先从这里读取 schema、storage、trace、参数、只读和持久化状态。 |
| `CreateTableData` | 受管内部输入 | 可读取建表元数据；不要保存为长期对象引用。 |
| `Table` / `TableBase` | 受管内部 API | 可用于自定义 table provider 迁移原型；升级 H2 小版本前必须跑契约测试。 |
| `Index` / `IndexType` / `IndexColumn` | 受管内部 API | 自定义索引实现必须覆盖 scan、cost、row count、drop/rebuild 语义。 |
| `Row` / `SearchRow` / `Value` | 受管内部 API | 可用于行编码边界；不要假设内部布局或对象复用策略稳定。 |
| `SessionLocal` | 高风险内部 API | 仅在必须接入事务、锁或权限时使用；避免把它传播到插件公共 API。 |
| parser / optimizer / JDBC server 内部类 | 非目标 | 本轮插件化不开放这些扩展点。 |

实现自定义 table provider 时应遵守以下规则：

- `createTable()` 失败必须释放已经打开的外部资源，并让同一 DDL 可重试。
- `supports()` 必须无副作用；诊断表可能多次调用它。
- provider 不应缓存 `SessionLocal`、`CreateTableData`、`TableEngineContext` 等请求期对象。
- 只读数据库中不得执行会改变外部存储或索引元数据的动作。
- 若需要 H2 当前上下文，优先提出 `TableEngineContext` 字段扩展，不要直接依赖 `Database` 深层内部状态。

H2 侧表 SPI 契约测试位于 `h2/src/test-plugin/org/h2/test/plugin/TableSpiContractTest.java`。迁移期插件至少应对齐其中的注册、建表、参数透传、schema 上下文、基础 DML、失败诊断、只读 gate 和诊断表用例。

### Provider 原型反馈与诊断规则

自定义 table provider 原型阶段如果发现 `TableEngineContext` 信息不足，按以下优先级处理：

1. 优先补充只读、持久化、schema、trace、storage、参数等上下文字段的契约测试。
2. 只有多个 provider 都需要同一信息时，才考虑给 `TableEngineContext` 新增公开方法。
3. 不通过 `Database` 暴露 parser、optimizer、session lifecycle 或 JDBC server 内部细节。
4. `createTable()` 抛错时应保留原始 cause，并在错误消息中包含 provider id、table name 和关键参数摘要。

## Storage Engine 约束

## System Catalog Provider 约束

`SystemCatalogProvider` 是非 MVStore 主路径的前置 SPI，provider type 为 `system_catalog`。它用于把系统元数据表、LOB、事务日志和临时结果等当前仍依赖 MVStore `Store` 的能力从 `Database` 主路径中逐步抽出。

当前版本稳定以下边界：

- 外部插件可注册 `system_catalog` provider。
- 诊断表可展示该 provider 和 `system.catalog` capability。
- 打开数据库时，实际选中的 storage provider id 必须存在同 id 的 `system_catalog` provider；缺失时数据库打开失败。
- provider 可通过 `validate(SystemCatalogContext)` 声明自身能否服务当前数据库上下文。

当前版本尚不承诺：

- 自动接管 H2 系统表创建。
- 替代 `Database.getStore()`。
- 让任意非 MVStore storage engine 直接成为生产主路径。

因此，非 MVStore 主路径的下一步实施必须先补 system catalog provider 的系统表、LOB、事务日志和临时结果契约测试，再移除 `Database` 对 MVStore `Store` 的硬依赖。

storage engine id 会写入数据库旁路元数据文件。打开数据库时，请求的 `STORAGE_ENGINE` 必须和持久化 id 一致。

缺失 storage provider 时默认拒绝打开。只有同时满足以下条件才允许只读降级：

- 数据库以只读方式打开。
- 显式设置 `MISSING_STORAGE_READ_ONLY_DOWNGRADE=TRUE`。
- 持久化 storage id 与请求 id 一致。

降级路径不能执行写入、compact、vacuum 等会改变文件状态的操作。

最小 storage provider 示例：

```java
package com.acme;

import org.h2.api.PluginCapability;
import org.h2.api.StorageEngine;
import org.h2.api.StorageEngineContext;
import org.h2.api.StorageEngineProvider;

public final class AcmeStorageProvider implements StorageEngineProvider {
    public String getType() {
        return TYPE;
    }

    public String getId() {
        return "acme_storage";
    }

    public boolean supports(String capability) {
        return PluginCapability.STORAGE_PERSISTENT.equals(capability);
    }

    public StorageEngine open(StorageEngineContext context) {
        return new AcmeStorageEngine(context);
    }
}
```

`StorageEngineContext` 提供当前 `Database`、数据库路径、文件密码密钥、数据库设置、只读状态和 trace。`StorageEngine.open()` 失败会导致数据库打开失败；实现方应在失败路径释放已经打开的文件、线程和其他资源。

`StorageEngine.close()` 默认先 `flush()` 再 `closeImmediately()`；如果存储引擎需要更精细的关闭顺序，应覆盖 `close()`。`StorageMaintenance` 的 compact / vacuum 入口必须先通过 capability gate，不能在不支持能力时偷偷执行维护操作。

当前主数据库路径仍要求 storage engine 为 MVStore-backed 实现；第二个内置 provider `mvstore_secondary` 复用 MVStore 物理实现，用于验证 storage provider 选择、持久化 id 和建表路径。

旁路元数据文件名为数据库路径加 `.storage` 后缀。备份、恢复、重命名或手工迁移数据库文件时，应把该文件作为同一数据库元数据的一部分处理。

## 加载方式

插件通过 `ServiceLoader` 自动发现。在插件 jar 中加入：

```text
META-INF/services/org.h2.api.H2Plugin
```

文件内容为插件类名：

```text
com.acme.AcmePlugin
```

插件 jar 位于应用 classpath 后，无需在 JDBC URL 里增加任何插件加载参数。URL 仍可使用 `STORAGE_ENGINE`、`DEFAULT_TABLE_ENGINE` 或 SQL `ENGINE` 来选择已发现的 provider。

## 诊断入口

插件诊断可通过以下 INFORMATION_SCHEMA 表查询：

- `INFORMATION_SCHEMA.PLUGINS`
- `INFORMATION_SCHEMA.PLUGIN_PROVIDERS`
- `INFORMATION_SCHEMA.PLUGIN_CAPABILITIES`

这些表只读，用于查看 plugin id、provider type/id、来源和 capability。

插件加载失败、版本不匹配、依赖缺失、provider 冲突和 provider type 禁止都会在数据库打开时报错。错误消息应包含 plugin id、provider type/id 或 class name，便于定位配置问题。

## 测试建议

插件逻辑优先使用 JUnit 测试，并运行：

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat runPluginArchitectureCheck
```

涉及 MVStore 恢复或空间回收时，还应运行：

```powershell
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
```
