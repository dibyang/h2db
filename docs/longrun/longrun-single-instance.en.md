# LongRun Single-Instance Startup Policy

LongRun protects users from accidentally starting duplicate runs. By default, the script reads `run.instance` from the selected config to derive pid/log files; if the config does not define an instance, it uses the default instance `i1`. One `workDir` can also be owned by only one Java process.

## Default Behavior

| Layer | Behavior |
| --- | --- |
| Linux script pid file | `bin/h2-longrun start` reads `run.instance` from `--config` and does not start a second background process when that pid is alive. |
| Java workDir lock | Every run attempts to lock `workDir/.longrun.lock`; startup fails when the lock is already held. |
| report command | Analyzes existing data only and does not hold the run lock. |

This prevents two processes from writing the same MVStore, metrics, report, or log output.

Use `watch` when a run should behave like a foreground command for observation but keep executing in the background:

```sh
./bin/h2-longrun watch --config config/crash.properties
```

If the instance is not running, `watch` starts it in the background and follows the selected instance log. If it is already running, `watch` only follows the log. Ctrl-C stops the log follower, not the background longrun process.

For a new background start, the Linux script rotates an existing instance log by default. Use `--append-log` to keep appending, `--truncate-log` to discard the old log, or `H2_LONGRUN_LOG_POLICY=rotate|append|truncate` to set the default policy from the environment. Existing running instances are never rotated by `watch` or `logs`.

## Parallel Runs

Built-in profiles already define instance names such as `crash`, `reopen`, and `smoke`. Use the same config for start and stop to resolve the matching pid/log files:

```sh
./bin/h2-longrun start --config config/crash.properties
./bin/h2-longrun status --config config/crash.properties
./bin/h2-longrun stop --config config/crash.properties
```

Parallel runs must be explicitly isolated:

```sh
H2_LONGRUN_INSTANCE=reopen \
./bin/h2-longrun start --config config/reopen.properties --work-dir work/reopen
```

```sh
H2_LONGRUN_INSTANCE=crash \
./bin/h2-longrun start --config config/crash.properties --work-dir work/crash
```

`run.instance` or `H2_LONGRUN_INSTANCE` makes the Linux script derive independent pid/log files:

```text
logs/longrun-reopen.pid
logs/longrun-reopen.out
logs/longrun-crash.pid
logs/longrun-crash.out
```

For full control, specify files directly:

```sh
H2_LONGRUN_PID_FILE=logs/custom.pid \
H2_LONGRUN_LOG_FILE=logs/custom.out \
./bin/h2-longrun start --config config/smoke.properties --work-dir work/custom
```

## Constraints

| Constraint | Reason |
| --- | --- |
| Do not reuse the same `--work-dir` | The Java lock rejects it to prevent data and report contamination. |
| Do not reuse the same pid/log files | Script state and logs become ambiguous. |
| Isolate fault/crash profiles carefully | These profiles kill processes or retain damaged copies, so mixed runs are hard to diagnose. |

## Crash Logs

The crash profile uses a parent/worker process model, but it still writes one instance log by default so events remain in one timeline:

```text
logs/longrun-crash.out
```

Log lines include role and pid prefixes:

```text
[parent pid=3545744] H2 LongRun Test App role=parent
[parent pid=3545744] Crash harness started worker cycle=1 resume=false
[worker pid=3545801] H2 LongRun Test App role=worker
[parent pid=3545744] Crash harness killed worker cycle=1
```

## Config Field

```properties
run.instance=crash
```

The instance name affects only script-level pid/log defaults and report metadata. It does not change `run.workDir`. Parallel runs still need different `--work-dir` values or different `run.workDir` values in their configs.
