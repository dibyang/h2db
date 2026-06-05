# 插件化发布就绪说明

本文档记录当前 H2 插件化基础设施的发布就绪状态，用于判断这轮插件化是否已经可以作为稳定基线发布。

## 发布范围

当前版本可以发布静态插件基础设施：

| 领域 | 发布承诺 |
| --- | --- |
| 自动发现 | 插件通过 `ServiceLoader` 自动发现；不支持通过 JDBC URL 加载插件类。 |
| 注册中心 | 插件描述符、provider、capability、依赖、来源和诊断统一通过 registry 路径注册。 |
| 版本 | 同一个 plugin id 的多个版本可在 provider id 不冲突时并存；依赖支持精确版本、`*` 和区间范围。 |
| 表引擎 | 外部 `TableEngineProvider` 可通过 SQL `ENGINE` id 建表，并接收 table/schema 参数。 |
| 存储引擎 | storage provider 支持解析、持久化、诊断、只读救援降级和维护 capability gate。生产主路径 storage engine 仍必须是 MVStore-backed。 |
| 系统目录 | `SystemCatalogProvider` 作为未来非 MVStore 主路径的系统目录所有权边界已经可用。 |
| JDBC URL 前缀 | `JdbcUrlPrefixProvider` 可在自动发现后把 `jdbc:vendor:*` 等 Driver 级自定义前缀映射到 H2 URL。 |
| 事务事件 | `TransactionEventProvider` 可监听 commit / rollback 边界事件并输出失败诊断。 |
| 数据库生命周期 | `DatabaseLifecycleProvider` 可监听数据库关闭事件，不需要 URL listener 注入。 |
| 可观测性 | `INFORMATION_SCHEMA.PLUGINS`、`PLUGIN_PROVIDERS`、`PLUGIN_CAPABILITIES`、`PLUGIN_DEPENDENCIES` 暴露发布级诊断。 |

## 明确非目标

以下能力不是当前版本的发布阻塞项，因为已经明确延期或不纳入规划：

| 能力 | 发布定位 |
| --- | --- |
| 热加载、卸载、在线替换 | 延期。插件只在 Driver 前缀解析和数据库打开期加载。 |
| 插件 manifest、签名、分发策略 | 延期到外部插件分发设计。当前支持的发现契约是 `META-INF/services/org.h2.api.H2Plugin`。 |
| 独立沙箱 | 延期。当前边界是 provider type 白名单、插件级 allowed provider types 和禁止类型诊断失败。 |
| parser、function、auth、optimizer、wire protocol 扩展点 | 不在当前插件规划内。 |
| 非 MVStore 生产主路径 | 延期到系统元数据表、LOB、事务日志和临时结果脱离 `Store` 之后。 |
| 自动性能基线和大资源慢测 | 延期到慢测基础设施。 |

## 必跑门禁

发布当前基线前运行：

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runH2LegacySmoke
```

完整项目发布可由 owner 额外运行完整 H2 CI 门禁；本线程内的插件化小步收口不要求每次运行完整门禁。

## 当前就绪条件

满足以下条件时，插件化基线可以发布：

- 两个必跑门禁通过。
- 发布就绪提交后工作区干净。
- 延期能力仍作为非目标写入文档。
- 任何新增 provider type 都必须同步更新白名单、诊断、测试和中英文文档。
