# h2db 2.3.3 (2026-10-30)

[中文](RELEASE_NOTES_TEMPLATE.md)

## Summary and changes

- Fix false MVStore startup checks of obsolete dead-chunk locations. Chunks without live pages no longer require original headers/footers at reclaimed or reused locations.
- Retain the latest header-validated candidate. Falling below a known version requires a full scan first; validated fallback after a full scan remains supported.
- Add regressions for reused dead-chunk locations and independent recovery-version selection. Live-chunk corruption is not unconditionally ignored.

## Compatibility, upgrade and rollback

Coordinates: `net.xdob.h2db:h2db:2.3.3`. Java 8 remains supported. No SQL/JDBC API, network protocol or MVStore disk-format change.
Stop old-driver processes and retain a stopped-database backup before upgrading. Validate on a fresh copy before replacing dependencies. Reverting binaries does not undo writes; retain the pre-upgrade backup for rollback.
The fix cannot reconstruct overwritten data or guarantee recovery of every damaged database. Inspect damaged databases only on copies; do not overwrite production databases directly.

## Verification and limitations

The earlier candidate with the same recovery logic, reporting 2.3.2, passed JDK 8 compilation, 28 recovery scenarios, plugin, reclamation, legacy smoke, LongRun unit gates and a full TestAll CI rerun (19m19s). The initial TestValueMemory failure is not fully explained; standalone baseline/candidate checks and the full rerun passed. The initial record is retained.
On Windows, 32 actual-Core cycles on a fresh copy retained 920 PUBLIC objects and key-table row counts of 1/2/3. A live-chunk corruption copy was rejected with 90030.
Linux same-agent dual-node candidate crash/recovery completed. On the same controlled dead-chunk input the original selected 47 and lost the latest marker, while the candidate selected 50 and retained it; a single live-chunk corruption remained rejected. Report review passed within these controlled conditions, not as validation of natural reclamation, the original production cause or all HA behavior.
These results do not automatically qualify the final 2.3.3 artifact. Version, hashes and final-artifact gates require separate verification. Excluded slow/benchmark tests and the unexecuted 12-hour endurance run are not counted as passed.

## Release status

The 2.3.3 jar, sources, Javadoc and POM built successfully; `SELECT H2VERSION()` returned `2.3.3`. Recovery, space reclamation, plugin, reclamation contract, LongRun unit and legacy smoke gates passed. Full TestAll CI directly loaded the final packaged JAR with main classes excluded and passed in 21m56s, exit 0. Artifact correspondence review found only version/date and inlined-constant changes. The isolated local Maven dry run and signatures of all four artifacts passed verification; the main JAR matches the gated artifact. Central upload was not performed.

The user has authorized local release finalization. Technical gates for this scoped dead-chunk validation and recovery-selection fix are satisfied, subject to the limitations and retained initial failure record above. Central upload is performed manually by the user; this document does not confirm Central availability, a remote push, a tag or deployment.
