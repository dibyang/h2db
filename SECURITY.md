# 安全策略

[English](SECURITY.en.md)

## 支持版本

除非维护者明确宣布某个 release branch 仍受支持，安全修复默认只在当前活跃开发线处理。

## 报告漏洞

在维护者完成初步分流前，请不要通过公开 issue 报告疑似安全漏洞。

首选报告路径：

1. 如果本仓库启用了 GitHub private vulnerability reporting 或 Security Advisories，请优先使用。
2. 如果 GitHub 私密报告不可用，请联系已发布 Maven POM 元数据中的维护者。

报告请包含：

* 受影响版本或 commit。
* 复现步骤或最小 proof of concept。
* 预期影响，例如认证绕过、数据泄露、远程代码执行、拒绝服务、数据损坏或权限提升。
* 已知缓解方案。

项目会在确认问题并准备修复或缓解措施后协调披露时间。
