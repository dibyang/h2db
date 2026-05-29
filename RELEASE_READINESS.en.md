# Release Readiness Report

This is the English companion of [RELEASE_READINESS.md](RELEASE_READINESS.md). The Chinese document is the primary version.

Check date: 2026-05-29

## Conclusion

h2db now has the baseline materials required for a public GitHub Release and Maven Central publication. Repository-side readiness includes:

- Open-source materials: `LICENSE.txt`, `NOTICE.txt`, `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md`, `SUPPORT.md`, `CODE_OF_CONDUCT.md`.
- Release guides: `OPEN_SOURCE_RELEASE.md`, `MAVEN_CENTRAL_RELEASE.md`, `GITHUB_RELEASE.md`, `RELEASE_NOTES_TEMPLATE.md`.
- Third-party materials: `THIRD-PARTY-NOTICES.md`.
- English companions: all release-facing primary documents have `.en` companions or English companion documents.
- Maven Central artifacts: Gradle can generate the main jar, sources jar, javadoc jar, and POM.
- License-in-artifact coverage: the main jar, sources jar, and javadoc jar include `LICENSE.txt` and `NOTICE.txt` under `META-INF/`.
- Credential protection: `signing.properties`, `*.gpg`, and `*.asc` are ignored by Git, and no publishing credential or signing key is tracked.

## Local Verification

Executed from `h2/`:

```powershell
.\gradlew.bat clean jar sourceJar javadocJar generatePomFileForMavenPublication
.\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=build\test-m2-release-clean"
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
```

Generated artifacts:

- `h2/build/libs/h2db-2.2.10-SNAPSHOT.jar`
- `h2/build/libs/h2db-2.2.10-SNAPSHOT-sources.jar`
- `h2/build/libs/h2db-2.2.10-SNAPSHOT-javadoc.jar`
- `h2/build/publications/maven/pom-default.xml`

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

## Maintainer Checks On Release Day

- Change `h2/gradle.properties` to a final release version; do not publish a release with `-SNAPSHOT`.
- Confirm the `net.xdob` namespace, or the actual namespace in use, is verified in Central Portal.
- Confirm versions match across `README.md`, release notes, and the generated POM.
- Run the local dry run and signing checks with the real release version.
- Keep Central Portal tokens, GPG private keys, and passphrases only in protected local files or CI secrets.
- After Maven Central publication, run a JDBC smoke test from a fresh temporary project, then create the Git tag and GitHub Release.

## References

- Maven Central Requirements: https://central.sonatype.org/publish/requirements/
- Central Portal: https://central.sonatype.org/register/central-portal/
- PGP signatures: https://central.sonatype.org/publish/requirements/gpg/
- Immutability: https://central.sonatype.org/publish/requirements/immutability/
