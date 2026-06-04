# H2 LongRun Tests

这是 H2 长稳压测独立应用的发布包，用于 MVStore / SQL 长时间访问模拟、故障恢复验证和 S2 自动空间整理观察。

## 目录

```text
bin/
  h2-longrun
  h2-longrun.bat
  h2-longrun-completion.bash
config/
  smoke.properties
  performance.properties
  nightly.properties
  comprehensive.properties
  fault-injection.properties
  reopen.properties
  crash.properties
  soak-30d.properties
lib/
  h2-longrun.jar
```

## 解压和快速运行

Linux / macOS 推荐使用 `tar.gz`：

```sh
tar -xzf h2-longrun-<version>.tar.gz -C /opt
cd /opt/h2-longrun
./bin/h2-longrun start -c config/smoke.properties -d 5m
```

Windows:

```powershell
Expand-Archive -Force h2-longrun-<version>.zip C:\
cd C:\h2-longrun
bin\h2-longrun.bat -c config\smoke.properties -d 5m
```

如果使用不保留 Unix 权限的解压工具，Linux / macOS 上可以执行 chmod +x bin/h2-longrun。

## Shell Completion

`h2-longrun` 提供 Bash 补全脚本：

```sh
source ./bin/h2-longrun-completion.bash
```

启用后会先补全命令（`start`、`run`、`stop`、`status`、`restart`、`logs`、`watch`、`report`），再根据参数上下文补全取值。例如：

```sh
./bin/h2-longrun watch --config <TAB>
```
## 后台进程

在 Linux / macOS 上，`bin/h2-longrun` 默认后台启动。常用管理命令如下：

```sh
./bin/h2-longrun start -c config/smoke.properties -d 5m
./bin/h2-longrun status
./bin/h2-longrun logs
./bin/h2-longrun stop
```

需要“看起来像前台运行、实际在后台继续跑”时，可以使用 `watch`：

```sh
./bin/h2-longrun watch -c config/smoke.properties -d 5m
```

在终端里跟随实例日志时，`logs` 和 `watch` 会把 `PROGRESS ...` 行显示成原地刷新的单行进度。遇到普通日志行时会自动结束当前进度行，再正常换行输出。`watch` 会在本次 longrun 进程结束后自动退出日志跟随并返回控制台提示符；`logs` 跟随正在运行的实例时也会在该实例结束后自动退出。日志文件本身仍然保存完整的逐行 `PROGRESS` 记录，不影响 grep、metrics 和 report 分析。进度输出由配置文件中的 `progress.interval` 控制，也可以用 `progress.interval=0` 或 `-Dh2.longrun.progress.interval=0` 关闭。

`watch` 会在实例未运行时先后台启动，再跟随该实例日志，并在测试结束后自动返回控制台提示符。按 Ctrl-C 只会退出日志跟随，后台 longrun 进程会继续运行。

启动新的后台进程时，脚本默认会轮转已有实例日志，让当前日志只包含本次运行输出。需要继续追加旧日志时使用 `--append-log`，需要直接清空旧日志且不保留轮转副本时使用 `--truncate-log`。

也可以省略 `start`：

```sh
./bin/h2-longrun -c config/smoke.properties -d 5m
```

调试时可以使用前台模式：

```sh
./bin/h2-longrun run -c config/smoke.properties -d 5m
```

默认进程文件写在解压后的工具目录下：

```text
logs/longrun.pid
logs/longrun.out
work/smoke/
```

默认情况下，脚本会从 `--config`（或 `-c`）读取 `run.instance` 并据此派生 pid/log 文件；配置中没有实例名时使用 `i1`。Java 应用还会锁定 `workDir/.longrun.lock`，避免前台运行或直接 Java 调用误用同一个工作目录。

并行运行多个 profile 时，请使用不同的工作目录和实例名：

```sh
H2_LONGRUN_INSTANCE=reopen ./bin/h2-longrun start -c config/reopen.properties -w work/reopen
H2_LONGRUN_INSTANCE=crash ./bin/h2-longrun start -c config/crash.properties -w work/crash
```

