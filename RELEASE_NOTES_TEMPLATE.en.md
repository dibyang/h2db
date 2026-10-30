# h2db 2.4.2 (2026-10-30)

[中文](RELEASE_NOTES_TEMPLATE.md)

## Summary and changes

- Fix obsolete dead-chunk header checks and premature fallback from incomplete quick-recovery candidates. Retain the latest header-validated candidate and require a full scan before falling below a known version; legitimate full-scan recovery remains supported.
- Fix unsafe publication of uppercase cache entries with immutable final-field entries, preventing JDBC column-label lookup from observing uninitialized keys or values.
- Add regressions for reused dead-chunk locations, recovery selection and cache publication. Preserve existing v2.4.x prepared-snapshot and interrupt protections.

## Compatibility, upgrade and rollback

Coordinates: `net.xdob.h2db:h2db:2.4.2`. Java 8 supported. This patch does not change SQL/JDBC APIs, network protocols or the MVStore disk format.
Existing 2.4.x coordinated-backup restrictions still apply: after enabling it and writing internal database identity metadata, do not write to that database with 2.3.x. No-format-change for this patch does not permit downgrading across that boundary.
Stop old-driver processes and retain a stopped-database backup before upgrading; validate on a copy first. Reverting binaries does not undo writes; retain the pre-upgrade backup. Do not overwrite production databases directly with damaged or recovered copies.

## Verification status

The port passed JDK 8 compilation, 28 recovery scenarios, space reclamation and reclamation-contract checks on v2.4.x.
Full TestAll CI directly against the final 2.4.2 JAR passed, exit 0, in 21m47s. Online backup: 89 tests; reclamation contracts: 7; LongRun unit: 51; no failures. Plugin: 141 tests, 1 skipped, no failures. Legacy smoke, recovery and space-reclamation gates passed.
`SELECT H2VERSION()` returned `2.4.2`. Jar, sources, Javadoc and POM build, isolated local Maven dry run, license checks and all four signatures passed. Main JAR bytes match the full-CI artifact.
The 2.3.3 Linux comparison and full CI results are not 2.4.2 acceptance. Excluded slow/benchmark tests, unexecuted endurance runs and full real-business acceptance are not counted as passed.

## Known limits

The fix cannot reconstruct overwritten data or guarantee recovery of every damaged database, and does not claim complete HA protection. Synthetic injection is not natural reclamation; one live-chunk corruption test does not cover all corruption forms.
Coordinated backup does not replace deployment-side generation registries, active pointers, CAS or crash recovery. The DML fast path remains experimental; no new performance claims.

## Release status

Local release finalized; not uploaded to Central. Central upload is performed manually by the user; this does not confirm remote availability. No push, tag or deployment in this run. 2.4.2 Linux dual-node real-business acceptance was not run; local gates are not a production-environment guarantee.
