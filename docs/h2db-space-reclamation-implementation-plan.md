# H2 MVStore 空间回收优化实施计划（草案）

## 意图

本计划用于推进 `.mv.db` 空间回收优化，不替代当前文件损坏恢复修复主线。路线是先把中期 `shadow compact + 短切换` 做成可验证、可回滚的内部能力，再把长期 `online chunk vacuum` 作为存储层优化单独推进。

## 范围

| 范围 | 内容 |
| --- | --- |
| In | 空间膨胀复现、shadow compact、维护态、manifest、crash recovery、TCP server 行为、测试矩阵、灰度计划。 |
| Out | 当前 `.mv.db` 损坏 root cause 修复、多写者 unsupported 场景支持、业务侧备份恢复策略重写、立即实现长期在线 chunk vacuum。 |

## 总体节奏

| 阶段 | 预计时间 | 目标 |
| --- | --- | --- |
| P0 文档和评审 | 2-3 天 | 明确方案边界、测试编号和验收门槛。 |
| P1 测试基础设施 | 1-2 周 | 建立空间回收专用测试入口和 crash/fault injection。 |
| P2 中期原型 | 2-3 周 | 跑通 shadow compact、维护态和基础切换。 |
| P3 中期加固 | 2-3 周 | 补齐 manifest、恢复、并发和 TCP server 行为。 |
| P4 灰度验证 | 1-2 周起 | 大库、慢盘、长稳压测和运维接入验证。 |
| P5 长期研究 | 3-6 个月 | 在线 chunk vacuum 详细设计、原型和长期测试。 |

中期方案预计 4-8 周形成内部可用版本；生产稳妥版本保守看 6-10 周。长期方案保守看 3-6 个月，生产级稳妥需要 6-9 个月。

## 执行门禁

| 门禁 | 要求 |
| --- | --- |
| G0 文档门禁 | 修改 `h2/src/main` 前，必须先更新 RFC 和本计划，并确认方案讨论通过。 |
| G1 测试编号门禁 | 每个新增风险先登记测试编号，再实现生产代码。 |
| G2 只读证明门禁 | 影响恢复、切换或文件替换的逻辑，必须先能通过测试复现失败或刻画当前行为。 |
| G3 crash 门禁 | 任何文件替换、manifest 或 truncate 改动，都必须有 crash 注入测试。 |
| G4 数据门禁 | 所有相关测试必须校验 marker 或模型数据，不能只校验“能打开”。 |
| G5 回滚门禁 | 新功能必须默认关闭或有明确开关，失败后旧库继续可用。 |

## 任务清单

- [ ] 评审 [h2db-space-reclamation-optimization-rfc.md](h2db-space-reclamation-optimization-rfc.md)，确认 S1 为中期主线，S2 作为长期优化。
- [ ] 新增空间回收测试登记表，避免继续膨胀 [h2db-corruption-investigation-plan.md](h2db-corruption-investigation-plan.md)。
- [ ] 新增 `TestMVStoreSpaceReclamation` 或等价测试类，迁移/复用 `T-NO-AUTO-COMPACT-BLOAT-01` 和 `T-OFFLINE-COMPACT-SHRINK-01` 的样本构造逻辑。
- [ ] 新增 Gradle 入口，例如 `runMvStoreSpaceReclamationCheck`，用于独立运行空间回收测试。
- [ ] 实现测试用 fault injection：copy 失败、manifest 写失败、verify 失败、切换前 crash、切换中 crash、清理失败。
- [ ] 设计并评审 `OnlineCompactManifest` 字段和恢复规则。
- [ ] 实现 S1 原型：生成 shadow 文件、校验 shadow 文件、维护态阻塞新事务、完成基础切换。
- [ ] 补齐 copy 期间写入处理策略，优先验证版本扫描增量追平是否可行。
- [ ] 补齐 TCP server、backup、长事务、慢盘、大文件场景测试。
- [ ] 灰度前补齐用户可见文档、配置开关、失败日志和诊断输出。
- [ ] S1 稳定后，启动 S2 `online chunk vacuum` 的详细设计和不变量测试。

## P0：文档和方案评审

目标：把“优化类改造”和“损坏修复主线”分开，避免在未明确风险的情况下改动存储核心。

交付物：

- `docs/h2db-space-reclamation-optimization-rfc.md`
- `docs/h2db-space-reclamation-implementation-plan.md`
- S1/S2 方案取舍结论。
- 第一批测试编号。

验收：

- 明确当前只讨论优化，不修改生产代码。
- 明确中期方案不改变 `.mv.db` 文件格式。
- 明确长期方案需要单独设计，不与中期方案混在一个实现分支。

## P1：测试基础设施

目标：先让风险可复现、可验证、可回归。

建议新增或拆分：

| 文件/入口 | 内容 |
| --- | --- |
| `h2/src/test/org/h2/test/store/TestMVStoreSpaceReclamation.java` | 空间回收专项测试。 |
| `runMvStoreSpaceReclamationCheck` | Gradle 专项运行入口。 |
| fault injection helper | 模拟 manifest、shadow、rename、truncate、verify、crash 失败。 |

测试编号：

| 编号 | 目标 |
| --- | --- |
| `T-SPACE-BLOAT-BASELINE-01` | 固定关闭自动 compact 后的膨胀样本。 |
| `T-SHADOW-COMPACT-SHRINK-01` | shadow compact 能收缩并保留 marker。 |
| `T-ONLINE-COMPACT-BLOCKS-WRITES-01` | 维护态能阻止新写入。 |
| `T-ONLINE-COMPACT-VERIFY-FAIL-01` | 校验失败时不替换旧库。 |
| `T-ONLINE-COMPACT-CRASH-BEFORE-SWITCH-01` | 切换前 crash 旧库可恢复。 |
| `T-ONLINE-COMPACT-CRASH-DURING-SWITCH-01` | 切换中 crash 可恢复到旧库或新库。 |

