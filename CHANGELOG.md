# 更新日志

[English](CHANGELOG.en.md)

本文件记录 h2db 面向外部用户的公开 release 变更。格式遵循“每个版本一节”的方式。

## 2.4.0（2026-07-30）

### 新增

- 增加协调式在线备份与影子恢复能力：通过数据库操作门禁、持久化数据库身份与 schema epoch、MVStore prepared snapshot、参与者协调、原子 bundle manifest、影子校验及 generation 激活栅栏，支持数据库与外部持久化参与者的一致性备份和恢复。
- 增加远程在线备份控制协议及 TCP 协议 v21/v22 兼容处理；旧客户端仍可使用常规 JDBC 和传统备份路径。
- 增加在线备份参与者 SPI、provider 认证规范、故障矩阵、性能报告及中英文运维手册。
- 完成实验性 DML 快路径 V1：覆盖简单 `INSERT ... VALUES` 与 JDBC batch 的识别、只读参数视图、批参数视图、表级批量写入接口，以及事务和错误语义兼容保护。
- 补充 GitHub issue、贡献和安全问题入口，方便外部用户报告缺陷与漏洞。

### 变更

- 在线备份功能默认关闭，并在数据库打开时固定；关闭时保持原有运行路径。启用后会写入内部数据库身份元数据。
- 强化 Executor、Session、MVStore、Server、认证、连接池、文件锁、网络和磁盘重试等生命周期中的中断语义，避免吞掉中断、错误恢复中断状态或污染调用线程。
- 强化 SourceCompiler 并发输出、LOB 立即关闭、工具进程退出、GUI Console 关闭及 FilePathDisk 重试收敛行为。

### 兼容性

- 已验证 2.3 数据库文件、传统 zip 备份、2.3 API 插件，以及新旧版本之间的常规 JDBC 与传统备份兼容路径。
- 启用协调式在线备份并写入数据库身份元数据后，不应再使用 2.3.x 对该数据库执行写操作；旧数据库可继续使用传统备份，或通过 clone onboarding 接入新流程。
- DML 快路径保持实验性和可回退；生成键、触发器、约束、delta table、`INSERT SELECT`、`MERGE`、`UPDATE` 与 `DELETE` 仍走原生执行路径。

### 验证

- 在线备份验收检查通过 82/82，插件架构检查通过 140/140。
- 2.3 兼容矩阵、在线备份故障矩阵、影子恢复与 generation 激活栅栏验证通过。
- 在线备份性能验收中，持续 DML 场景 prepare 延迟 p99/max 为 2ms/2ms；混合 DML、DDL 与长事务场景为 21ms/21ms。

### 已知限制

- 首批生产灰度仅认证一个真实 `adb_ldb` 参与者；generation registry、活动指针、CAS 切换和崩溃恢复由部署/路由层负责。
- DML 快路径尚无本仓库内 ADB/LDB 集成吞吐结论，不声明 1.5x 或 3x 性能目标。

## 2.3.0（2026-06-05）

### 新增

