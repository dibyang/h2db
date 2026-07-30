# Release Readiness Report

This is the English companion of [RELEASE_READINESS.md](RELEASE_READINESS.md). The Chinese document is the primary version.

Check date: 2026-07-30

## Conclusion

h2db 2.4.0 has the repository-side materials and local verification results required for a public GitHub Release and Maven Central publication:

The official release version is `2.4.0`. `h2/gradle.properties`, `Constants.VERSION/FULL_VERSION`, release notes, the POM, and artifact names use final release semantics without `-SNAPSHOT`.

- Open-source materials: `LICENSE.txt`, `NOTICE.txt`, `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md`, `SUPPORT.md`, `CODE_OF_CONDUCT.md`.
- Release guides: `OPEN_SOURCE_RELEASE.md`, `MAVEN_CENTRAL_RELEASE.md`, `GITHUB_RELEASE.md`, `RELEASE_NOTES_TEMPLATE.md`.
- Third-party materials: `THIRD-PARTY-NOTICES.md`.
- English companions: all release-facing primary documents have `.en` companions or English companion documents.
- Maven Central artifacts: Gradle can generate the main jar, sources jar, javadoc jar, and POM.
- License-in-artifact coverage: the main jar, sources jar, and javadoc jar include `LICENSE.txt` and `NOTICE.txt` under `META-INF/`.
- LongRun distribution: the standalone long-running stress-test package, configs, scripts, reporting, and bilingual documentation are in place; see `docs/longrun/longrun-release-acceptance.en.md`.
- Plugin foundation: static automatic discovery, provider registry, version/dependency resolution, diagnostic views, permission boundaries, and release-readiness documentation are in place; see `docs/plugin/plugin-release-readiness.en.md`.
- Online backup: coordinated online backup and shadow restore P0-P9, the 2.3 compatibility matrix, fault matrix, provider certification, and operations documentation are closed.
- DML fast path: experimental V1 is bounded to simple `INSERT ... VALUES` and JDBC batches; this release makes no ADB/LDB integration throughput multiplier claim.
- Credential protection: `signing.properties`, `*.gpg`, and `*.asc` are ignored by Git, and no publishing credential or signing key is tracked.

## Local Verification

Executed from `h2/`:

```powershell
.\gradlew.bat clean jar sourceJar javadocJar generatePomFileForMavenPublication
.\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=build\test-m2-release-clean"
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runH2LegacySmoke
.\gradlew.bat runOnlineBackupCheck
.\gradlew.bat --rerun-tasks runLongRunJUnitCheck
.\gradlew.bat --rerun-tasks longRunTestDistZip
.\gradlew.bat runH2TestAllCi
```

Generated artifacts:

- `h2/build/libs/h2db-2.4.0.jar`
- `h2/build/libs/h2db-2.4.0-sources.jar`
- `h2/build/libs/h2db-2.4.0-javadoc.jar`
- `h2/build/publications/maven/pom-default.xml`

The isolated local Maven repository at `h2/build/test-m2-release-clean/net/xdob/h2db/h2db/2.4.0/` contains the main, sources, javadoc, POM, and an `.asc` signature for each artifact. All four signatures pass `gpg --verify`.

The generated POM includes:

- `groupId`: `net.xdob.h2db`
- `artifactId`: `h2db`
- `licenses`: MPL 2.0 / EPL 1.0
- `developers`
- `scm`
- runtime dependencies

Confirmed the main jar, sources jar, and javadoc jar all include:

- `META-INF/LICENSE.txt`
- `META-INF/NOTICE.txt`

LongRun distribution verification:

- `runLongRunJUnitCheck` passed.
- `longRunTestDistZip` passed.
- Reliability configuration tests confirm that online-backup assertions for the 30-day SQL soak read `soak-30d-sql.properties`, not the regular MVStore soak profile.
- Linux 10-minute smoke acceptance passed: `PASS`, 4 reopen checks, 0 warnings, and 0 suspicious log lines.
- Linux crash/recovery acceptance passed: `PASS`, 15 crash cycles, 29 recovery checks, 0 warnings, and 0 suspicious log lines.
- Linux fault-injection acceptance passed: `PASS`, 14 fault injection events, 0 unexpected, 0 warnings, and 0 suspicious log lines.

Plugin verification:

- `runPluginArchitectureCheck` passed, 140/140.
- `runH2LegacySmoke` passed.
- Plugin non-goals for this release are documented in `docs/plugin/plugin-release-readiness.en.md`.

Online backup and full regression:

- `runOnlineBackupCheck` passed, 82/82.
- The bidirectional 2.3 database file, legacy zip, old plugin, and TCP compatibility matrix passed.
- `runH2TestAllCi` passed in 22m 2s, including default, lazy, network, network-lazy, and special storage/cache/encryption combinations.
- The full CI task reports that four heavyweight benchmark, defrag, lazy large-subquery, and large-blob tests are not executed by this task; these are coverage notices, not failures.

## Maintainer Checks On Release Day

- Confirm `version` in `h2/gradle.properties` is still the final release version `2.4.0`; do not publish a release with `-SNAPSHOT`.
- Confirm the `net.xdob` namespace, or the actual namespace in use, is verified in Central Portal.
- Confirm versions match across `README.md`, release notes, and the generated POM.
- If code changes after the release commit, rerun the local dry run, full CI, and signing checks with the final version.
- If this release advertises the LongRun distribution, run `longRunTestDistTar` before publishing and unpack both zip/tar packages in a clean directory to confirm script permissions and layout.
- If this release advertises the plugin baseline, release notes should reference the plugin entries in `CHANGELOG.en.md` and the non-goal boundaries in `docs/plugin/plugin-release-readiness.en.md`.
- If the release window allows it, run a shortened nightly or comprehensive LongRun to cover a longer normal-pressure combined path.
- Keep Central Portal tokens, GPG private keys, and passphrases only in protected local files or CI secrets.
- After Maven Central publication, run a JDBC smoke test from a fresh temporary project, then create the Git tag and GitHub Release.

## References

- Maven Central Requirements: https://central.sonatype.org/publish/requirements/
- Central Portal: https://central.sonatype.org/register/central-portal/
- PGP signatures: https://central.sonatype.org/publish/requirements/gpg/
- Immutability: https://central.sonatype.org/publish/requirements/immutability/
