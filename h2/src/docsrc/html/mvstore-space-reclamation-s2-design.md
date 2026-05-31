# MVStore 空间回收 S2 设计索引

本文档用于固定 S2 的阶段边界，避免后续推进跑偏。

## 阶段定义

| 阶段 | 状态 | 定义 |
| --- | --- | --- |
| S1 | 已完成 | 中期方案。已有维护入口、partial compact 基础、shadow/closed-store 脚手架、专项测试门禁和插件化维护边界。 |
| S2 | 当前推进 | 长期终极方案。目标是 MVStore 内部 chunk/page 级在线回收，不是整库 shadow copy，也不是简单包装 `compactFile()`。 |

S2 的主设计文档是 `mvstore-space-reclamation-long-term-design.md`。后续开发计划、阶段拆分和测试门禁都以该文档为准。

## S2 主线

S2 必须围绕以下长期能力推进：

| 能力 | 说明 |
| --- | --- |
| `MVStoreReclamationCoordinator` | 统一维护入口、预算、互斥、恢复和结果汇总。 |
| `ChunkLivenessAnalyzer` | 分析 chunk fill rate、live/dead bytes、pinning 版本和 map/page ownership。 |
| `ReclamationCandidateSelector` | 根据收益、风险、位置和预算选择候选 chunk。 |
| `PageRelocator` | 将候选 chunk 中仍然 live 的 page 迁移到新位置。 |
| `ChunkEvacuationJournal` | 持久记录任务、阶段、候选 chunk、迁移进度和 publish marker。 |
| `RelocationMap` | 支持旧版本读取被迁移 page，解决 long retention 下 chunk 无法释放的问题。 |
| `TailCompactor` | 在 chunk 已死亡后移动尾部 chunk 并 shrink file。 |

## 非主线

| 能力 | S2 定位 |
| --- | --- |
| 整库 shadow publish | 只作为离线 compact 或兜底能力保留，不作为在线主线。 |
| 单纯包装 `compactFile()` | 只能作为 S2.2 的早期实现路径，不能代表 S2 完成。 |
| 自动后台调度 | 属于 S2.7，已在手动路径和恢复语义稳定后以低强度默认调度落地。 |
| SQL 命令 | 不作为 S2 起点，等 Java maintenance API 和诊断稳定后再评审。 |

## 阶段计划

| 阶段 | 目标 |
| --- | --- |
| S2.0 | 设计收口。 |
| S2.1 | 观测与决策：chunk liveness snapshot、candidate scoring、dry-run result。 |
| S2.2 | 治理现有 partial compact：coordinator 接入、预算、no-progress 诊断。 |
| S2.3 | Page relocation 主路径。 |
| S2.4 | 持久 evacuation journal。 |
| S2.5 | Relocation map。 |
| S2.6 | Tail mover 一体化。 |
| S2.7 | 后台调度。 |
| S2.8 | 对外运维化、文档和长期慢测。 |

## 测试门禁

每个阶段至少运行：

```powershell
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

涉及 `StorageMaintenance` 或插件能力时加跑：

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

高风险阶段再加跑 daily gate 或相关 `TestAll ci` phase，并记录网络偶发失败的 focused phase 复跑结果。
