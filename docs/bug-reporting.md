# 问题反馈

[English](docs/bug-reporting.en.md)

## 反馈入口

- 普通问题：在 GitHub 新建 **Bug 报告**，使用仓库内置 issue 表单提交。
- 安全问题：先走 `Security Advisory`，不要在公开 issue 中披露漏洞细节。

## 先确认

- 先搜索已有 issue，避免重复。
- 确认这是针对本 fork（h2db）的问题，而不是通用 H2 使用疑问。
- 先提供可复现的最小示例，再补充更完整日志。

## 建议提交内容

- 组件：SQL/JDBC/MVStore/服务端/Console/LongRun/构建发布等。
- 版本：`h2db version`、`Java version`、`OS`、`JDBC URL`（必要时脱敏）。
- 重现步骤：最小 SQL、Java 片段或脚本。
- 预期 / 实际行为：明确差异。
- 堆栈与日志：完整异常栈、关键错误码、前后操作日志。
- 触发条件：并发度、数据规模、运行时长、失败频率。

## 安全问题

安全相关问题请不要在公开 issue 中给出 PoC 代码、数据样本或利用步骤。  
优先使用仓库的安全报告通道（Security Advisory）提交，审核通过后按公开方式同步修复进展。
