# h2db 2.3.2

[中文](RELEASE_NOTES_TEMPLATE.md)

## Summary

2.3.2 fixes unsafe concurrent publication of uppercase string cache entries, preventing reads of uninitialized keys or values. This cache is used by JDBC result retrieval by column label and other paths.

## Changes

* Use immutable cache entries with final fields while retaining lock-free cache reads.
* Add publication checks, hash-collision conversion checks, and concurrent JDBC regression tests with independent connections.
* Use named daemon test workers with timeout checks so blocked tasks cannot prevent the test JVM from exiting.

## Compatibility and Upgrade

Maven coordinates are `net.xdob.h2db:h2db:2.3.2`. Java 8 remains supported.
SQL semantics, JDBC APIs, network protocols, and the MVStore on-disk format are unchanged. Databases from 2.3.1 can be upgraded. Back up databases and stop processes using the old driver before replacing the jar and restarting applications.

## SQL and JDBC

Applications do not need SQL changes. The reported production stack is `StringUtils.toUpperEnglish → JdbcResultSet.getColumnIndex → getString`.
Fault injection into the complete old jar reproduced the same H2 exception chain. Natural concurrency stress did not reproduce the failure. Unsafe publication is a high-confidence root-cause assessment, not directly demonstrated by a natural reproduction.

## Security, Storage and Recovery

This release declares no dedicated security fix and adds no database repair or recovery capabilities. The experimental MVStore reclamation API and the previous release's recovery limitations remain unchanged.

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.3.2</version>
</dependency>
```

## Verification

JDK 8 jar, sources, javadoc, and POM packaging passed. Running `SELECT H2VERSION()` against the packaged jar returned `2.3.2`.

`runPluginArchitectureCheck`, `runH2LegacySmoke`, `runMvStoreSpaceReclamationCheck`, `runMvStoreRecoveryCheck`, `runMvStoreReclamationJUnitCheck`, `runLongRunJUnitCheck`, and `runH2TestAllCi` all passed. Full CI took 19 minutes 47 seconds.

The CI configuration did not execute four slow/benchmark tests: `TestMVStoreBenchmark`, `TestLargeBlob`, `TestSubqueryPerformanceOnLazyExecutionMode`, and `TestDefrag`.
The 12-hour LongRun has not been repeated for this release; the 2.3.1 endurance result is not a 2.3.2 acceptance result.

## Release Status

This document prepares the 2.3.2 release. Maven Central publication, tags, and GitHub Release status must be confirmed after the corresponding publishing operations.
