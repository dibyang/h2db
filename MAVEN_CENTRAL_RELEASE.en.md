# Maven Central Release Guide

[中文](MAVEN_CENTRAL_RELEASE.md)

This guide is for publishing h2db to Maven Central for external users with Gradle `maven-publish`.

Maven Central releases are effectively immutable. If a bad version is published, the normal recovery path is to publish a new fixed version rather than modify or delete the old one.

## Current Project State

This repository publishes with Gradle's standard `maven-publish` plugin. The Gradle project already creates the required main, sources, javadoc, and POM artifacts.

```sh
cd h2
./gradlew clean jar sourceJar javadocJar generatePomFileForMavenPublication
```

The generated POM currently declares:

* `groupId`: `net.xdob.h2db`
* `artifactId`: `h2db`
* `version`: from `h2/gradle.properties`
* licenses: `MPL 2.0` and `EPL 1.0`
* SCM URL: `https://github.com/dibyang/h2db`
* developer: `dib.yang`

Confirm these values before the first public release.

## Maven Central Requirements

Before release, verify these items against the generated artifacts:

* The version is a final release version, not `-SNAPSHOT`.
* Every binary artifact has matching sources and javadoc jars.
* Every deployed file has required checksums.
* Every deployed file is signed with GPG/PGP and has a matching `.asc` file.
* The POM has valid coordinates, project name, description, URL, licenses, developer information, and SCM information.
* The namespace for the selected `groupId` is verified in Sonatype Central Portal.

Official references (re-check these pages before publishing):

* https://central.sonatype.org/publish/requirements/
* https://central.sonatype.org/register/central-portal/
* https://central.sonatype.org/publish/requirements/gpg/
* https://central.sonatype.org/publish/requirements/immutability/
* https://central.sonatype.org/publish/publish-portal-api/

## One-Time Maintainer Setup

1. Create or select the public GitHub repository for h2db.
2. Verify the Maven Central namespace that matches the planned `groupId`.
3. Create a GPG/PGP signing key for release signing.
4. Publish the public key so Central users can verify signatures.
5. Create Maven Central user tokens or repository credentials.
6. Configure local publication properties in `h2/signing.properties`.
7. Store credentials only in local protected files or CI secrets.

Do not commit signing keys, passphrases, Central tokens, or repository credentials.

`h2/signing.properties` is intentionally ignored by Git. Keep it local and use placeholder documentation instead of committing real values.

```properties
# Fill these with your Central Portal / compatible publishing endpoints.
# If you use the Portal Publisher API, first generate a local staging bundle
# with maven-publish, then upload it with the Central Portal API.
releasesRepository=https://YOUR_RELEASE_REPOSITORY_URL/
snapshotsRepository=https://YOUR_SNAPSHOT_REPOSITORY_URL/

signing.keyId=YOUR_KEY_ID
signing.password=YOUR_GPG_PASSPHRASE
signing.secretKeyRingFile=/path/to/secret-keyring.gpg

ossrhUsername=YOUR_CENTRAL_TOKEN_USERNAME
ossrhPassword=YOUR_CENTRAL_TOKEN_PASSWORD
```

## Pre-Release Checks

From `h2/`:

```sh
./gradlew clean compileJava jar sourceJar javadocJar generatePomFileForMavenPublication
```

Then inspect:

* `build/publications/maven/pom-default.xml`
* `build/libs/*`
* generated source and javadoc jars

Confirm:

* POM license metadata matches `LICENSE.txt`.
* POM SCM URL points to this fork.
* POM developer contact is correct for external users.
* README dependency snippet matches the released version.
* `THIRD-PARTY-NOTICES.md` matches the resolved dependency graph.
* No release credential, signing key, or passphrase is tracked by Git.

## Local Dry Run

Before publishing externally, publish to the local Maven repository.

```sh
./gradlew clean publishToMavenLocal "-Pversion=2.2.10" "-Dmaven.repo.local=build/test-m2-release-clean"
```

Inspect the generated files under the local Maven repository:

* `h2db-<version>.jar`
* `h2db-<version>-sources.jar`
* `h2db-<version>-javadoc.jar`
* `h2db-<version>.pom`
* Final release versions should have matching `.asc` signatures.

The build disables Gradle Module Metadata generation because this release path publishes standard Maven artifacts to Maven Central.

## Publish With Maven Publish

The build reads repository URLs and credentials from properties:

* `releasesRepository`
* `snapshotsRepository`
* `ossrhUsername`
* `ossrhPassword`

`-SNAPSHOT` versions publish to `snapshotsRepository`; final versions publish to `releasesRepository`.

Snapshot builds do not attach signing artifacts by default to avoid stale local or Gradle 6 module metadata signing issues. Final release versions attach and generate signatures. If a snapshot repository requires signatures, run with `-PsignSnapshots`.

If the maintainer has a release repository endpoint compatible with `maven-publish`, use this command from `h2/`:

```sh
./gradlew clean publish "-Pversion=2.2.10"
```

If using the Central Portal Publisher API, first generate a local Maven repository layout or staging bundle, confirm signatures and checksum files, and upload it with the Portal API. Use the external publish step only after the local dry run confirms the generated repository layout, signatures, and POM metadata pass validation.

## Post-Publish Verification

After Central validates and publishes the release:

1. Create a fresh temporary project.
2. Depend on the released coordinate and version from Maven Central.
3. Run a small JDBC smoke test against an in-memory database.
4. Verify the downloaded POM has the expected license, SCM, and developer metadata.
5. Create the matching Git tag.
6. Publish the GitHub Release.

## Rollback Policy

Do not attempt to overwrite a released Maven Central version. If a release is bad, publish a new patch version and document the issue in the GitHub Release notes.