实例名会派生独立的 pid/log 文件，例如 `logs/longrun-reopen.pid` 和 `logs/longrun-reopen.out`。也可以直接设置 `H2_LONGRUN_INSTANCE`、`H2_LONGRUN_PID_FILE` 和 `H2_LONGRUN_LOG_FILE`。

crash profile 使用父子进程模式。实例主日志只保留父进程事件和 worker 的 `PROGRESS ...` 行，方便 `logs` 和 `watch` 保持可读进度；worker 详细 stdout/stderr 会写入 `work/<profile>/worker-logs/`，按 cycle 和阶段拆分成独立日志。

smoke 配置默认使用 `work/smoke`，performance 默认使用 `work/performance`，nightly 默认使用 `work/nightly`，comprehensive profile 默认使用 `work/comprehensive`，reopen profile 默认使用 `work/reopen`，crash profile 默认使用 `work/crash`，30 天 soak 默认使用 `work/soak-30d`。

## 数据可靠性 Profile

短性能指标采集：

```sh
./bin/h2-longrun run -c config/performance.properties
```

该 profile 默认运行 3 分钟，使用较短的 metrics 采样间隔，关闭 crash、fault injection 和周期 reopen，主要用于快速观察吞吐、吞吐跌幅、空间放大和每百万操作空间占用。需要更短或更长时可以加 `-d 1m`、`-d 5m` 等覆盖运行时长。

performance profile 保留在线空间回收，并按 10 秒触发、10 秒内部最小间隔、250ms 单轮回收/尾部整理预算运行，用于在短性能观察里同时校验吞吐和 `5x` 空间放大目标。

常压综合测试：

```sh
./bin/h2-longrun start -c config/comprehensive.properties
```

该 profile 使用常规 bounded ledger 和默认 keySpace，不刻意制造 append-only 膨胀；同时启用 S2 reclamation、周期 reopen 校验和 crash/recovery 校验，适合作为发布前或夜间综合稳定性测试。

reopen 稳定性：

```sh
./bin/h2-longrun start -c config/reopen.properties
```

该 profile 周期性 close/open MVStore，并在每次 reopen 后执行一致性校验。报告中的 `Reopen Checks` 表示成功完成的 reopen 校验次数。

crash/recovery 稳定性：

```sh
./bin/h2-longrun start -c config/crash.properties
```

该 profile 使用父子进程模式，父进程周期性强杀 worker，再用 `--resume=true` 重启同一个工作目录。worker 恢复后会先打开库并校验已提交数据、checksum、counter 和 ledger，再继续 workload。报告中的 `Recovery Checks` 表示成功完成的 crash recovery 校验次数。worker 细节日志保存在 `work/crash/worker-logs/`；实例主日志只转发 worker 进度行和父进程 cycle 事件。

copy-based 文件损坏注入：

```sh
./bin/h2-longrun start -c config/fault-injection.properties
```

该 profile 与常规 comprehensive profile 隔离。它会关闭并校验主 MVStore，将 `.mv.db` 文件复制到 `work/fault-injection/fault/`，对副本执行配置的字节级损坏，再以只读方式打开副本并分类结果。每次注入后都会重新打开并校验主 workload 文件。

首版支持的 fault kind 包括 `truncate`、`bit-flip`、`zero-range`、`random-range` 和 `partial-page`。报告状态计数会区分 `RECOVERED`、`DETECTED`、`DETECTED_BY_VERIFY` 和 `UNEXPECTED_*`。当启用 fault injection 但没有记录任何 fault 事件时，报告返回 WARN，避免过短运行或调度关闭时误以为已完成损坏注入测试。`fault.retainedCopies` 控制保留的损坏副本数量，metrics 和 report 仍会保留全部事件历史。

实时写入顺序、torn-write 和 FilePath 级 chaos 注入尚未在该 profile 中启用。这类测试可能损坏活动数据库，需要单独的破坏性开关、更强的保护和专门报告。

## 运行报告

长测结束后生成报告：

```sh
./bin/h2-longrun report -w work/smoke -l logs/longrun.out
```

测试正常结束后会自动生成报告。上面的 `report` 命令用于对已有数据手动重新分析。

