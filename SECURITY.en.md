# Security Policy

[中文](SECURITY.md)

## Supported Versions

Security fixes are handled on the active development line unless a maintainer announces a supported release branch.

## Reporting a Vulnerability

Please do not report suspected vulnerabilities in public issues before a maintainer has had a chance to triage them.
Do not include PoCs, exploit steps, or sensitive test data in public issue comments.

Preferred reporting path:

1. Use GitHub Security Advisory: `https://github.com/dibyang/h2db/security/advisories/new`.
2. If private reporting is not available, open a minimal public placeholder issue requesting private handling and include no exploit details.

Include:

* Affected version or commit.
* Reproduction steps or a minimal proof of concept.
* Expected impact, such as authentication bypass, data disclosure, remote code execution, denial of service, corruption, or privilege escalation.
* Any known mitigations.

Treat the following categories as security-sensitive: data exposure, unauthorized access, arbitrary file read or write, path traversal, exploitable deserialization, authentication or privilege bypass, denial of service reliably triggered by remote or untrusted input, and attack paths that can corrupt persistent data or bypass recovery.

The project will coordinate disclosure timing after confirming the issue and preparing a fix or mitigation.