- 增加 H2 插件化静态加载基线：插件通过 `ServiceLoader` 自动发现，支持 table/storage/system catalog/JDBC URL prefix/transaction event/database lifecycle provider，并统一走插件 registry。
- 增加插件版本并存和依赖解析能力；同一 plugin id 的多个版本可在 provider id 不冲突时并存，依赖版本支持精确版本、`*` 和区间范围。
- 增加插件诊断视图：`INFORMATION_SCHEMA.PLUGINS`、`PLUGIN_PROVIDERS`、`PLUGIN_CAPABILITIES`、`PLUGIN_DEPENDENCIES`，可查看插件描述符、provider、capability、依赖、来源和多版本归属。
- 增加插件发布就绪说明，明确当前版本的发布范围、非目标、必跑门禁和后续新增 provider type 的收口规则。
- 增加实验性 DML 执行快路径 SPI：`DmlExecutionProvider`、只读参数视图、JDBC batch 参数视图和 `BulkInsertTable`，用于插件在受控条件下接管简单 `INSERT ... VALUES` / batch 写入。
- 补齐开源发布材料，包括 README、贡献指南、安全策略、支持说明、GitHub Release 指南、Maven Central 发布指南、第三方通知和中英文副本。
- 增加 MVStore 空间回收实验性维护 API 文档，说明受控维护窗口、诊断、残留清理和回滚策略。
- MVStore 空间回收维护 API 增加公开稳定级别、入口形态查询和诊断事件监听器。
- 增加独立 LongRun 长稳测试发布包，包含 smoke、reopen、crash/recovery、fault-injection、nightly、comprehensive 和 30 天 soak 配置。
- LongRun Linux/macOS 包装脚本增加 `watch` 模式，可后台启动或复用实例并直接跟随日志；后台启动默认轮转旧日志，并支持 `--append-log`、`--truncate-log` 与 `H2_LONGRUN_LOG_POLICY`。
- LongRun 报告生成后会直接打印 Markdown summary，同时继续写入 `report/summary.md` 和 `report/summary.properties`。
- LongRun metrics 增加 lifecycle `phase`，报告中的吞吐跌幅告警只使用 `RUNNING` 样本，避免 crash/recovery 窗口误报吞吐 WARN。
- 增加 copy-based 文件损坏注入 profile，支持 `truncate`、`bit-flip`、`zero-range`、`random-range` 和 `partial-page` 副本损坏分类。

### 变更

- 插件加载不再支持通过 JDBC URL 配置插件类；插件 jar 进入 classpath 并发布 `META-INF/services/org.h2.api.H2Plugin` 后自动发现，URL 只用于选择已发现的 provider。
- 加固插件权限和隔离边界，非法 provider type、插件级 allowed provider type 违规、ServiceLoader 自动发现失败、无效描述符、依赖缺失和依赖环都会输出可诊断错误。
- Gradle 发布产物会在 main jar、sources jar 和 javadoc jar 的 `META-INF/` 下包含 `LICENSE.txt` 与 `NOTICE.txt`。
- LongRun 发布包 README、设计文档、单实例策略文档和 profile 默认时间文档已同步中英文说明。

### 验证

- 插件化发布门禁通过：`runPluginArchitectureCheck`。
- H2 legacy smoke 通过：`runH2LegacySmoke`。
- LongRun smoke 10 分钟验收通过：`PASS`，约 1409 万操作，4 次 reopen，60 次 reclamation success，0 suspicious log lines。
- LongRun performance 3 分钟预发布对比显示，启用在线空间回收后有中等幅度吞吐开销，同时显著降低最终文件大小和 MVStore 空间放大。
- LongRun crash/recovery 30 分钟验收通过：`PASS`，15 个 crash cycle，29 次 recovery check，0 warnings，0 suspicious log lines。
- LongRun fault-injection 30 分钟验收通过：`PASS`，14 次 fault injection，11 次 recovered，3 次 detected / detected by verify，0 unexpected，0 suspicious log lines。

### 已知限制

- 当前插件机制是静态加载模型，不支持热加载、卸载或在线替换；插件 manifest、签名、独立沙箱、parser/function/auth/optimizer/wire protocol 扩展点和非 MVStore 生产主路径均不在本版本发布范围内。
- DML 执行快路径仍是实验 SPI，V1 仅覆盖简单 `INSERT ... VALUES` / JDBC batch；generated keys、触发器、约束、delta table、`INSERT SELECT`、`MERGE`、`UPDATE` 和 `DELETE` 默认回退原生路径。ADB/LDB 新 hook 长测尚未在本仓库内跑通，因此不宣称 1.5x 或 3x 性能目标。
- MVStore 空间回收能力当前是实验性维护 API，不暴露 SQL，不自动调度。
- prepared shadow 生成后源文件发生变化时，默认拒绝切换；显式开启降级选项后会执行维护态 full-copy。
- LongRun live write-order、torn-write 和 FilePath 级 chaos 注入尚未启用；当前 fault-injection profile 只损坏数据库副本，不损坏活动数据库。