自动报告和手动 `report` 都会把 Markdown summary 打印到 stdout，并写入：

```text
work/smoke/report/summary.md
work/smoke/report/summary.properties
```

状态分为 `PASS`、`WARN`、`FAIL`。分析器会检查最终运行元数据、metrics 样本、reclamation 事件和可疑日志行。

默认阈值保持适中：

```text
最大吞吐跌幅比例：0.90
最大最终文件大小：64 GB
每百万操作最大空间占用：4 GB
最大 MVStore 空间放大率：5x
keySpace <= 10000 的 smoke 最大最终文件大小：5 GB
最大 reclamation backoff 比例：0.60
较长运行最少 reclamation 事件：1
最大可疑日志行数：0
```

可以用 `-t, --max-throughput-drop-ratio`、`-f, --max-final-size-gb`、`-p, --max-size-per-million-ops-gb`、`-a, --max-size-amplification`、`-e, --min-reclamation-events` 和 `-x, --max-error-lines` 覆盖。performance profile 同样使用默认 `5x` 空间放大阈值，避免短性能采样放过明显文件膨胀。

如果 reclamation 执行过但总 `shrinkBytes` 仍为 0，或者 SUCCESS 轮次没有降低文件大小，报告也会给出 WARN。
常规吞吐 metrics 行包含 `phase` 列。吞吐跌幅告警只使用 `RUNNING` 样本，因此预期内的 `STARTUP` 和 crash harness `RECOVERY` 窗口仍会显示在报告中，但不会把恢复类测试误判为 WARN。旧 metrics 文件没有该列时会按 `RUNNING` 处理。
吞吐跌幅判断使用稳定 `RUNNING` 窗口的 5% 分位吞吐，避免单个调度抖动采样把整体报告误判为 WARN；少于 20 个稳定样本的极短运行仍使用最低 `RUNNING` 采样值。

## MVStore ledger 模式

默认 smoke 配置使用：

```properties
workload.ledgerMode=bounded
workload.ledgerMaxEntries=100000
```

`bounded` 会限制内部操作账本大小，避免测试工具自己的无限追加日志把 smoke 文件放大到几十 GB。nightly 同样默认使用 bounded，但上限更大。需要专门制造历史版本和 S2 回收压力时，可以改成 `workload.ledgerMode=append-only`。

默认 smoke / nightly 每 10 秒触发一次 reclamation，并使用高写入压测预算：

```properties
reclamation.maxCandidateChunks=64
reclamation.maxLiveBytesToRewriteMb=64
reclamation.maxRun=500
reclamation.maxTailCompaction=500
reclamation.minSchedulerInterval=0
```

这些默认值用于把高写入压力下的稳定空间放大率压到 `5x` 验收线以内。需要降低后台整理频率或压低整理开销时，可以复制配置文件后调整这些 `reclamation.*` 参数；也可以启动时使用系统属性 `-Dh2.longrun.reclamation.interval=30s` 覆盖触发间隔。

## 验证候选 H2 jar

```sh
bin/h2-longrun start --config config/nightly.properties -j /path/to/h2.jar
```

脚本会把 `--h2-jar` 指定的 jar 放到 classpath 前面；应用启动时会校验 jar，输出 SHA-256 和 manifest 版本，并写入 `final-report.properties`。

## 常用参数

| 参数 | 说明 |
| --- | --- |
| `-c`, `--config` | 指定配置文件。 |
| `-w`, `--work-dir` | 覆盖运行输出目录。 |
| `-d`, `--duration` | 覆盖运行时长，例如 `5m`、`12h`、`30d`。 |
| `-m`, `--mode` | 指定 workload，支持 `mvstore`、`sql`。 |
| `-j`, `--h2-jar` | 指定候选 H2 jar。 |
| `--rotate-log` | 后台启动前轮转旧日志，默认行为。 |
| `--append-log` | 后台启动时追加到旧日志。 |
| `--truncate-log` | 后台启动前清空旧日志，不保留轮转副本。 |

长时间运行前建议复制一份 `config/*.properties` 后再调整，避免直接修改基线配置。
