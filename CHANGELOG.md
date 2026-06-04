# 更新日志

[English](CHANGELOG.en.md)

本文件记录 h2db 面向外部用户的公开 release 变更。格式遵循“每个版本一节”的方式。

## 2.3.0（待发布）

### 新增

- 补齐开源发布材料，包括 README、贡献指南、安全策略、支持说明、GitHub Release 指南、Maven Central 发布指南、第三方通知和中英文副本。
- 增加 MVStore 空间回收实验性维护 API 文档，说明受控维护窗口、诊断、残留清理和回滚策略。
- MVStore 空间回收维护 API 增加公开稳定级别、入口形态查询和诊断事件监听器。
- 增加独立 LongRun 长稳测试发布包，包含 smoke、reopen、crash/recovery、fault-injection、nightly、comprehensive 和 30 天 soak 配置。
- LongRun Linux/macOS 包装脚本增加 `watch` 模式，可后台启动或复用实例并直接跟随日志；后台启动默认轮转旧日志，并支持 `--append-log`、`--truncate-log` 与 `H2_LONGRUN_LOG_POLICY`。
- LongRun 报告生成后会直接打印 Markdown summary，同时继续写入 `report/summary.md` 和 `report/summary.properties`。
- LongRun metrics 增加 lifecycle `phase`，报告中的吞吐跌幅告警只使用 `RUNNING` 样本，避免 crash/recovery 窗口误报吞吐 WARN。
- 增加 copy-based 文件损坏注入 profile，支持 `truncate`、`bit-flip`、`zero-range`、`random-range` 和 `partial-page` 副本损坏分类。

### 变更

- Gradle 发布产物会在 main jar、sources jar 和 javadoc jar 的 `META-INF/` 下包含 `LICENSE.txt` 与 `NOTICE.txt`。
- LongRun 发布包 README、设计文档、单实例策略文档和 profile 默认时间文档已同步中英文说明。

### 验证

- LongRun smoke 10 分钟验收通过：`PASS`，约 1409 万操作，4 次 reopen，60 次 reclamation success，0 suspicious log lines。
- LongRun performance 3 分钟预发布对比显示，启用在线空间回收后有中等幅度吞吐开销，同时显著降低最终文件大小和 MVStore 空间放大。
- LongRun crash/recovery 30 分钟验收通过：`PASS`，15 个 crash cycle，29 次 recovery check，0 warnings，0 suspicious log lines。
- LongRun fault-injection 30 分钟验收通过：`PASS`，14 次 fault injection，11 次 recovered，3 次 detected / detected by verify，0 unexpected，0 suspicious log lines。

### 已知限制

- MVStore 空间回收能力当前是实验性维护 API，不暴露 SQL，不自动调度。
- prepared shadow 生成后源文件发生变化时，默认拒绝切换；显式开启降级选项后会执行维护态 full-copy。
- LongRun live write-order、torn-write 和 FilePath 级 chaos 注入尚未启用；当前 fault-injection profile 只损坏数据库副本，不损坏活动数据库。
