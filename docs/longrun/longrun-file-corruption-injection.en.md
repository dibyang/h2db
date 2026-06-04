# LongRun File Corruption Injection Design

This document records the real file corruption injection design for the standalone longrun application. The capability validates MVStore recovery, corruption detection, and data consistency after random truncation, bit flips, byte-range damage, and partial-page damage.

## Goals

| Goal | Notes |
| --- | --- |
| Isolated by default | Do not include this in regular smoke or comprehensive profiles; require an explicit fault profile. |
| Do not damage the primary store | The first version mutates copied `.mv.db` artifacts only. The primary workload file is reopened and verified after each injection. |
| Long-running capable | Can run alongside regular read/write pressure, S2 reclamation, and reopen checks. |
| Judged outcomes | Reports distinguish recovered, detected, and unexpected outcomes instead of only checking process exit. |
| Reproducible | Every fault metric records event id, kind, offset, length, file sizes, and copy path. |

## First-Version Scope

The first version uses copy-based injection:

1. The workload runs `commit()` and consistency `verify()`.
2. The primary MVStore is closed so the copy point is clear.
3. `mvstore-longrun.mv.db` is copied to `work/.../fault/fault-N.mv.db`.
4. The copied file is mutated with the configured byte-level fault.
5. The copy is opened read-only; if it opens, checksum / counter / ledger verification is executed.
6. The primary store is reopened and verified.

Supported fault kinds:

| Kind | Behavior |
| --- | --- |
| `truncate` | Randomly truncates the file tail by up to `fault.maxBytes`. |
| `bit-flip` | Flips one random bit. |
| `zero-range` | Overwrites a random byte range with zeroes. |
| `random-range` | Overwrites a random byte range with random bytes. |
| `partial-page` | Damages a range inside a 4 KB MVStore block. |

## Outcome Classification

| Status | Meaning | Judgement |
| --- | --- | --- |
| `RECOVERED` | The damaged copy opened read-only and passed business consistency verification. | Pass |
| `DETECTED` | MVStore correctly rejected the damaged copy. | Pass |
| `DETECTED_BY_VERIFY` | The copy opened, but business checksum / counter / ledger verification detected damage. | Pass and count |
| `UNEXPECTED_*` | Injection, copying, opening, or classification produced an unclassified unexpected result. | Fail |

## Configuration

Dedicated profile:

```properties
run.name=mvstore-fault-injection
run.workDir=work/fault-injection
fault.enabled=true
fault.interval=2m
fault.kinds=truncate,bit-flip,zero-range,random-range,partial-page
fault.maxBytes=4096
fault.retainedCopies=5
```

Run command:

```sh
./bin/h2-longrun start --config config/fault-injection.properties
```

Local Gradle validation:

```sh
./gradlew runLongRunFaultInjection -PlongRunDuration=10s -PlongRunFaultInterval=1s
```

## Report

Metrics add `fault,...` rows. The report adds:

| Metric | Notes |
| --- | --- |
| `Fault Injection Events` | Total injection events. |
| `Fault Injection Recovered Events` | Damaged copies that recovered and verified successfully. |
| `Fault Injection Detected Events` | Damaged copies correctly rejected by MVStore. |
| `Fault Injection Unexpected Events` | Unexpected outcomes; greater than zero fails the report. |
| `Fault Injection Status Counts` | Aggregation by result status. |
| `Fault Injection Kind Counts` | Aggregation by fault kind. |
| `Recent Fault Injection Events` | Recent event summaries. |

When `fault.enabled=true` but no `fault,...` metric rows are recorded, the report returns WARN. This catches profiles whose duration is shorter than `fault.interval` or runs where injection scheduling is accidentally disabled.

`fault.retainedCopies` controls how many damaged copies are retained under `work/.../fault/`. The profile keeps only recent copies by default so a 30-minute or longer run does not stop after copied artifacts exceed `limits.maxDbSizeGb`. Metrics and reports still keep the full fault-event history.

## Later Phases

These capabilities are intentionally outside the first version and need a separate destructive profile:

| Capability | Reason |
| --- | --- |
| Live write reordering | Requires FilePath or low-level write hooks and affects the active write path. |
| Torn writes / partial page writes | Can damage the running file and must be paired with crash harness and recovery boundaries. |
| Live bit flips | Can create an unrecoverable primary store and needs stronger guardrails and artifact-retention policy. |
| OS-level fsync / reorder simulation | Strongly depends on platform and file-system semantics and needs a dedicated test entry. |
