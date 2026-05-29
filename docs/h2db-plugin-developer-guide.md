# H2 插件开发者指南

## 当前支持范围

H2 插件通过 `org.h2.api.H2Plugin` 暴露插件描述，通过 provider 扩展具体能力。当前已支持：

- `TableEngineProvider`：扩展表引擎创建入口。
- `StorageEngineProvider`：扩展数据库级 storage engine。
- `StorageMaintenance`：暴露 compact / vacuum 等维护能力。
- 显式类加载：`PLUGIN_CLASSES=a.b.Plugin`。
- 隔离路径加载：`PLUGIN_PATHS=/path/to/plugin.jar`。
- 可选自动发现：`PLUGIN_SERVICE_LOADER=TRUE` 时读取 `META-INF/services/org.h2.api.H2Plugin`。

默认不会自动启用 classpath 上的 ServiceLoader 插件，避免 provider 冲突和启动行为不可控。

## 插件描述

插件类必须实现 `H2Plugin`，并提供稳定的 `plugin id`、版本、展示名称和 provider 列表。`plugin id` 是依赖解析和诊断输出的主键，发布后不应随意修改。

版本范围支持三类写法：

- `*`：兼容当前 H2。
- 精确版本：例如 `2.2`。
- 区间：例如 `[2.2,3.0)`、`[2.2,2.3]`。

依赖通过 `PluginDependency` 声明。加载多个显式插件时，H2 会按依赖顺序注册；缺失依赖或循环依赖会导致启动失败。

## Provider 约束

外部插件当前只允许注册 table 和 storage provider。SQL parser、optimizer、wire protocol 等核心扩展点尚未开放。

provider id 在同一 provider type 下必须唯一。内置 provider 不允许被外部插件覆盖。

## Storage Engine 约束

storage engine id 会写入数据库旁路元数据文件。打开数据库时，请求的 `STORAGE_ENGINE` 必须和持久化 id 一致。

缺失 storage provider 时默认拒绝打开。只有同时满足以下条件才允许只读降级：

- 数据库以只读方式打开。
- 显式设置 `MISSING_STORAGE_READ_ONLY_DOWNGRADE=TRUE`。
- 持久化 storage id 与请求 id 一致。

降级路径不能执行写入、compact、vacuum 等会改变文件状态的操作。

## 诊断入口

插件诊断可通过以下 INFORMATION_SCHEMA 表查询：

- `INFORMATION_SCHEMA.PLUGINS`
- `INFORMATION_SCHEMA.PLUGIN_PROVIDERS`
- `INFORMATION_SCHEMA.PLUGIN_CAPABILITIES`

这些表只读，用于查看 plugin id、provider type/id、来源和 capability。

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
