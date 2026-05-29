# GitHub Release 指南

[English](GITHUB_RELEASE.en.md)

本文用于指导 h2db 的公开 GitHub Release。

## 发布顺序

先发布并验证 Maven Central，再创建 GitHub Release。这样可以避免 GitHub 已经公告，但 Maven Central 产物不可用。

## Release 附件

建议附加或链接：

* Release notes。
* GitHub 生成的 source archive。
* 如果希望 GitHub 用户直接下载，可附加 binary jar。
* 在 Maven Central 之外也有用时，可附加 sources 和 javadoc jar。
* 手工附加二进制文件时提供 checksums。
* Maven Central 坐标链接。

不要附加本地凭据、签名密钥、包含 secrets 的 staging 文件或未发布调试产物。

## Release Notes 模板

使用 `RELEASE_NOTES_TEMPLATE.md` 作为起点。每个公开 release 应包含：

* 兼容性说明。
* 安全修复，如有。
* 存储或 MVStore 恢复影响，如有。
* SQL 或 JDBC 行为变化。
* 已知问题。
* 升级建议。

## Tag

tag 应与 Maven 版本一致，例如：

```sh
git tag -s v2.2.10
git push origin v2.2.10
```

如果无法签名 tag，请说明如何通过 Maven Central PGP 签名验证 release 真实性。

## 首个公开 Release

首个公开 GitHub Release 应明确：

1. h2db 是独立维护 fork，不是官方 H2 release。
2. 说明此 fork 存在的原因。
3. 说明与上游 H2 的兼容目标。
4. 链接 `LICENSE.txt`、`NOTICE.txt`、`SECURITY.md` 和 `THIRD-PARTY-NOTICES.md`。
5. 列出 Maven 坐标和 Java 兼容目标。
