# MVStore 空间回收 S2 发布说明

本文记录 S2 在线空间回收进入正式版前的用户可见行为、默认策略、诊断方式和延后能力。S2 是 MVStore 内部的 chunk/page 级在线部分回收能力，不是整库 shadow copy，也不是简单包装 `compactFile()`。

## 默认策略

| 项 | 正式版策略 |
| --- | --- |
| 后台调度 | 默认低强度启用，复用 MVStore housekeeping。 |
| 预算控制 | 默认受最小运行间隔、失败退避、rewrite 字节预算和单轮运行时间预算限制。 |
| 关闭开关 | 可通过 `onlineReclamationEnabled(false)` 立即关闭后台在线回收。 |
| journal | 默认关闭，只在显式请求时启用。 |
| relocation map | 只在存在显式映射时参与读页解析；带相关 metadata 且 gate 禁用时拒绝打开。 |
| tail compaction | 只有显式设置时间预算时触发，默认不主动执行物理 tail move / truncate。 |

## 使用和回退

默认配置会在 MVStore housekeeping 中尝试低强度在线回收。它会先分析候选 chunk，再在预算内执行在线 partial relocation，并返回结构化诊断。若业务对延迟敏感、需要隔离问题或怀疑兼容风险，可在打开 MVStore 时设置：

```java
new MVStore.Builder()
        .fileName(fileName)
        .onlineReclamationEnabled(false)
        .open();
```

手动维护入口仍走同一套 coordinator。插件化维护路径可以通过 storage engine provider 暴露的 `StorageMaintenance.vacuumOnline()` 调用。

## 诊断码

| 诊断码 | 含义 | 建议处理 |
| --- | --- | --- |
| `RECLAMATION_ROUND_FINISHED` | 单轮在线回收完成。 | 观察 `estimatedReclaimedBytes`、fill rate 和文件大小变化。 |
| `RECLAMATION_PAUSED_BY_TIME_BUDGET` | 单轮达到运行时间预算后暂停。 | 保持默认退避；持续空间压力较大时再调高预算。 |
| `NO_RECLAMATION_CANDIDATE` | 当前没有可回收候选 chunk。 | 通常是正常跳过；如文件仍大，检查 retention、长事务或 relocation map 状态。 |
| `NO_OPEN_MAP_RELOCATION_PROGRESS` | 有候选但本轮没有完成 page relocation。 | 检查 map ownership、旧版本 pin 和写入竞争。 |
| `DRY_RUN` | 只执行分析，不写入。 | 用于诊断和发布前评估。 |
| `RECLAMATION_SCHEDULER_DISABLED` | scheduler 被配置关闭。 | 如需后台回收，启用 `onlineReclamationEnabled(true)` 或移除关闭配置。 |
| `RECLAMATION_SCHEDULER_BACKOFF` | scheduler 处于最小间隔或失败退避窗口。 | 正常节流，避免频繁抢占前台 IO。 |
| `RECLAMATION_STORE_CLOSED` | store 已关闭，后台 housekeeping 跳过本轮在线回收。 | 正常关闭竞态保护；若业务仍在调用维护入口，检查关闭顺序。 |
| `RECLAMATION_FAILED` | 本轮执行异常。 | 保留文件和 journal 状态，查看异常消息；下一轮会先尝试恢复 stale journal。 |

## 发布前门禁

正式发布前需要重新运行：

```powershell
.\gradlew.bat runMvStoreReclamationJUnitCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runH2LegacySmoke
.\gradlew.bat runH2TestAllCi
```

## 本版不阻塞发布的延后项

| 能力 | 状态 | 后续方向 |
| --- | --- | --- |
| 真正的 relocation map 读路径 | 已有 feature gate，默认 unused。 | 增加 old page position 到 new page position 的安全读路径和兼容拒写策略。 |
| 完整 crash-safe publish 语义 | journal scaffold 已存在。 | 补 publish marker、故障注入、replay/rollback 和旧版本打开保护。 |
| 更精确 unknown-map 诊断 | 当前 MVStore 可 lazy-open maps 参与 rewrite。 | ownership 无法解析时输出专用 skip reason。 |
| 真实负载后台调度观察 | scheduler 默认低强度启用。 | 持续观察限速、退避、全局互斥和前台写延迟。 |
| SQL 运维命令 | 当前不作为 S2 起点。 | Java maintenance API 和诊断稳定后再评审。 |
