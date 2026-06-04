# LongRun 文件损坏注入设计

本文记录 longrun 独立应用的真实文件损坏注入方案。该能力用于验证 MVStore 在随机截断、bit flip、局部字节破坏和部分页破坏后的恢复、检测和数据一致性表现。

## 目标

| 目标 | 说明 |
| --- | --- |
| 默认隔离 | 不并入普通 smoke / comprehensive profile，必须显式使用故障注入 profile。 |
| 不破坏主库 | 首版只破坏复制出来的 `.mv.db` 副本，主 workload 文件每轮注入后必须重新打开并校验。 |
| 持续运行 | 可以跟常规读写、S2 reclamation、reopen 一起长时间运行。 |
| 可判定 | 报告区分 recovered、detected 和 unexpected，不只看进程是否退出。 |
| 可复现 | 每条 fault metrics 记录事件 id、类型、offset、length、前后文件大小和副本路径。 |

## 首版范围

首版采用 copy-based 模式：

1. workload 先 `commit()` 并执行一致性 `verify()`。
2. 关闭主 MVStore，确保复制点清晰。
3. 把 `mvstore-longrun.mv.db` 复制到 `work/.../fault/fault-N.mv.db`。
4. 对副本执行配置的字节级损坏。
5. 用只读方式打开副本，能打开则继续校验 checksum / counter / ledger。
6. 重新打开并校验主库。

首版支持的损坏类型：

| 类型 | 行为 |
| --- | --- |
| `truncate` | 随机截断文件尾部，最大不超过 `fault.maxBytes`。 |
| `bit-flip` | 随机翻转 1 个 bit。 |
| `zero-range` | 随机把一段字节置 0。 |
| `random-range` | 随机把一段字节改成随机内容。 |
| `partial-page` | 在 4 KB MVStore block 内破坏一段内容。 |

## 结果分类

| 状态 | 含义 | 判定 |
| --- | --- | --- |
| `RECOVERED` | 损坏副本可只读打开，并通过业务一致性校验。 | 通过 |
| `DETECTED` | MVStore 正确拒绝损坏副本。 | 通过 |
| `DETECTED_BY_VERIFY` | 副本可打开，但业务 checksum / counter / ledger 校验发现损坏。 | 通过并计数 |
| `UNEXPECTED_*` | 注入、复制、打开或分类流程出现未归类的非预期结果。 | 失败 |

## 配置

专项 profile：

```properties
run.name=mvstore-fault-injection
run.workDir=work/fault-injection
fault.enabled=true
fault.interval=2m
fault.kinds=truncate,bit-flip,zero-range,random-range,partial-page
fault.maxBytes=4096
fault.retainedCopies=5
```

运行方式：

```sh
./bin/h2-longrun start --config config/fault-injection.properties
```

本地 Gradle 快速验证：

```sh
./gradlew runLongRunFaultInjection -PlongRunDuration=10s -PlongRunFaultInterval=1s
```

## 报告

metrics 中新增 `fault,...` 行，报告新增：

| 指标 | 说明 |
| --- | --- |
| `Fault Injection Events` | 注入总次数。 |
| `Fault Injection Recovered Events` | 损坏副本可恢复且通过校验的次数。 |
| `Fault Injection Detected Events` | 损坏副本被 MVStore 正确拒绝的次数。 |
| `Fault Injection Unexpected Events` | 非预期结果次数，该值大于 0 时报告失败。 |
| `Fault Injection Status Counts` | 按结果状态聚合。 |
| `Fault Injection Kind Counts` | 按损坏类型聚合。 |
| `Recent Fault Injection Events` | 最近的注入事件摘要。 |

当 `fault.enabled=true` 但没有记录任何 `fault,...` metrics 行时，报告返回 WARN。这样可以发现运行时长短于 `fault.interval`，或注入调度被意外关闭的情况。

`fault.retainedCopies` 控制 `work/.../fault/` 下保留的损坏副本数量，默认只保留最近若干个副本，避免 30 分钟或更长 profile 因副本累计超过 `limits.maxDbSizeGb` 而中止。metrics 和 report 仍会保留全部 fault 事件历史。

## 后续阶段

以下能力暂不并入首版，需要单独 destructive profile：

| 能力 | 原因 |
| --- | --- |
| 活跃写入乱序 | 需要 FilePath 或底层写入 hook，会直接影响主库写入路径。 |
| torn write / 部分页写 | 可能破坏正在运行的文件，必须配合 crash harness 和恢复边界。 |
| live bit flip | 可能制造不可恢复主库，需要更强 guardrail 和现场保留策略。 |
| 操作系统级 fsync / reorder 模拟 | 和平台、文件系统语义强相关，需要专门测试入口。 |
