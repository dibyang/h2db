# LongRun 发布前验收记录

[English](longrun-release-acceptance.en.md)

本文记录 LongRun 独立发布包在正式发布前的人工验收结果。验收目标是确认发布包脚本、报告输出、MVStore S2 空间回收、reopen、crash/recovery 和 copy-based fault injection 的关键路径可用。

## 已通过验收

| Profile | 命令 | 结果 | 关键指标 |
| --- | --- | --- | --- |
| smoke | `./bin/h2-longrun watch --duration 10m --config config/smoke.properties` | PASS | 约 1409 万操作，4 次 reopen，60 次 reclamation success，0 warnings，0 suspicious log lines。 |
| crash/recovery | `./bin/h2-longrun watch --config config/crash.properties` | PASS | 15 个 crash cycle，29 次 recovery check，约 3614 万操作，165 次 reclamation success，0 warnings，0 suspicious log lines。 |
| fault-injection | `./bin/h2-longrun watch --config config/fault-injection.properties` | PASS | 14 次 fault injection，11 次 recovered，3 次 detected / detected by verify，0 unexpected，0 warnings，0 suspicious log lines。 |

## 已确认行为

- `watch` 能启动后台 longrun 进程并直接跟随实例日志。
- 正常结束后，报告会直接打印到终端，同时写入 `report/summary.md` 和 `report/summary.properties`。
- crash/recovery 报告会记录 `STARTUP`、`RUNNING`、`RECOVERY` phase；吞吐告警只使用 `RUNNING` 样本。
- fault-injection profile 能覆盖 `TRUNCATE`、`BIT_FLIP`、`ZERO_RANGE`、`RANDOM_RANGE` 和 `PARTIAL_PAGE`，并区分 `RECOVERED`、`DETECTED` 和 `DETECTED_BY_VERIFY`。
- S2 reclamation 在上述 profile 中均有成功事件，且没有 backoff-heavy 或 ineffective success WARN。

## 发布前仍建议补跑

| Profile | 建议 |
| --- | --- |
| nightly | 发布前至少运行一次缩短版，例如 `./bin/h2-longrun watch --duration 2h --config config/nightly.properties`。 |
| comprehensive | 如发布窗口允许，运行一次综合 profile 的缩短版，覆盖常压、reopen、crash/recovery 与 S2 的组合路径。 |
| dist tar/zip | 从干净目录解压 `h2-longrun.zip` 和 `h2-longrun.tar.gz`，确认脚本权限、配置文件、README 和 jar 布局正确。 |

## 判定标准

正式发布前，已跑 profile 应满足：

- `Status: PASS`。
- `Failures: None`。
- `Warnings: None`，或已在 release notes 中解释。
- `Suspicious Log Lines: 0`。
- `MVStore Size Amplification` 低于默认阈值 `5.0`。
- crash profile 没有 recovery failure。
- fault-injection profile 没有 `UNEXPECTED_*` 事件。
