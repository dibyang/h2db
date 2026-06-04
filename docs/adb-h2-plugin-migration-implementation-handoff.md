# ADB-H2 Plugin Migration Handoff (H2-P1 → ADB-P1)

## 当前状态（截至 2026-06-04）

- H2 插件化核心基线已达成：`PLUGIN_CLASSES`、`PLUGIN_PATHS`、`PluginLoader`、`PluginLoaderTest`、`PluginInformationSchema*`、`StorageEngineResolver`、`PluginHelp` 全部就绪。
- 表引擎 SPI 契约测试已增加：
  - [h2/src/test-plugin/org/h2/test/plugin/TableSpiContractTest.java](/D:/work/java/h2db/h2/src/test-plugin/org/h2/test/plugin/TableSpiContractTest.java)
  - 已覆盖注册、默认引擎创建、`WITH` 参数透传、schema 默认参数透传、`DDL+DML` 冒烟。
- 里程碑计划更新：
  - [docs/h2db-plugin-future-implementation-plan.md](/D:/work/java/h2db/docs/h2db-plugin-future-implementation-plan.md) 已补 `H2-P1` 验收要点。

## ADB-P1 目标（最小原型）

在 `ADB` 侧先做到“只接管表引擎，不替换存储引擎”：

1. 新增 `AdbH2Plugin implements H2Plugin`
   - `id/version/displayName`
   - `getProviders()` 返回一个 `AdbTableEngineProvider`
   - 先保持 `getDependencies()` 空
2. 新增 `AdbTableEngineProvider implements TableEngineProvider`
   - `getType() = "table"`
   - `getId() = "adb"`（或 `adb_like`）
   - `supports(PluginCapability.TABLE_CREATE) = true`
   - `createTable(CreateTableData data, TableEngineContext context)`：
     - 建议先落到最小可行实现（例如：`return context.getDatabase().getStorageEngine() instanceof MVStoreBackedStorageEngine ? ((MVStoreBackedStorageEngine) context.getStorageEngine()).getStore().createTable(data) : throw`）
     - 同时记录 `data.tableEngineParams` 和 `context.getTableEngineParams()` 以便参数链路验收
3. 在 ADB 接入处新增最小配置开关：
   - `jdbc:h2:...;PLUGIN_CLASSES=<adb plugin class>;DEFAULT_TABLE_ENGINE=adb`
   - `jdbc:h2:...;MODE=REGULAR;PLUGIN_CLASSES=<adb plugin class>`
   - `CREATE TABLE t(...) ENGINE "adb" WITH "p1", "p2"`
   - 注意：显式 `ENGINE "adb"` 作为 H2 table provider 路由时按 `MODE=REGULAR` 验证；MySQL table option `ENGINE` 不代表插件路由。

## ADB-P1 最小代码骨架（示意）

```java
public final class AdbH2Plugin implements H2Plugin {
    @Override
    public String getId() { return "adb.h2.plugin"; }
    @Override
    public String getVersion() { return "0.1"; }
    @Override
    public String getDisplayName() { return "ADB H2 Plugin"; }

    @Override
    public Iterable<? extends PluginProvider> getProviders() {
        return Arrays.asList(new AdbTableEngineProvider());
    }
}

public final class AdbTableEngineProvider implements TableEngineProvider {
    public static final String ID = "adb";

    @Override
    public String getType() { return TableEngineProvider.TYPE; }
    @Override
    public String getId() { return ID; }
    @Override
    public boolean supports(String capability) {
        return PluginCapability.TABLE_CREATE.equals(capability);
    }

    @Override
    public Table createTable(CreateTableData data, TableEngineContext context) {
        // 第 1 版：先落地到 MVStore-backed 宿主实现，验证参数路由与 DML 链路
        return ((MVStoreBackedStorageEngine) context.getStorageEngine())
                .getStore().createTable(data);
    }
}
```

> 说明：上面只做最小可验证版本。后续阶段再替换 `createTable` 内部逻辑为 ADB 的 `Table/Index` 实现。

## ADB-P1 验收清单

先做到如下 1:1 对齐测试项后再进 ADB-P2：

- [h2/src/test-plugin/org/h2/test/plugin/TableSpiContractTest.java](/D:/work/java/h2db/h2/src/test-plugin/org/h2/test/plugin/TableSpiContractTest.java) 全部通过。
- ADB 侧可通过一条显式 `ENGINE` DDL 成功创建：
  - `jdbc:h2:...;MODE=REGULAR;PLUGIN_CLASSES=...`
  - `CREATE TABLE t(id int) ENGINE "adb"`
- `WITH` 参数可见且顺序保留：
  - `ENGINE "adb" WITH "p1", "p2"`
- schema 默认参数可见：
  - `CREATE SCHEMA s WITH "schema_p1", "schema_p2"` 后在 `s.t` 创建成功。
- 基础 DML 不报错：`insert/select/count/drop`。

## 下一步建议顺序

1. 先在 ADB 源码中补上述 3 个最小类（或等价类名）。
2. 在一条本地测试脚本里只打 `CREATE TABLE` + 一条 DML：确认 `TableEngineContext#getTableEngineParams()` 能拿到参数。
3. 与现网 `org.adb.*` 业务类解耦前，先保持存储仍用 MVStore（等同 H2 baseline）。
4. 在下一阶段再推进真正 table/index 的自定义实现。

## 立即可执行验收清单（每次改动后）

- `.\gradlew.bat -p h2 runPluginArchitectureCheck`
- `.\gradlew.bat -p h2 test --tests org.h2.test.plugin.TableSpiContractTest`
- `.\gradlew.bat -p h2 test --tests org.h2.test.plugin.PluginLoaderTest#loadsConfiguredPluginClass`

## 风险与回退

- 高风险：实现自定义 `Table`/`Index` 前就导入大量 ADB 专有存储代码。
- 回退线：若参数路由或 createTable 透传异常，先保持 `CREATE TABLE` fallback 到 MVStore baseline（`DEFAULT_TABLE_ENGINE` 空行为），再分步排障。
