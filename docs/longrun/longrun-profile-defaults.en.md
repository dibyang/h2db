# LongRun Profile Default Timings

This document records the default timing semantics for `h2/src/longrun/resources/longrun-*.properties`.

## Defaults

| Profile | run.duration | reopen interval | crash interval | crash cycles | Use case |
| --- | --- | --- | --- | --- | --- |
| `longrun-smoke.properties` | 5m | 120s | disabled | 1 | Quick tool and S2 baseline check. |
| `longrun-reopen.properties` | 1h | 2m | disabled | 1 | Dedicated close/open stability, about 30 reopen checks by default. |
| `longrun-crash.properties` | 30m | 2m | 60s | 15 | Dedicated crash/recovery, about 15 kill-and-recover rounds by default. |
| `longrun-nightly.properties` | 12h | 30m | disabled | 1 | Normal overnight pressure. |
| `longrun-comprehensive.properties` | 12h | 10m | 30m | 12 | Combined pressure, reopen, crash/recovery, and S2 acceptance. |
| `longrun-fault-injection.properties` | 30m | 5m | disabled | 1 | Dedicated copy-based file corruption injection. |
| `longrun-soak-30d.properties` | 30d | 2h | 120m | 180 | Dedicated-host 30-day soak. |

## Crash Harness Semantics

When `crash.enabled=true`, the parent process loops over `crash.cycles`:

1. Start a worker.
2. Wait `crash.interval`.
3. Kill the worker forcibly.
4. Start a recovery worker with `--resume=true` and let it run for about `crash.interval`.

The effective crash-harness duration is therefore approximately:

```text
crash.interval * 2 * crash.cycles
```

`run.duration` is still reported and passed as the worker target duration, but under the crash harness it is not the only total-duration control. `crash.cycles` needs to remain consistent with the intended profile duration.

## Tuning Rules

| Scenario | Recommendation |
| --- | --- |
| Quick local confirmation | Use smoke, optionally with `--duration 1m`. |
| Reopen bug validation | Use reopen, default 1h; use `--duration 10m` for quick reproduction. |
| Crash-safe publish/free validation | Use crash or comprehensive. |
| Pre-release overnight acceptance | Use comprehensive or nightly. |
| Long-term space organization stability | Use soak-30d on a dedicated host or controlled disk directory. |
