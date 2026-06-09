# LongRun Profile 默认时间

本文记录 `h2/src/longrun/resources/*.properties` 的默认运行时间和周期语义。

## 默认值

| Profile | run.duration | reopen interval | crash interval | crash cycles | 适用场景 |
| --- | --- | --- | --- | --- | --- |
| `smoke.properties` | 5m | 120s | disabled | 1 | 快速确认工具和 S2 基础链路。 |
| `performance.properties` | 3m | disabled | disabled | 1 | 短时间采集吞吐和空间指标，关闭 crash/fault/reopen 干扰。 |
| `reopen.properties` | 1h | 2m | disabled | 1 | 专项 close/open 稳定性，默认约 30 次 reopen。 |
| `crash.properties` | 30m | 2m | 60s | 15 | 专项 crash/recovery，默认约 15 轮强杀和恢复。 |
| `nightly.properties` | 12h | 30m | disabled | 1 | 夜间常压长跑。 |
| `comprehensive.properties` | 12h | 10m | 30m | 12 | 综合常压、reopen、crash/recovery 和 S2 验收。 |
| `fault-injection.properties` | 30m | 5m | disabled | 1 | copy-based 文件损坏注入专项。 |
| `soak-30d.properties` | 30d | 2h | 120m | 180 | 专机 30 天 soak。 |

## crash harness 语义

当前 `crash.enabled=true` 时，父进程按 `crash.cycles` 循环：

1. 启动 worker。
2. 等待 `crash.interval`。
3. 强杀 worker。
4. 用 `--resume=true` 启动恢复 worker，并让它运行约 `crash.interval`。

因此 crash profile 的实际总时长近似为：

```text
crash.interval * 2 * crash.cycles
```

`run.duration` 仍会写入报告，并作为 worker 目标运行时长传入，但在 crash harness 下不是唯一的总时长控制项。配置里的 `crash.cycles` 需要和目标运行时间保持自洽。

## 调整原则

| 场景 | 建议 |
| --- | --- |
| 日常本地快速确认 | 使用 smoke，必要时 `--duration 1m`。 |
| 快速性能指标观察 | 使用 performance，默认 3m；对比候选 jar 时指定相同 seed、时长和磁盘环境，并按 10s 回收节奏校验吞吐和 5x 空间放大目标。 |
| 验证 reopen bug | 使用 reopen，默认 1h；只想快速复现可 `--duration 10m`。 |
| 验证 crash-safe publish/free | 使用 crash 或 comprehensive。 |
| 发布前夜间验收 | 使用 comprehensive 或 nightly。 |
| 长期空间整理稳定性 | 使用 soak-30d，并放在专机或受控磁盘目录下；默认使用 bounded ledger 和 10s 回收节奏，避免测试账本无限增长。 |