验收：

```powershell
.\gradlew.bat compileJava
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

说明：第三条为计划新增入口，未实现前不能作为已完成验收。

## P2：中期原型

目标：跑通 `shadow compact + 维护态 + 基础切换` 的最短链路。

工作项：

- 新增内部 `OnlineCompactOptions` 和 `OnlineCompactResult`。
- 新增 shadow 文件命名和清理规则。
- 复用 `MVStoreTool.compact(source, target)` 的重写能力，先在受控入口下生成 shadow。
- 新增最小维护态：阻止新写事务，已有事务按超时等待。
- 完成 shadow 校验后切换。

暂不要求：

- 暂不暴露新 SQL。
- 暂不实现自动调度。
- 暂不实现长期 chunk vacuum。

验收：

- 膨胀样本能够收缩。
- marker 数据不丢。
- 校验失败不替换。
- 切换前失败不影响旧库。

## P3：中期加固

目标：让 S1 从原型变为内部可用。

工作项：

- 完成 `OnlineCompactManifest`。
- 补齐启动恢复。
- 明确 copy 期间写入处理。优先验证“版本扫描增量追平”，不可行时退回“维护态后重做 full copy”过渡方案。
- 明确读请求在维护态中的行为。
- 补齐 TCP server 行为：等待、timeout、busy error 的语义和日志。
- 补齐 backup/restore 互斥策略。
- 补齐 Windows 文件替换和残留清理测试。

验收：

- `T-ONLINE-COMPACT-CATCHUP-WRITES-01` 通过。
- `T-ONLINE-COMPACT-TCP-BEHAVIOR-01` 通过。
- `T-ONLINE-COMPACT-BACKUP-INTERACTION-01` 通过。
- crash 注入矩阵通过。

## P4：灰度验证

目标：在真实运行特征下证明 S1 可靠。

场景：

- 大库：至少覆盖几十 MB、几百 MB 和用户实际量级样本。
- 慢盘：模拟较长 copy、fsync 和 rename。
- 高删除率：持续制造低 fill rate。
- 长事务：长期只读和写事务交错。
- TCP server：多连接并发读写。
- backup：周期 `SCRIPT DROP TO` 与 compact 交错。

退出条件：

- 任一 crash/fault 场景出现不可恢复文件。
- 任一 marker 或模型数据不一致。
- 维护态阻塞时间超过配置且无法诊断。
- 发现需要改磁盘格式但未完成单独 RFC。

## P5：长期 online chunk vacuum

目标：研究并实现更优雅的在线空间回收，但不影响 S1 交付。

阶段：

| 子阶段 | 目标 |
| --- | --- |
| P5.1 不变量文档 | 写清 root、page、chunk、版本和 truncate 的硬约束。 |
| P5.2 白盒测试 | 先写 chunk relocation、旧版本保护、metadata 发布测试。 |
| P5.3 原型 | 只在测试入口下搬迁低利用率 chunk。 |
| P5.4 crash 注入 | 覆盖 relocation、publish、free、truncate 每个落盘点。 |
| P5.5 随机模型 | 随机 put/remove/commit/rollback/vacuum/crash，与内存模型比对。 |
| P5.6 长稳压测 | 多小时到多天循环，评估空间回收和损坏风险。 |

长期方案验收前置：

- `T-ONLINE-VACUUM-RELOCATE-CHUNK-01`
- `T-ONLINE-VACUUM-LONG-TRANSACTION-01`
- `T-ONLINE-VACUUM-CRASH-PUBLISH-01`
- `T-ONLINE-VACUUM-TRUNCATE-01`
- `T-ONLINE-VACUUM-RANDOMIZED-01`

## 验证命令

当前已有入口：

```powershell
.\gradlew.bat compileJava
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat "-Dh2.test.mvStoreRecoveryCorruption.characterize=true" runMvStoreRecoveryCheck
```

计划新增入口：

```powershell
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

每次涉及生产代码变更后，至少运行 `compileJava` 和本计划对应专项测试。涉及文件替换、恢复、manifest、truncate 的变更，必须运行 crash/fault injection 矩阵。

## 风险登记

| 风险 | 阶段 | 测试要求 |
| --- | --- | --- |
| copy 期间写入丢失 | S1 | `T-ONLINE-COMPACT-CATCHUP-WRITES-01` |
| shadow 文件校验不充分 | S1 | `T-ONLINE-COMPACT-VERIFY-FAIL-01` |
| 切换中 crash | S1 | `T-ONLINE-COMPACT-CRASH-DURING-SWITCH-01` |
| 维护态死锁 | S1 | 并发和超时测试，需记录锁顺序。 |
| backup 并发读到半文件 | S1 | `T-ONLINE-COMPACT-BACKUP-INTERACTION-01` |
| 长事务旧版本被提前释放 | S1/S2 | `T-ONLINE-COMPACT-LONG-TRANSACTION-01`、`T-ONLINE-VACUUM-LONG-TRANSACTION-01` |
| truncate 截断仍可见 chunk | S2 | `T-ONLINE-VACUUM-TRUNCATE-01` |

## 开放问题

- S1 的增量追平能否可靠基于 MVStore 版本扫描完成，还是必须引入双写/变更日志。
- 维护态下读请求是否继续允许，需要结合事务可见性和产品侧期望确认。
- 新能力最终是 SQL、工具 API、数据库设置，还是只作为内部运维入口。
- S2 是否能完全不改磁盘格式，需要在详细设计中证明。
