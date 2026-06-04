# Open Source Release Review

[中文](OPEN_SOURCE_RELEASE.md)

This document records the open source release readiness checks for h2db.

## Current Assessment

The repository now has the basic release materials expected for an open source Java project:

* `LICENSE.txt` with the H2 MPL 2.0 / EPL 1.0 dual license text.
* `NOTICE.txt` identifying the upstream H2 origin.
* `README.md` with project identity, build instructions, Maven coordinates, license, support, and release material links.
* `CHANGELOG.md` with public release changes for external users.
* `CONTRIBUTING.md` with development, testing, compatibility, and licensing expectations.
* `SECURITY.md` with private vulnerability reporting guidance.
* `CODE_OF_CONDUCT.md` and `SUPPORT.md`.
* `THIRD-PARTY-NOTICES.md` with a dependency license review inventory.
* GitHub issue, pull request, and release templates.
* `RELEASE_READINESS.md` with the latest local artifact readiness check.

## Blocking Items Before Public Release

The following items still need maintainer confirmation before a public release:

* Confirm h2db is presented as an independently maintained fork, not an official H2 release.
* Confirm the final repository URL, maintainer identity, and contact address in published POM metadata.
* Confirm the release version and Maven coordinates in `README.md`, `CHANGELOG.md`, release notes, `h2/gradle.properties`, and generated POM metadata.
* Enable or document a private security reporting channel.
* Run a resolved dependency license review for the exact release build.
* Confirm signing keys and release repository credentials are available only through secure local files or CI secrets.
* Verify generated source, javadoc, binary, and POM artifacts before upload.
* Verify the Maven Central namespace for `net.xdob` or choose a different namespace that you control.

## Release Checklist

1. Start from a clean working tree.
2. Review `LICENSE.txt`, `NOTICE.txt`, and `THIRD-PARTY-NOTICES.md`.
3. Confirm there are no undocumented binary artifacts or generated files in the release.
4. Build from `h2/`:

   ```sh
   ./gradlew clean jar javadocJar sourceJar generatePomFileForMavenPublication
   ```

5. Run the project-specific test command selected for the release.
6. Inspect generated POM license, SCM, developer, and dependency metadata.
7. Inspect source and binary archives for license and notice files; current Gradle artifacts include `LICENSE.txt` and `NOTICE.txt` under `META-INF/`.
8. If this release includes the LongRun distribution package, build and unpack `h2-longrun.zip` / `.tar.gz`, confirm scripts, configs, README files, and jar layout, and record profile acceptance results using `docs/longrun/longrun-release-acceptance.en.md`.
9. Sign release artifacts.
10. Publish through the Maven Central workflow.
11. Download published artifacts into a fresh workspace and smoke test them.
12. Create the Git tag after the immutable Maven Central version is verified.
13. Publish the GitHub Release with release notes, checksums, and known issues.

## Compliance Notes

The source headers in H2-derived files identify H2 Group copyright and the MPL 2.0 / EPL 1.0 license choices. The Gradle POM metadata must stay aligned with those license choices.

This document is a release engineering checklist, not legal advice. A maintainer or counsel should review it before first public distribution.

For the detailed public release process, see `MAVEN_CENTRAL_RELEASE.md` and `GITHUB_RELEASE.md`.
