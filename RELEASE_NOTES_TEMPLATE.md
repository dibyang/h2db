# h2db 2.3.2

[English](RELEASE_NOTES_TEMPLATE.en.md)

## 摘要

2.3.2 修复大写字符串缓存项的不安全并发发布，避免读取未初始化的缓存键或值。该缓存用于 JDBC 按列名读取结果等路径。

## 变更

* 使用具有 final 字段的不可变缓存项，保持无锁缓存读取。
* 增加缓存发布约束、哈希碰撞转换和独立连接并发 JDBC 回归测试。
* 测试使用命名守护线程并保留超时检查，避免阻塞任务阻止测试 JVM 退出。

## 兼容性与升级

Maven 坐标为 `net.xdob.h2db:h2db:2.3.2`，保持 Java 8 兼容。
本版本不改变 SQL 语义、JDBC API、网络协议或 MVStore 磁盘格式。2.3.1 数据库可升级使用；升级前备份数据库并停止使用旧驱动的进程，再替换 jar 和重启应用。

## SQL 与 JDBC

修复涉及全局字符串缓存，应用无需修改 SQL。生产报告的异常链为 `StringUtils.toUpperEnglish → JdbcResultSet.getColumnIndex → getString`。
旧版完整 jar 的故障注入重现了相同 H2 异常链；自然并发压力测试未重现。并发发布竞态是高置信根因判断，而非自然复现直接证实。

## 安全、存储与恢复

本版本未声明独立安全修复，也未新增数据库修复或恢复能力。MVStore 实验性回收 API 及上一版本的恢复限制保持不变。

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.3.2</version>
</dependency>
```

## 验证

JDK 8 下发布 jar、sources、javadoc 和 POM 构建通过；实际 `SELECT H2VERSION()` 返回 `2.3.2`。

`runPluginArchitectureCheck`、`runH2LegacySmoke`、`runMvStoreSpaceReclamationCheck`、`runMvStoreRecoveryCheck`、`runMvStoreReclamationJUnitCheck`、`runLongRunJUnitCheck` 和 `runH2TestAllCi` 全部通过。完整 CI 用时 19 分 47 秒。

完整 CI 配置未执行 `TestMVStoreBenchmark`、`TestLargeBlob`、`TestSubqueryPerformanceOnLazyExecutionMode` 和 `TestDefrag` 四项慢速/基准测试。
本次未重新运行 12 小时 LongRun；2.3.1 的长稳结果不作为 2.3.2 验收结果。

## 发布状态

本文件用于 2.3.2 发布准备；实际 Maven Central 发布、标签和 GitHub Release 状态需在发布操作后确认。
