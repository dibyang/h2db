# Third-Party Notices

This inventory is derived from `h2/build.gradle` and is intended for release
review. Re-check it before each release with the resolved dependency graph and
published POM files.

## Runtime and Optional Dependencies

| Component | Version | Scope | License to verify |
| --- | --- | --- | --- |
| `javax.servlet:javax.servlet-api` | 4.0.1 | implementation / optional servlet integration | CDDL-1.1 OR GPL-2.0 with Classpath exception |
| `jakarta.servlet:jakarta.servlet-api` | 5.0.0 | implementation / optional servlet integration | EPL-2.0 OR GPL-2.0 with Classpath exception |
| `org.apache.lucene:lucene-core` | 8.5.2 | implementation / optional full-text search | Apache-2.0 |
| `org.apache.lucene:lucene-analyzers-common` | 8.5.2 | implementation / optional full-text search | Apache-2.0 |
| `org.apache.lucene:lucene-queryparser` | 8.5.2 | implementation / optional full-text search | Apache-2.0 |
| `org.slf4j:slf4j-api` | 1.7.30 | implementation / optional logging API | MIT |
| `org.osgi:org.osgi.core` | 5.0.0 | implementation / OSGi metadata | Apache-2.0 |
| `org.osgi:org.osgi.service.jdbc` | 1.1.0 | compileOnly / OSGi JDBC service | Apache-2.0 |
| `org.locationtech.jts:jts-core` | 1.17.0 | implementation / spatial support | EPL-2.0 OR EDL-1.0 |

## Test Dependencies

| Component | Version | Scope | License to verify |
| --- | --- | --- | --- |
| `org.slf4j:slf4j-nop` | 1.7.30 | testImplementation | MIT |
| `org.postgresql:postgresql` | 42.4.0 | testImplementation | BSD-2-Clause |
| `org.junit.jupiter:junit-jupiter-engine` | 5.6.2 | testImplementation | EPL-2.0 |
| `org.ow2.asm:asm` | 9.4 | testImplementation | BSD-3-Clause |

## Build Plugins

| Component | Version | Purpose | License to verify |
| --- | --- | --- | --- |
| `net.researchgate:gradle-release` | 2.6.0 | release automation | Apache-2.0 |

## Release Requirements

Before distributing source or binary artifacts:

* Regenerate or inspect the resolved dependency tree.
* Confirm each dependency license from its published POM or upstream release.
* Ensure license texts required by redistributed binaries are included in the
  release artifact or distribution bundle.
* Confirm no checked-in generated artifact or binary has an undocumented origin.
