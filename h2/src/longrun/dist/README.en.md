# H2 LongRun Tests

This is the standalone distribution package for H2 long-running stress tests. It is intended for MVStore / SQL realistic access simulation, recovery validation, and S2 automatic space reclamation observation.

## Layout

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

## Extract And Quick Start

Use `tar.gz` on Linux / macOS:

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

If an unpacker does not preserve Unix permissions, run `chmod +x bin/h2-longrun` on Linux / macOS.

## Shell Completion

`h2-longrun` includes a Bash completion script:

```sh
source ./bin/h2-longrun-completion.bash
```

After sourcing it, completion will first suggest commands (`start`, `run`, `stop`, `status`, `restart`, `logs`, `watch`, `report`), and then use context rules for option values. For example:

```sh
./bin/h2-longrun watch --config <TAB>   # suggests config files
```

## Background Process

On Linux / macOS, `bin/h2-longrun` starts in the background by default. These commands manage the process:

```sh
./bin/h2-longrun start -c config/smoke.properties -d 5m
./bin/h2-longrun status
./bin/h2-longrun logs
./bin/h2-longrun stop
```

Use `watch` when you want foreground-style output while the longrun process keeps running in the background:

```sh
./bin/h2-longrun watch -c config/smoke.properties -d 5m
```

`watch` starts the selected instance if it is not already running, then follows the instance log and returns to the shell prompt when the test finishes. Pressing Ctrl-C stops only the log follower; the background process keeps running.

While following an instance log in a terminal, `logs` and `watch` render `PROGRESS ...` lines as one in-place progress line. Normal log lines automatically finish the current progress line before printing. `watch` automatically exits the log follower and returns to the shell prompt when the longrun process finishes; `logs` does the same when it follows a currently running instance. The log file itself still stores complete line-oriented `PROGRESS` records, so grep, metrics, and report analysis are not affected. Progress output is controlled by `progress.interval` in the config file and can be disabled with `progress.interval=0` or `-Dh2.longrun.progress.interval=0`.

When starting a new background process, the script rotates an existing instance log by default so the watched output belongs to the current run. Use `--append-log` to keep appending to the existing log, or `--truncate-log` to discard the old log instead of preserving a rotated copy.

You can also omit `start`:

```sh
./bin/h2-longrun -c config/smoke.properties -d 5m
```

Use foreground mode for debugging:

```sh
./bin/h2-longrun run -c config/smoke.properties -d 5m
```

Default process files are written under the unpacked tool directory:

```text
logs/longrun.pid
logs/longrun.out
work/smoke/
```

By default, the script reads `run.instance` from `--config` (or `-c`) and uses it to derive pid/log files. If no instance is configured, the default instance is `i1`. The Java application also locks `workDir/.longrun.lock`, so foreground runs and direct Java invocations cannot accidentally share the same work directory.

To run multiple profiles in parallel, use separate work directories and instance names:

```sh
H2_LONGRUN_INSTANCE=reopen ./bin/h2-longrun start -c config/reopen.properties -w work/reopen
H2_LONGRUN_INSTANCE=crash ./bin/h2-longrun start -c config/crash.properties -w work/crash
```

The instance name derives separate pid/log files such as `logs/longrun-reopen.pid` and `logs/longrun-reopen.out`. You can also set `H2_LONGRUN_INSTANCE`, `H2_LONGRUN_PID_FILE`, and `H2_LONGRUN_LOG_FILE` directly.

Crash profiles use a parent/worker process model. The instance log keeps parent events and worker `PROGRESS ...` lines so `logs` and `watch` remain readable. Detailed worker stdout/stderr is written under `work/<profile>/worker-logs/`, with one log file per cycle and phase.

The smoke config defaults to `work/smoke`, performance defaults to `work/performance`, nightly defaults to `work/nightly`, the comprehensive profile defaults to `work/comprehensive`, the reopen profile defaults to `work/reopen`, the crash profile defaults to `work/crash`, and the 30-day soak config defaults to `work/soak-30d`.

## Data Reliability Profiles

Short performance metrics collection:

```sh
./bin/h2-longrun run -c config/performance.properties
```

This profile defaults to 3 minutes, uses a shorter metrics interval, and disables crash, fault injection, and periodic reopen checks. It is intended for quick throughput, throughput-drop, size-amplification, and size-per-million-operations observations. Use `-d 1m`, `-d 5m`, or another duration to override the default.

The performance profile keeps online reclamation enabled and runs with a 10-second trigger interval, a 10-second internal minimum interval, and 250 ms per-round reclamation / tail-compaction budgets so short performance observations validate both throughput and the `5x` size-amplification target.

Normal-pressure comprehensive test:

```sh
./bin/h2-longrun start -c config/comprehensive.properties
```

This profile uses the regular bounded ledger and default keySpace without intentionally creating append-only bloat. It enables S2 reclamation, periodic reopen verification, and crash/recovery verification together, making it a good pre-release or nightly comprehensive stability test.

Reopen stability:

```sh
./bin/h2-longrun start -c config/reopen.properties
```

This profile periodically closes and reopens MVStore, then verifies consistency after every reopen. `Reopen Checks` in the report is the number of successful reopen verifications.

Crash/recovery stability:

```sh
./bin/h2-longrun start -c config/crash.properties
```

