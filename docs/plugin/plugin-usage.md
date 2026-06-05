# 插件化使用说明

本文档面向希望扩展 H2 存储引擎或表引擎的使用者和开发者。当前插件机制是静态加载模型：插件通过 `ServiceLoader` 自动发现，并在 Driver 解析自定义 JDBC URL 前缀和数据库打开时加载；运行中不支持热加载、卸载或在线替换。

## 自动发现

插件 jar 需要在 classpath 上提供标准服务文件：

```text
META-INF/services/org.h2.api.H2Plugin
```

文件内容是插件实现类名，每行一个：

```text
com.acme.AcmePlugin
```

H2 不再通过 JDBC URL 加载插件类。URL 只用于选择已发现 provider，例如：

```sql
jdbc:h2:./data/demo;STORAGE_ENGINE=acme_storage
```

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `STORAGE_ENGINE` | `mvstore` | 数据库级存储引擎 provider id |
| `MISSING_STORAGE_READ_ONLY_DOWNGRADE` | `FALSE` | 缺失 storage provider 时是否允许只读降级打开；必须同时只读打开才生效 |

## 最小插件

插件类实现 `H2Plugin`，并返回一个或多个 provider。一个插件可以同时提供 storage provider 和 table provider。

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
        return "1";
    }

    public String getDisplayName() {
        return "Acme Plugin";
    }

    public Iterable<? extends PluginProvider> getProviders() {
        return Arrays.asList(new AcmeTableProvider());
    }
}
```

插件 id、版本、provider type、provider id 都不能为空。一个插件至少要提供一个 provider。重复 provider id、非法 provider type、缺失依赖或依赖环会在数据库打开时失败并给出诊断信息。

## 表引擎插件

表引擎 provider 实现 `TableEngineProvider`。SQL 中的 `ENGINE` 名称对应 provider id。

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

使用示例：

```sql
CREATE TABLE TEST(ID INT) ENGINE "acme_table";
CREATE TABLE TEST(ID INT) ENGINE "acme_table" WITH "param1", "param2";
CREATE SCHEMA S WITH "schema_param";
CREATE TABLE S.TEST(ID INT) ENGINE "acme_table";
```

`WITH` 参数可通过 `TableEngineContext.getTableEngineParams()` 获取。表未指定参数时，会使用 schema default params。

旧的 `org.h2.api.TableEngine` class-name 方式仍保留用于兼容；新增插件建议使用 `TableEngineProvider`。

## 存储引擎插件

存储引擎 provider 实现 `StorageEngineProvider`，数据库 URL 的 `STORAGE_ENGINE` 对应 provider id。

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

数据库创建后，storage engine id 会写入数据库旁路元数据。后续打开同一数据库时，请求的 `STORAGE_ENGINE` 必须与持久化 id 匹配，否则会拒绝打开，避免跨存储引擎误读。

只读降级只用于明确配置的兼容/救援场景：

```sql
jdbc:h2:./data/demo;ACCESS_MODE_DATA=r;STORAGE_ENGINE=missing;MISSING_STORAGE_READ_ONLY_DOWNGRADE=TRUE
```

## 诊断查询

可以通过信息 schema 查看已注册插件和 provider：

```sql
SELECT * FROM INFORMATION_SCHEMA.PLUGINS;
SELECT * FROM INFORMATION_SCHEMA.PLUGIN_PROVIDERS;
SELECT * FROM INFORMATION_SCHEMA.PLUGIN_CAPABILITIES;
```

这些视图会展示插件 id、版本、来源、provider type/id、是否内置、以及 provider 声明的能力。

## 当前边界

当前阶段暂不支持：

| 能力 | 说明 |
| --- | --- |
| 热加载、卸载、在线替换 | 插件只在数据库打开时加载 |
| 插件 manifest 和签名 | 当前通过 `ServiceLoader` 自动发现 |
| 多版本插件并存 | 当前依赖只检查插件 id，复杂版本解算留待后续 |
| parser/function/auth/optimizer/wire protocol 等扩展点 | 当前规划不纳入；白名单只允许 table、storage、system catalog、JDBC URL prefix 和 transaction event provider |
| 独立权限沙箱 | 当前主要边界是 provider type 白名单和加载诊断 |
