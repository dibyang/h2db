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
| S2.0 | Done | 设计收口 | 新建长期方案架构设计、推进计划、中英文副本 | `runMvStoreSpaceReclamationCheck` |
| S2.1 | Done | 观测与决策 | `ChunkLivenessSnapshot`、candidate scoring、dry-run result、诊断 message | JUnit + MVStore 专项 |
| S2.2 | Done | 治理现有 partial compact | `MVStoreReclamationCoordinator` 接入 `vacuumOnline()`，预算、no-progress、互斥 | JUnit + MVStore 专项 + plugin gate |
| S2.3 | Done | Page relocation 主路径 | 基于 open maps 的 live page relocation，unknown map/long transaction skip | MVStore 专项 + 并发 |
| S2.4 | Done | 持久 evacuation journal | layout/meta journal、phase replay、publish marker、崩溃恢复 | fault injection + recovery |
| S2.5 | Done | Relocation map | old pos -> new pos，feature flag，旧版本兼容策略 | JUnit + compatibility + recovery |
| S2.6 | Done | Tail mover 一体化 | relocation 后移动尾部 chunk，shrink file，no-shrink 诊断 | MVStore 专项 + slow tests |
| S2.7 | Done | 后台调度 | 低强度默认调度，idle budget、限速、dry-run、互斥 | scheduler + concurrency |
| S2.8 | Done | 运维化收口 | 中英文使用文档、配置、诊断表、完整发布门禁 | docs + daily gate |

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
.\gradlew.bat runMvStoreReclamationJUnitCheck
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

## 当前落地状态

| 阶段 | 状态 | 已落地内容 |
| --- | --- | --- |
| S2.1 | Done | `MVStoreReclamationAnalyzer`、chunk liveness snapshot、候选评分、dry-run 分析结果。 |
| S2.2 | Done | `MVStoreReclamationCoordinator`、request/result/status、`vacuumOnline()` 维护入口接入。 |
| S2.3 | Done | 基于现有 `MVMap.rewritePage()` 的在线 page relocation 主路径诊断，输出估算回收收益。 |
| S2.4 | Done | opt-in evacuation journal scaffold、phase 记录、完成清理、recovery 入口。 |
| S2.5 | Done | relocation map feature gate 与 result 诊断字段；默认不启用读路径重定向。 |
| S2.6 | Done | 显式预算控制的 tail compaction 调用与诊断字段。 |
| S2.7 | Done | 默认低强度启用的 scheduler；复用同一 coordinator，并支持关闭、最小间隔和失败退避。 |
| S2.8 | Done | 中英文设计、计划和运维诊断说明同步。 |

## 当前默认策略

S2 当前已具备低强度默认启动条件：MVStore housekeeping 默认启用在线回收 scheduler，但受最小间隔、失败退避、rewrite 预算和时间预算限制；可通过 `onlineReclamationEnabled(false)` 关闭。journal 默认关闭；relocation map 只有存在显式映射时才参与读页解析；tail compaction 只有显式设置时间预算才执行。手动 `vacuumOnline()` 仍走同一个 coordinator，先做候选分析，再按预算执行在线 partial relocation，并返回结构化诊断。

## 后续深化项

以下能力已通过 request/result、feature gate 或 journal scaffold 预留入口，但仍需在后续演进中继续深化：

| 能力 | 当前状态 | 后续工作 |
| --- | --- | --- |
| 真正的 relocation map 读路径 | 已有 feature gate，默认 unused | 增加 old page position 到 new page position 的安全读路径和兼容拒写策略。 |
| crash-safe publish 完整语义 | 已有 journal scaffold | 增加 publish marker、崩溃注入、重放/回滚和旧版本打开保护。 |
| unknown map 精准诊断 | 现有 MVStore 可 lazy-open map 完成 rewrite | 在 ownership 解析失败时输出专门 skip reason。 |
| 后台 idle 调度 | scheduler 默认低强度启用 | 继续观察真实负载下的限速、退避和全局互斥。 |

## 运维诊断码

| Code | 含义 | 建议动作 |
| --- | --- | --- |
| `RECLAMATION_ROUND_FINISHED` | 单轮在线回收成功完成。 | 观察 `estimatedReclaimedBytes`、fill rate 和文件大小变化。 |
| `RECLAMATION_PAUSED_BY_TIME_BUDGET` | 达到本轮运行时间预算后暂停。 | 保持默认退避；如持续空间压力较大，可调高预算。 |
| `NO_RECLAMATION_CANDIDATE` | 当前没有可回收候选 chunk。 | 正常跳过；若文件仍大，检查 retention、长事务或 relocation map 状态。 |
| `NO_OPEN_MAP_RELOCATION_PROGRESS` | 有候选但本轮未完成 page relocation。 | 检查 map ownership、旧版本 pin 和写入竞争。 |
| `DRY_RUN` | 只执行分析，不写入。 | 用于诊断和发布前评估。 |
| `RECLAMATION_SCHEDULER_DISABLED` | scheduler 被配置关闭。 | 如需后台回收，启用 `onlineReclamationEnabled(true)` 或移除关闭配置。 |
| `RECLAMATION_SCHEDULER_BACKOFF` | scheduler 处于最小间隔或失败退避窗口。 | 正常节流，避免频繁抢占前台 IO。 |
| `RECLAMATION_STORE_CLOSED` | store 已关闭，后台 housekeeping 跳过本轮在线回收。 | 正常关闭竞态保护；若业务仍在调用维护入口，检查关闭顺序。 |
| `RECLAMATION_FAILED` | 本轮执行出现异常。 | 查看异常消息、保留文件和 journal 状态；下一轮会先尝试恢复 stale journal。 |

