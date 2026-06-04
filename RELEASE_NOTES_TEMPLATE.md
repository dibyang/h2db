# h2db 2.3.0

[English](RELEASE_NOTES_TEMPLATE.en.md)

## 摘要

简要说明本次 release 的目的。

## 兼容性

说明本 release 是否预期兼容上一版 h2db，以及是否兼容其基于的上游 H2 版本。

## 变更

* 

## 安全

说明本 release 是否包含安全修复。如果披露流程单独协调，请在发布后链接 advisory。

## 存储与恢复说明

说明是否存在 MVStore、文件格式、备份、恢复、recovery 或 corruption-handling 变更。

## SQL 与 JDBC 说明

说明 SQL 解析、执行、兼容模式、JDBC 行为、错误或公开 API 是否变化。

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.3.0</version>
</dependency>
```

## 验证

列出 release 验证命令和 smoke tests。

### LongRun 验收

如果本次 release 包含 LongRun 发布包，请列出已跑 profile：

| Profile | 命令 | 结果 | 关键指标 |
| --- | --- | --- | --- |
| smoke |  |  |  |
| crash/recovery |  |  |  |
| fault-injection |  |  |  |

## 已知问题

列出已知问题；如果没有，写“暂无已知问题”。
