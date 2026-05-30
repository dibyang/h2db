# MVStore 空间回收 S2 长期方案推进计划

本文是 S2 长期方案的可追踪任务计划。S1 中期方案已经完成并归档；S2 从本文开始独立推进。

## 推进规则

1. 每个阶段完成后本地提交一次。
2. 新增生产代码必须同步补测试。
3. 可用 JUnit 覆盖的契约、选项、评分、结果对象优先使用 JUnit。
4. MVStore 文件、故障注入、并发和兼容场景纳入 `runMvStoreSpaceReclamationCheck` 管理。
5. 对外文档同步提供英文副本。

## 阶段任务

| 阶段 | 状态 | 目标 | 主要任务 | 测试门禁 |
| --- | --- | --- | --- | --- |
| S2.0 | In progress | 设计收口 | 新建长期方案架构设计、推进计划、中英文副本 | `runMvStoreSpaceReclamationCheck` |
| S2.1 | Planned | 观测与决策 | `ChunkLivenessSnapshot`、candidate scoring、dry-run result、诊断 message | JUnit + MVStore 专项 |
| S2.2 | Planned | 治理现有 partial compact | `MVStoreReclamationCoordinator` 接入 `vacuumOnline()`，预算、no-progress、互斥 | JUnit + MVStore 专项 + plugin gate |
| S2.3 | Planned | Page relocation 主路径 | 基于 open maps 的 live page relocation，unknown map/long transaction skip | MVStore 专项 + 并发 |
| S2.4 | Planned | 持久 evacuation journal | layout/meta journal、phase replay、publish marker、崩溃恢复 | fault injection + recovery |
| S2.5 | Planned | Relocation map | old pos -> new pos，feature flag，旧版本兼容策略 | JUnit + compatibility + recovery |
| S2.6 | Planned | Tail mover 一体化 | relocation 后移动尾部 chunk，shrink file，no-shrink 诊断 | MVStore 专项 + slow tests |
| S2.7 | Planned | 后台调度 | 默认关闭，idle budget、限速、dry-run、互斥 | scheduler + concurrency |
| S2.8 | Planned | 运维化收口 | 中英文使用文档、配置、诊断表、长期慢测基线 | docs + daily gate |

## S2.1 详细任务

| 任务 | 交付 |
| --- | --- |
| 定义 `ChunkLivenessSnapshot` | chunk id、block/len、fill rate、live/dead bytes、pinning reason。 |
| 定义 `MVStoreReclamationAnalysis` | candidates、skipped chunks、estimated bytes、message。 |
| 候选评分规则 | fill rate、dead bytes、tail position、unknown map、active version。 |
| dry-run 入口 | 不写文件，仅输出分析结果。 |
| 测试 | JUnit 覆盖评分和结果；MVStore 专项覆盖 bloat snapshot。 |

## S2.2 详细任务

| 任务 | 交付 |
| --- | --- |
| 新增 coordinator | 统一 capability、互斥、预算、result message。 |
| 接入 `vacuumOnline()` | 调用 coordinator，不再直接裸调 `compactFile(50)`。 |
| no-progress 诊断 | before/after size、fill rate、chunks fill rate。 |
| 互斥 | backup、close、已有 reclaim job 时返回 busy/skipped。 |
| 测试 | JUnit 覆盖 result；MVStore 专项覆盖入口行为。 |

## S2.3 详细任务

| 任务 | 交付 |
| --- | --- |
| Page relocation 原语 | 基于 `MVMap.rewritePage()` 做 open map live page relocation。 |
| unknown map skip | map 未打开或 ownership 不明确时跳过并诊断。 |
| long transaction skip | 被 old version pin 的 chunk 不强制回收。 |
| 预算中断 | 达到 live bytes / chunks / millis 上限时暂停。 |
| 测试 | open map relocation、unknown map、long transaction、budget pause。 |

## S2.4-S2.8 规划

| 阶段 | 关键风险 | 先决条件 |
| --- | --- | --- |
| S2.4 | journal 语义错误会影响恢复 | S2.3 relocation 行为稳定。 |
| S2.5 | relocation map 会改变读路径兼容面 | S2.4 journal 和 feature flag 完成。 |
| S2.6 | tail move 涉及文件布局和 truncate | S2.5 或至少 S2.3 后 dead chunk 释放稳定。 |
| S2.7 | 后台任务可能影响业务延迟 | 手动入口、预算、互斥、恢复稳定。 |
| S2.8 | 运维文档需要真实诊断项 | S2.1-S2.7 message 和配置收口。 |

## 测试命令

每阶段最低门禁：

```powershell
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

涉及维护 SPI 或插件 capability：

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

高风险阶段：

```powershell
.\gradlew.bat clean test check build runH2LegacySmoke
```

完整验收按需运行 `runH2TestAllCi`，若出现已知 localhost 网络偶发，按 phase report 复跑并记录。