This profile uses a parent/worker process model. The parent forcibly kills the worker, then restarts the same work directory with `--resume=true`. The worker verifies committed data, checksums, counters, and ledger state before continuing the workload. `Recovery Checks` in the report is the number of successful crash recovery verifications. Worker details are available in `work/crash/worker-logs/`; the main instance log forwards only worker progress lines plus parent cycle events.

Copy-based file corruption injection:

```sh
./bin/h2-longrun start -c config/fault-injection.properties
```

This profile is intentionally separate from the normal comprehensive profile. It closes and verifies the primary MVStore, copies the `.mv.db` file into `work/fault-injection/fault/`, mutates the copy with configured byte-level faults, and then opens the copy read-only to classify the outcome. The primary workload file is reopened and verified after each injection.

Supported first-version fault kinds are `truncate`, `bit-flip`, `zero-range`, `random-range`, and `partial-page`. Report status counters distinguish `RECOVERED` copies, `DETECTED` corrupt copies, `DETECTED_BY_VERIFY` copies caught by business verification, and `UNEXPECTED_*` outcomes. If fault injection is enabled but no fault events are recorded, the report returns WARN so a too-short run is visible. `fault.retainedCopies` controls how many damaged copies are kept; metrics and reports still keep the full event history.

Live write-order, torn-write, and FilePath-level chaos injection are intentionally not enabled in this profile yet. They need a separate destructive switch because they can damage the active database instead of only a copied artifact.

## Run Report

A completed run generates a report automatically. Use `report` to re-analyze an existing run manually:

```sh
./bin/h2-longrun report -w work/smoke -l logs/longrun.out
```

Automatic and manual reports print the Markdown summary to stdout and write:

```text
work/smoke/report/summary.md
work/smoke/report/summary.properties
```

The status is `PASS`, `WARN`, or `FAIL`. The analyzer checks final run metadata, metrics samples, reclamation events, and suspicious log lines.

Default thresholds are intentionally moderate:

```text
max throughput drop ratio: 0.90
max final size: 64 GB
max size per million operations: 4 GB
max MVStore size amplification: 5x
max smoke final size for keySpace <= 10000: 5 GB
max reclamation backoff ratio: 0.60
minimum reclamation events for longer runs: 1
maximum suspicious log lines: 0
```

You can override them with `-t, --max-throughput-drop-ratio`, `-f, --max-final-size-gb`, `-p, --max-size-per-million-ops-gb`, `-a, --max-size-amplification`, `-e, --min-reclamation-events`, and `-x, --max-error-lines`. The performance profile uses the default `5x` size-amplification threshold too, so short performance runs do not hide obvious file growth.

The report also warns when reclamation runs but total `shrinkBytes` remains zero, or when successful reclamation rounds do not reduce the file size.
Regular throughput metric rows include a `phase` column. Throughput-drop warnings use only `RUNNING` samples, so expected `STARTUP` and crash-harness `RECOVERY` windows remain visible in the report without turning recovery-focused runs into WARN. Older metric files without this column are treated as `RUNNING`.
Throughput-drop analysis uses the 5th percentile throughput from the steady `RUNNING` window, so a single scheduler jitter sample does not turn an otherwise healthy run into WARN. Very short runs with fewer than 20 steady samples still use the minimum `RUNNING` sample.

## MVStore Ledger Mode

The default smoke config uses:

```properties
workload.ledgerMode=bounded
workload.ledgerMaxEntries=100000
```

`bounded` caps the internal operation ledger so the test tool's own audit log does not inflate smoke, nightly, or soak files to tens of GB. Nightly and the 30-day soak also default to bounded mode with larger caps. Copy a config and use `workload.ledgerMode=append-only` when the goal is to manufacture historical-version pressure for S2 reclamation.

Smoke and nightly trigger reclamation every 10 seconds by default with a high-write stress budget:

```properties
reclamation.maxCandidateChunks=64
reclamation.maxLiveBytesToRewriteMb=64
reclamation.maxRun=500
reclamation.maxTailCompaction=500
reclamation.minSchedulerInterval=0
```

These defaults are intended to keep steady-state size amplification under the `5x` acceptance line during high write pressure. To reduce background cleanup frequency or cleanup cost, copy a config file and tune these `reclamation.*` properties; the trigger interval can also be overridden at startup with `-Dh2.longrun.reclamation.interval=30s`.

## Candidate H2 Jar Validation

```sh
bin/h2-longrun start -c config/nightly.properties -j /path/to/h2.jar
```

The scripts put the `-j/--h2-jar` candidate jar at the front of the classpath. The application validates the jar on startup, prints its SHA-256 and manifest version, and records them in `final-report.properties`.

## Common Arguments

| Argument | Notes |
| --- | --- |
| `-c`, `--config` | Selects the config file. |
| `-w`, `--work-dir` | Overrides the output work directory. |
| `-d`, `--duration` | Overrides run duration, such as `5m`, `12h`, or `30d`. |
| `-m`, `--mode` | Selects workload mode, such as `mvstore` or `sql`. |
| `-j`, `--h2-jar` | Selects a candidate H2 jar. |
| `--rotate-log` | Rotates the old log before a background start. This is the default. |
| `--append-log` | Appends to the old log when starting in the background. |
| `--truncate-log` | Truncates the old log before a background start without preserving a rotated copy. |

Before long soaks, copy one of the `config/*.properties` files and adjust the copy instead of changing the baseline config.
