# Bug Reporting

[中文](bug-reporting.md)

## How to Report

- Regular bugs: open a **Bug report** from the repository issue templates.
- Security issues: use GitHub Security Advisory first and avoid disclosing exploit details, PoCs, sample data, or exploitable steps in public issues.

## Please Check First

- Search existing issues to avoid duplicates.
- Confirm this is a fork-specific issue (h2db), not a generic upstream H2 usage question.
- Try to confirm the issue still reproduces on the latest release or current main branch.
- Provide a minimal reproducible example before attaching large logs.

## What to Include

- Affected area: SQL / JDBC / MVStore / server / Console / LongRun / build and release.
- Usage path: direct JDBC, embedded mode, TCP server, Console, MVStore API, framework integration, or script/tool usage.
- Versions: `h2db version`, Maven coordinates or commit, `Java version`, `OS`, `JDBC URL` (redact secrets).
- Reproduction steps: minimal SQL, Java snippet, or script.
- Expected vs actual behavior.
- Stack traces / logs and relevant error codes.
- Trigger shape: concurrency, thread count, iteration count, data scale, run duration, first failure time, and failure frequency.

## Security issues

For potential security reports, do not post PoCs, sensitive data samples, or exploit steps in public issue comments. Submit through Security Advisory first; public discussion will be coordinated after triage.

Treat the following categories as security-sensitive: data exposure, arbitrary file read or write, path traversal, exploitable deserialization, authentication or privilege bypass, denial of service reliably triggered by remote or untrusted input, and attack paths that can corrupt persistent data.
