# MVStore 空间回收 S2 启动准备

本文档记录 S2 长期终极方案的启动条件。阶段边界已经明确：S1 中期方案已完成；S2 就是长期终极方案，后续空间回收工作统一归入 S2.0-S2.8。

## 启动结论

S2 已通过当前发布门禁实现。插件化前置已经收口，MVStore storage engine 已通过内置 provider 暴露维护能力，专项测试和完整 legacy 门禁可运行。S2 主线是 MVStore 内部 chunk/page 级在线回收，不是整库 shadow copy，也不是简单包装 `compactFile()`。

## 已具备基础

| 基础 | 状态 |
| --- | --- |
| 维护入口 | `StorageMaintenance.vacuumOnline()` 已存在，可作为 S2 对外入口。 |
| 局部 compact 基础 | `MVStore.compact()`、`MVStore.compactFile()`、`FileStore.rewriteChunks()`、`RandomAccessStore.compactMoveChunks()` 已存在。 |
| 离线/兜底脚手架 | `MVStoreSpaceReclamation` 提供 closed-store shadow compact、manifest、recover、fault harness 基础。 |
| 诊断基础 | `MVStoreSpaceReclamationResult`、listener、phase 和专项测试已存在。 |
| 测试门禁 | `runMvStoreSpaceReclamationCheck` 可独立运行。 |
| 插件化前置 | storage/table provider、维护 capability、诊断表和 JUnit plugin gate 已完成。 |

## S2 阶段任务

| 阶段 | 目标 | 主要交付 |
| --- | --- | --- |
| S2.0 | 设计收口 | 中英文长期方案、阶段计划、测试门禁确认。 |
| S2.1 | 观测与决策 | chunk liveness snapshot、candidate scoring、dry-run result。 |
| S2.2 | 治理现有 partial compact | coordinator 接入、预算、no-progress 诊断。 |
| S2.3 | Page relocation 主路径 | open maps 的 live page 迁移、long transaction/unknown map skip。 |
| S2.4 | 持久 evacuation journal | crash 后恢复、继续或清理未完成任务。 |
| S2.5 | Relocation map | 支持旧版本读取被迁移 page，解决 long retention pinning。 |
| S2.6 | Tail mover 一体化 | relocation 后 move tail chunks 和 shrink file。 |
| S2.7 | 后台调度 | 低强度默认调度，支持 idle budget、限速、dry-run 和关闭开关。 |
| S2.8 | 对外运维化 | 中英文文档、诊断表、配置说明、长期慢测。 |

## 测试策略

| 层级 | 要求 |
| --- | --- |
| JUnit | `runMvStoreReclamationJUnitCheck` 覆盖 request/result 默认值、校验、scheduler 诊断、message 和 feature flag。 |
| MVStore 专项 | chunk bloat、page relocation、unknown map、long transaction、tail shrink、no-progress。 |
| 故障注入 | journal publish 前后、free/shrink 中断、relocation map 缺失。 |
| 并发 | 写入同时回收、长读事务、close/backup/compact 互斥。 |
| 兼容 | 旧库打开、新 feature flag、未完成 journal 恢复、只读降级。 |

每个阶段至少运行：

```powershell
.\gradlew.bat runMvStoreReclamationJUnitCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
```

涉及 `StorageMaintenance` 或插件能力时加跑：

```powershell
.\gradlew.bat runPluginArchitectureCheck
```

高风险阶段加跑 daily gate 或相关 `TestAll ci` phase。

## 非主线

| 能力 | 处理 |
| --- | --- |
| 整库 shadow publish | 保留为离线 compact 或兜底能力，不作为 S2 在线主线。 |
| SQL 命令 | S2 起点不做；等 Java maintenance API 和诊断稳定后再评审。 |
| 插件热加载/签名/权限沙箱 | 属于插件化后续阶段，不混入 S2。 |

## 当前要拍板的问题

| 问题 | 建议 |
| --- | --- |
| 是否允许 relocation map | 允许，但必须 feature flag，旧版本写打开要拒绝。 |
| 是否要求不打开所有 map 也能回收 | 作为 S2 终极目标；先从 open maps 开始，后续补按需打开/unknown map 诊断。 |
| 是否默认后台执行 | 是，稳定后默认低强度执行；可通过 `onlineReclamationEnabled(false)` 关闭。 |
| 长事务是否强制等待 | 否。默认跳过 pinned chunks；relocation map 成熟后再处理旧版本读取。 |

## 启动规则

S2 每个阶段完成后本地提交。新增生产代码必须同步补测试；可用 JUnit 的契约测试优先用 JUnit，MVStore 文件、故障注入和并发场景继续纳入专项 legacy test gate。对外文档需要同步英文副本。
