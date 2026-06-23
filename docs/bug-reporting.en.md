# Bug Reporting

[中文](docs/bug-reporting.md)

## How to Report

- Regular bugs: open a **Bug report** from the repository issue templates.
- Security issues: use GitHub Security Advisory first and avoid disclosing exploit details in public issues.

## Please Check First

- Search existing issues to avoid duplicates.
- Confirm this is a fork-specific issue (h2db), not a generic upstream H2 usage question.
- Provide a minimal reproducible example before attaching large logs.

## What to Include

- Affected area: SQL / JDBC / MVStore / server / Console / LongRun / build and release.
- Versions: `h2db version`, `Java version`, `OS`, `JDBC URL` (redact secrets).
- Reproduction steps: minimal SQL, Java snippet, or script.
- Expected vs actual behavior.
- Stack traces / logs and relevant error codes.
- Trigger shape: concurrency, data scale, run duration, and failure frequency.

## Security issues

For potential security reports, do not post PoCs, sensitive data samples, or exploit steps in public issue comments.  
Submit through Security Advisory first; public discussion will be coordinated after triage.
