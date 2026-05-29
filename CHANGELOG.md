# 更新日志

[English](CHANGELOG.en.md)

本文件记录 h2db 面向外部用户的公开 release 变更。格式遵循“每个版本一节”的方式；发布前请将 `Unreleased` 中的内容移动到正式版本号。

## Unreleased

### 新增

- 补齐开源发布材料，包括 README、贡献指南、安全策略、支持说明、GitHub Release 指南、Maven Central 发布指南、第三方通知和中英文副本。
- 增加 MVStore 空间回收实验性维护 API 文档，说明受控维护窗口、诊断、残留清理和回滚策略。

### 变更

- Gradle 发布产物会在 main jar、sources jar 和 javadoc jar 的 `META-INF/` 下包含 `LICENSE.txt` 与 `NOTICE.txt`。

### 已知限制

- MVStore 空间回收能力当前是实验性维护 API，不暴露 SQL，不自动调度。
- prepared shadow 生成后源文件发生变化时，默认拒绝切换；显式开启降级选项后会执行维护态 full-copy。
