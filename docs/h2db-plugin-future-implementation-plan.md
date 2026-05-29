# H2 插件化 F0-F9 可追踪实施计划

## 范围

本计划承接 [h2db-plugin-architecture-future-roadmap.md](h2db-plugin-architecture-future-roadmap.md)，用于推进阶段一之后暂缓的插件化能力。每个阶段完成后必须本地提交，生产代码变更必须同步补 JUnit 测试。

## 总体状态

| 阶段 | 目标 | 状态 |
| --- | --- | --- |
| F0 | 建立后续阶段可追踪计划 | [x] |
| F1 | 插件诊断与 registry 可观测性 | [x] |
| F2 | storage engine id 持久化边界 | [x] |
| F3 | 缺失 storage 插件处理策略 | [x] |
| F4 | 显式外部插件加载 | [x] |
| F5 | 收敛第三方 Storage SPI 最小面 | [ ] |
| F6 | 插件依赖、版本和冲突治理 | [ ] |
| F7 | 安全边界第一版 | [ ] |
| F8 | S1/S2 maintenance 接入 | [ ] |
| F9 | 自动发现、示例和生态补齐 | [ ] |

## 统一门禁

| 门禁 | 要求 |
| --- | --- |
| G0 兼容 | 默认 MVStore、旧库、legacy `TableEngine` 行为不回退。 |
| G1 测试 | 每个生产代码阶段必须新增或更新 `src/test-plugin` JUnit。 |
| G2 构建 | 阶段提交前运行 `.\gradlew.bat test` 和必要的 `.\gradlew.bat compileJava`。 |
| G3 回滚 | 每个阶段能通过单独 commit 回滚，不混入下一阶段逻辑。 |
| G4 文档 | 每个阶段完成后更新本计划状态和测试编号。 |

## 阶段任务

### F0 后续规划收口

- [x] 新增本文档，作为 F0-F9 开发追踪入口。
- [x] 保留路线图文档作为设计依据。
- [x] 固定阶段名使用 `F*`，与阶段一 `P0-P6` 区分。

### F1 插件诊断与 Registry 可观测性

- [x] 新增 registry 快照 API，避免 SQL/测试直接依赖内部 `HashMap`。
- [x] 提供 provider/capability 诊断数据结构。
- [x] 增加 JUnit 覆盖 provider 来源、capability 展示、冲突诊断。
- [x] 本阶段先提供内部诊断入口，`INFORMATION_SCHEMA` 接入保留给后续 SQL 元数据阶段。

测试编号：

- [x] `T-PLUGIN-F1-INFO-SCHEMA-01`
- [x] `T-PLUGIN-F1-CAPABILITY-LIST-01`
- [x] `T-PLUGIN-F1-CONFLICT-DIAGNOSTIC-01`

### F2 Storage Engine Id 持久化边界

- [x] 新增 storage engine id 解析策略，旧库缺省为 `mvstore`。
- [x] 新增显式 storage engine id 与数据库实际 id 的校验入口。
- [x] 文档化真正磁盘格式持久化仍需单独 RFC。
- [x] 增加 JUnit 覆盖旧库默认、显式不匹配、回滚开关。

测试编号：

- [x] `T-PLUGIN-F2-OLD-DB-DEFAULT-01`
- [x] `T-PLUGIN-F2-STORAGE-ID-MISMATCH-01`
- [x] `T-PLUGIN-F2-ROLLBACK-01`

### F3 缺失 Storage 插件处理策略

- [x] 明确缺失 storage provider 默认拒绝打开。
- [x] 错误消息包含 storage engine id 和 provider type。
- [x] 不自动 fallback 到 `mvstore`。
- [x] 增加 JUnit 覆盖缺失、只读降级占位、无隐式 fallback。

测试编号：

- [x] `T-PLUGIN-F3-MISSING-STORAGE-FAIL-01`
- [x] `T-PLUGIN-F3-MISSING-STORAGE-READONLY-01`
- [x] `T-PLUGIN-F3-NO-MVSTORE-FALLBACK-01`

### F4 显式外部插件加载

- [x] 选择第一版显式配置入口：`PLUGIN_CLASSES`。
- [x] 加载实现 `H2Plugin` 的外部类并注册 provider。
- [x] 加载失败时显式报错。
- [x] 内置 provider 冲突不可覆盖。

测试编号：

- [x] `T-PLUGIN-F4-LOAD-CLASS-01`
- [x] `T-PLUGIN-F4-LOAD-FAIL-01`
- [x] `T-PLUGIN-F4-BUILTIN-CONFLICT-01`

### F5 稳定第三方 Storage SPI 最小面

- [ ] 收敛 storage SPI 的最小生命周期和异常语义。
- [ ] 增加 fake storage provider 编译/注册测试。
- [ ] 明确 `supports()` 无副作用。
- [ ] 文档化 experimental/stable 边界。

测试编号：

- [ ] `T-PLUGIN-F5-SPI-COMPAT-01`
- [ ] `T-PLUGIN-F5-CAPABILITY-PURE-01`
- [ ] `T-PLUGIN-F5-LIFECYCLE-01`

### F6 插件依赖、版本和冲突治理

- [ ] 增加插件描述元数据：H2 版本范围、依赖、允许 provider type。
- [ ] 加载时校验版本范围和依赖。
- [ ] 冲突错误包含 plugin id、provider type/id。
- [ ] 增加 JUnit 覆盖版本不匹配、依赖缺失、冲突治理。

测试编号：

- [ ] `T-PLUGIN-F6-H2-VERSION-RANGE-01`
- [ ] `T-PLUGIN-F6-DEPENDENCY-MISSING-01`
- [ ] `T-PLUGIN-F6-PROVIDER-CONFLICT-01`

### F7 安全边界第一版

- [ ] 限制外部插件 provider type 白名单。
- [ ] 增加敏感配置脱敏工具和测试。
- [ ] 明确 classloader 关闭/释放占位策略。
- [ ] 文档化不支持热卸载。

测试编号：

- [ ] `T-PLUGIN-F7-SENSITIVE-TRACE-01`
- [ ] `T-PLUGIN-F7-FORBIDDEN-CAPABILITY-01`
- [ ] `T-PLUGIN-F7-CLASSLOADER-CLOSE-01`

### F8 S1/S2 Maintenance 接入

- [ ] 扩展 `StorageMaintenanceResult` 表达 skipped / unsupported / success。
- [ ] MVStore maintenance 暴露 S1 compact 能力边界。
- [ ] S2 入口通过 capability gate 调用。
- [ ] 增加 JUnit 覆盖 unsupported 和 MVStore-only 行为。

测试编号：

- [ ] `T-PLUGIN-F8-S1-MAINTENANCE-01`
- [ ] `T-PLUGIN-F8-S2-VACUUM-GATE-01`
- [ ] `T-PLUGIN-F8-MVSTORE-S2-ONLY-01`

### F9 自动发现、示例和生态补齐

- [ ] `ServiceLoader` 默认关闭。
- [ ] 显式开启时发现 `H2Plugin`。
- [ ] 新增最小示例插件或测试内示例。
- [ ] 补充插件开发说明。

测试编号：

- [ ] `T-PLUGIN-F9-SERVICELOADER-OFF-01`
- [ ] `T-PLUGIN-F9-SERVICELOADER-ON-01`
- [ ] `T-PLUGIN-F9-SAMPLE-COMPILE-01`

## 验收命令

每个代码阶段至少运行：

```powershell
cd D:\work\java\h2db\h2
.\gradlew.bat test
.\gradlew.bat compileJava
```
