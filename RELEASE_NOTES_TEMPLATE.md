# h2db 2.3.0

[English](RELEASE_NOTES_TEMPLATE.en.md)

## 摘要

本次 release 是 h2db 首个面向公开发布材料完整性的 2.3.0 基线，重点补齐开源发布材料、MVStore 空间回收实验性维护 API、LongRun 长稳测试发布包，以及 H2 静态插件化基础设施。

## 兼容性

本 release 继续保持 H2 嵌入式与服务端数据库模型，Maven 坐标为 `net.xdob.h2db:h2db:2.3.0`。插件化能力以静态加载方式发布，不要求用户通过 JDBC URL 加载插件类；已有 H2 常规 URL 和 legacy table engine class-name 方式仍保持兼容。

## 变更

* 新增静态插件化基线：`ServiceLoader` 自动发现、统一 provider registry、table/storage/system catalog/JDBC URL prefix/transaction event/database lifecycle provider。
* 新增插件版本并存、依赖解析和诊断视图：`INFORMATION_SCHEMA.PLUGINS`、`PLUGIN_PROVIDERS`、`PLUGIN_CAPABILITIES`、`PLUGIN_DEPENDENCIES`。
* 加固插件权限边界和错误诊断：非法 provider type、插件级 allowed provider type 违规、ServiceLoader 自动发现失败、无效描述符、依赖缺失和依赖环都会失败并输出诊断。
* 新增 MVStore 空间回收实验性维护 API 文档、状态查询、入口形态查询和诊断事件监听器。
* 新增独立 LongRun 长稳测试发布包，包含 smoke、performance、crash/recovery、fault-injection、nightly、comprehensive 和 30 天 soak 配置。
* 补齐 README、贡献指南、安全策略、支持说明、Maven Central 发布指南、GitHub Release 指南、第三方通知和中英文副本。

## 安全

本 release 未声明单独安全修复。发布材料补齐了 `SECURITY.md` 和凭据保护检查，发布凭据、GPG 私钥和 staging secrets 不应提交到仓库。

## 存储与恢复说明

MVStore 空间回收能力仍是实验性维护 API，不改变 SQL 入口，不自动调度。当前插件化存储扩展支持 provider 注册、storage id 持久化、缺失 provider 只读救援降级和维护 capability gate；生产主路径 storage engine 仍必须是 MVStore-backed。

## SQL 与 JDBC 说明

新增 `JdbcUrlPrefixProvider` 可在自动发现后把 `jdbc:vendor:*` 等 Driver 级前缀映射到 H2 URL。插件类加载不再通过 JDBC URL 配置；URL 只用于选择已发现 provider。parser、function、auth、optimizer 和 wire protocol 扩展点不在本版本插件化范围内。

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.3.0</version>
</dependency>
```

## 验证

主要验证命令：

```powershell
cd h2
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runH2LegacySmoke
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat --rerun-tasks runLongRunJUnitCheck
.\gradlew.bat --rerun-tasks longRunTestDistZip
```

### LongRun 验收

如果本次 release 包含 LongRun 发布包，请列出已跑 profile：

| Profile | 命令 | 结果 | 关键指标 |
| --- | --- | --- | --- |
| smoke | `./bin/h2-longrun watch -c config/smoke.properties` | PASS | 约 1409 万操作，4 次 reopen，60 次 reclamation success，0 suspicious log lines。 |
| performance | `./bin/h2-longrun watch -c config/performance.properties` | PASS | 启用在线空间回收后有中等幅度吞吐开销，同时显著降低最终文件大小和 MVStore 空间放大。 |
| crash/recovery | `./bin/h2-longrun watch -c config/crash-recovery.properties` | PASS | 15 个 crash cycle，29 次 recovery check，0 warnings，0 suspicious log lines。 |
| fault-injection | `./bin/h2-longrun watch -c config/fault-injection.properties` | PASS | 14 次 fault injection，11 次 recovered，3 次 detected / detected by verify，0 unexpected。 |

performance profile 结果说明：本次 release 的在线空间回收带来中等幅度吞吐开销，但显著降低 MVStore 文件增长和空间放大；该取舍符合长稳运行目标。

## 已知问题

* 插件化当前是静态加载模型，不支持热加载、卸载或在线替换。
* 插件 manifest、签名、独立沙箱、parser/function/auth/optimizer/wire protocol 扩展点不在本版本发布范围内。
* 非 MVStore 生产主路径延期到系统元数据表、LOB、事务日志和临时结果脱离 `Store` 之后。
* MVStore 空间回收能力是实验性维护 API，不暴露 SQL，不自动调度。