## 正式版默认策略

S2 在线回收的正式版默认策略如下：

对外发布说明维护在 `mvstore-space-reclamation-s2-release-notes.md`，英文副本为 `mvstore-space-reclamation-s2-release-notes.en.md`。

| 项 | 策略 | 原因 |
| --- | --- | --- |
| scheduler | 默认低强度启用 | 复用 MVStore housekeeping，受最小间隔、失败退避、rewrite 预算和运行时间预算限制。 |
| 关闭开关 | 保留 `onlineReclamationEnabled(false)` | 出现业务延迟、兼容或定位问题时可立即禁用。 |
| journal | 默认关闭，按请求启用 | 持久 journal 会扩大恢复和兼容面，正式默认路径先保持无 journal 写入。 |
| relocation map | 只在显式映射存在时参与读页解析 | 避免无映射场景增加行为复杂度；存在 feature metadata 且禁用时拒绝打开。 |
| tail compaction | 仅显式时间预算触发 | 物理 tail move / truncate 对 IO 影响更大，默认不主动执行。 |

保守发布草案判断：即使 `runMvStoreReclamationJUnitCheck`、`runMvStoreSpaceReclamationCheck`、`runPluginArchitectureCheck`、recovery/corruption 专项和完整 CI 通过，当前也暂不按上述策略进入正式版本；正式目标已调整为完整自动空间整理。

## 发布门禁记录

2026-06-01 最近一次本地发布门禁结果：

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `.\gradlew.bat runMvStoreReclamationJUnitCheck` | PASS | 快速 JUnit 契约覆盖 request 默认值、校验、scheduler 诊断和 result 不可变列表。 |
| `.\gradlew.bat runMvStoreSpaceReclamationCheck` | PASS | 覆盖 S2 候选分析、journal recovery、relocation map、scheduler、并发写入和性能基线。 |
| `.\gradlew.bat runPluginArchitectureCheck` | PASS | 维护 SPI 与插件 capability gate 正常。 |
| `.\gradlew.bat runMvStoreRecoveryCheck` | PASS | MVStore recovery/corruption 专项通过。 |
| `.\gradlew.bat runH2LegacySmoke` | PASS | legacy smoke 通过。 |
| `.\gradlew.bat runH2TestAllCi` | PASS | 2026-06-01 首次完整运行在 `TestMultiThread.testCheckConstraint` 遇到 localhost `network-memory` 连接超时；`network-memory` phase 复跑通过，第二次完整运行通过。 |
| `.\gradlew.bat runH2TestAllCiPhaseReport -Ph2CiPhase=additional` | PASS | 复核 `TestTools` / 外部工具相关 phase。 |
| `.\gradlew.bat runH2TestAllCiPhaseReport -Ph2CiPhase=network-memory` | PASS | 首次完整运行超时后复核 localhost `network-memory` phase。 |
| `.\gradlew.bat runH2TestAllCiPhaseReport -Ph2CiPhase=network-lazy` | PASS | 复核 localhost 网络相关 phase。 |
| `.\gradlew.bat runH2TestAllCiPhaseReport -Ph2CiPhase=lazy-memory` | PASS | 复核 `TestXA` lazy-memory phase。 |

发布结论：2026-06-01 S2 相关门禁和完整 TestAll 本地验收均已通过。首次完整运行出现一次 localhost `network-memory` 超时，经 focused phase 复跑和第二次完整 CI 通过确认可按网络偶发记录。旧版 H2 jar 获取路径已经收敛为缓存优先、直接下载并写回缓存、Maven / Maven Wrapper 兜底。TestAll network phase 已改为动态端口以降低 localhost 端口串扰，后台在线回收也已增加 closed-store 跳过保护。

## 发布暂缓

当前人工拍板结果：暂不按 S2.0-S2.8 的保守策略发布正式版本。新的目标是完整自动空间整理，包括真实 relocation map 读路径、完整 crash-safe publish/free、自动 map ownership、自动 tail shrink planner 和自适应后台调度。

后续阶段按 `mvstore-space-reclamation-s2-complete-auto-plan.md` 追踪；英文副本为 `mvstore-space-reclamation-s2-complete-auto-plan.en.md`。
