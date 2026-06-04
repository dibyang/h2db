# LongRun 单实例启动策略

LongRun 默认保护用户不要重复启动同一套测试。默认情况下，脚本会使用配置里的 `run.instance` 派生 pid/log 文件；如果配置没有指定实例名，则使用默认实例 `i1`。同一个 `workDir` 也只能被一个 Java 进程持有。

## 默认行为

| 层级 | 行为 |
| --- | --- |
| Linux 脚本 pid 文件 | `bin/h2-longrun start` 从 `--config` 读取 `run.instance`，发现对应 pid 存活时，不再启动第二个后台进程。 |
| Java workDir 锁 | 任何启动方式都会尝试锁定 `workDir/.longrun.lock`；锁被占用时启动失败。 |
| report 命令 | 只分析已有数据，不持有运行锁。 |

这样可以避免两个进程同时写同一个 MVStore、metrics、report 或日志。

需要“像前台运行一样观察、但进程实际留在后台”时，使用 `watch`：

```sh
./bin/h2-longrun watch --config config/longrun-crash.properties
```

实例未运行时，`watch` 会先后台启动再跟随该实例日志；实例已运行时，`watch` 只跟随日志。按 Ctrl-C 只会停止日志跟随，不会停止后台 longrun 进程。

新的后台启动默认轮转已有实例日志。使用 `--append-log` 可以继续追加，使用 `--truncate-log` 可以丢弃旧日志，也可以通过 `H2_LONGRUN_LOG_POLICY=rotate|append|truncate` 设置环境默认策略。实例已经运行时，`watch` 或 `logs` 都不会轮转日志。

## 需要并行时

内置 profile 已经带有实例名，例如 `crash`、`reopen`、`smoke`。启动和停止时使用同一份配置即可定位对应 pid/log：

```sh
./bin/h2-longrun start --config config/longrun-crash.properties
./bin/h2-longrun status --config config/longrun-crash.properties
./bin/h2-longrun stop --config config/longrun-crash.properties
```

并行运行必须显式隔离：

```sh
H2_LONGRUN_INSTANCE=reopen \
./bin/h2-longrun start --config config/longrun-reopen.properties --work-dir work/reopen
```

```sh
H2_LONGRUN_INSTANCE=crash \
./bin/h2-longrun start --config config/longrun-crash.properties --work-dir work/crash
```

配置里的 `run.instance` 或 `H2_LONGRUN_INSTANCE` 会让 Linux 脚本自动使用独立 pid/log 文件：

```text
logs/longrun-reopen.pid
logs/longrun-reopen.out
logs/longrun-crash.pid
logs/longrun-crash.out
```

如果需要更精确控制，也可以直接指定：

```sh
H2_LONGRUN_PID_FILE=logs/custom.pid \
H2_LONGRUN_LOG_FILE=logs/custom.out \
./bin/h2-longrun start --config config/longrun-smoke.properties --work-dir work/custom
```

## 约束

| 约束 | 原因 |
| --- | --- |
| 不要复用同一个 `--work-dir` | Java 层会拒绝，防止数据和报告污染。 |
| 不要复用同一个 pid/log 文件 | 脚本层状态和日志会混乱。 |
| fault/crash profile 更应隔离 | 这些 profile 会强杀进程或保留损坏副本，混跑很难排查。 |

## crash 日志

crash profile 使用父子进程模式，但默认仍写同一个实例日志文件，便于按时间线排查：

```text
logs/longrun-crash.out
```

日志行会带角色和 pid 前缀：

```text
[parent pid=3545744] H2 LongRun Test App role=parent
[parent pid=3545744] Crash harness started worker cycle=1 resume=false
[worker pid=3545801] H2 LongRun Test App role=worker
[parent pid=3545744] Crash harness killed worker cycle=1
```

## 配置字段

```properties
run.instance=crash
```

实例名只影响脚本层默认 pid/log 文件命名和报告记录，不改变 `run.workDir`。并行运行时仍然必须使用不同的 `--work-dir` 或不同配置里的 `run.workDir`。
