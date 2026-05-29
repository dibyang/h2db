# H2 插件化后续特性规划

## 背景

阶段一已经完成最小插件化骨架：内置 MVStore storage/table provider 通过 `PluginRegistry` 注册，默认建表路径走 `TableEngineProvider`，S2 相关能力通过 capability gate 保留为 `UNSUPPORTED`。

本规划面向阶段一之前明确暂不做的插件化能力，目标是把它们拆成可评审、可开发、可测试、可回滚的后续阶段，而不是一次性引入完整插件生态。

## 规划目标

| 目标 | 说明 |
| --- | --- |
| 控制范围 | 先把必须服务 S2 和第二存储引擎的能力前置，延后插件市场、热插拔等高风险能力。 |
| 保持兼容 | 旧库、旧 `TableEngine` 类名、默认 MVStore 行为不因后续规划被破坏。 |
| 建立可追踪任务 | 每个阶段都有设计产物、代码范围、测试编号、验收命令和回滚边界。 |
| 明确拍板问题 | 在进入实现前列出必须定下来的配置、持久化、安全和错误策略。 |

## 总体取舍

| 能力 | 建议优先级 | 原因 |
| --- | --- | --- |
| 插件诊断与可观测性 | 高 | 外部插件加载前必须能看见已注册 provider、来源、能力和冲突。 |
| storage engine id 持久化 | 高 | 第二存储引擎和缺失插件策略都依赖该元数据。 |
| 缺失 storage 插件处理 | 高 | 一旦允许外部 storage，打开失败语义必须先定。 |
| 显式外部插件加载 | 中高 | 比 `ServiceLoader` 更可控，适合作为第一种外部加载方式。 |
| 第三方稳定 Storage SPI | 中高 | 需要在第二存储引擎前稳定最小接口面。 |
| 插件依赖与版本校验 | 中 | 外部插件加载后立即需要，但可先做最小 H2 版本范围。 |
| 外部插件安全边界 | 中 | 第一版可限制能力和加载来源，完整 sandbox 后置。 |
| S1 迁入 `StorageMaintenance` | 中 | 依赖 maintenance 边界稳定，可在 S2 前后分步迁移。 |
| S2 在线空间回收 | 中 | 由空间回收主线推进，但必须复用 capability gate。 |
| `ServiceLoader` 自动发现 | 低 | 自动发现不利于启动可控性，建议显式加载成熟后再引入。 |
| 热插拔 / 卸载 | 远期 | 涉及事务、缓存、对象生命周期和 classloader，阶段性收益不高。 |
| 插件市场 / 远程下载 | 远期 | 与核心数据库能力关系弱，安全和供应链成本高。 |

## 阶段路线图

| 阶段 | 名称 | 核心交付 | 不做内容 |
| --- | --- | --- | --- |
| F0 | 后续规划收口 | 本文档、阶段边界、拍板问题、测试编号池 | 不改代码 |
| F1 | 插件诊断与 registry 可观测性 | `INFORMATION_SCHEMA` / trace 展示 provider、来源、能力、冲突 | 不加载外部插件 |
| F2 | storage engine id 持久化设计与实现 | 持久化位置、旧库默认、回滚策略、缺失 id 行为 | 不引入第二 storage |
| F3 | 缺失 storage 插件与打开策略 | 明确失败、只读降级、错误码、诊断信息 | 不做自动下载或替换插件 |
| F4 | 显式外部插件加载 | URL / system property / database setting 中选择一种显式配置方式 | 不启用默认 `ServiceLoader` 自动发现 |
| F5 | 稳定第三方 Storage SPI 最小面 | 收敛 `StorageEngine`、`StorageEngineProvider`、版本兼容承诺 | 不承诺完整 MVStore 私有能力 |
| F6 | 插件依赖、版本和冲突治理 | H2 版本范围、插件依赖、provider 冲突、能力版本 | 不做插件市场 |
| F7 | 安全边界第一版 | classloader 边界、敏感配置脱敏、禁止核心路径扩展 | 不做完整 JVM sandbox |
| F8 | S1/S2 maintenance 接入 | S1 能力迁入或包装，S2 从 capability gate 进入 | 不强迫所有 storage 实现 MVStore 能力 |
| F9 | 自动发现、示例和生态补齐 | `ServiceLoader` 可选发现、插件示例、文档 | 不做热插拔 |

## 分阶段任务

### F0 后续规划收口

- [ ] 确认本文档作为阶段一之后的主路线图。
- [ ] 确认阶段命名使用 `F*`，避免和已完成的 `P0-P6` 混淆。
- [ ] 为每个阶段补充 `T-PLUGIN-F*` 测试编号。
- [ ] 将后续开发计划拆到独立可追踪文档。

验收：

- 文档评审通过。
- 不要求代码变更。

### F1 插件诊断与 Registry 可观测性

目标：在真正支持外部插件前，先能观察当前 registry 状态。

实现建议：

- 新增只读诊断入口，优先考虑 `INFORMATION_SCHEMA` 表。
- 暴露字段：provider type、provider id、plugin id、version、source、capability、是否内置。
- trace 中记录内置 provider 注册结果和冲突错误。
- 保持 `PluginRegistry` 内部结构不可被 SQL 修改。

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F1-INFO-SCHEMA-01` | 可查询内置 `mvstore` storage/table provider。 |
| `T-PLUGIN-F1-CAPABILITY-LIST-01` | capability 展示与 `supports()` 结果一致。 |
| `T-PLUGIN-F1-CONFLICT-DIAGNOSTIC-01` | provider 冲突错误包含 type/id/source。 |

拍板问题：

- 诊断表是否进入标准 `INFORMATION_SCHEMA`，还是先放入 H2 私有表。
- capability 是按 provider 一行多 capability，还是一行一个 capability。

### F2 Storage Engine Id 持久化

目标：为第二 storage engine、外部 storage 插件和缺失插件策略建立元数据基础。

候选方案：

| 方案 | 优点 | 风险 | 建议 |
| --- | --- | --- | --- |
| URL / database setting | 实现简单，易回滚 | 不能证明文件原本属于哪个 storage | 仅适合作为打开时显式 override |
| MVStore meta map | 与数据库元数据同存储 | 仍依赖 MVStore 才能读取 | 不适合作为通用 storage id |
| 独立 sidecar 元数据文件 | 可先于 storage 打开读取 | 多文件一致性和备份复杂 | 可作为第二阶段候选 |
| 文件 header | 最准确 | 触及磁盘格式，回滚成本最高 | 第二 storage 前单独 RFC |

阶段建议：

- F2 先写单独 RFC，不急着改磁盘格式。
- 阶段一旧库默认 `mvstore` 的策略继续保留。
- 如果引入持久化，只允许新库写入；旧库升级必须可回滚。

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F2-OLD-DB-DEFAULT-01` | 无 storage id 的旧库默认 `mvstore`。 |
| `T-PLUGIN-F2-STORAGE-ID-MISMATCH-01` | 显式 storage id 与持久化 id 不一致时拒绝打开。 |
| `T-PLUGIN-F2-ROLLBACK-01` | 禁用新 storage id 写入后旧库仍可按 MVStore 打开。 |

拍板问题：

- storage id 是强约束还是提示信息。
- storage id 是否参与 backup / restore。
- 持久化 id 是否包含 provider version。

### F3 缺失 Storage 插件处理

目标：数据库显式依赖某个 storage 插件但当前环境缺失时，行为可预测。

默认策略建议：

- 默认拒绝打开。
- 错误信息必须包含 storage engine id、plugin id、期望来源和建议配置。
- 只读降级必须显式开启，不作为默认。
- 不能自动 fallback 到 `mvstore`，避免误读文件。

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F3-MISSING-STORAGE-FAIL-01` | 缺失 storage 插件默认拒绝打开。 |
| `T-PLUGIN-F3-MISSING-STORAGE-READONLY-01` | 显式允许只读降级时只读打开或给出不可降级原因。 |
| `T-PLUGIN-F3-NO-MVSTORE-FALLBACK-01` | 显式非 MVStore storage 不会自动 fallback 到 MVStore。 |

拍板问题：

- 是否需要新的错误码，还是使用现有 `FEATURE_NOT_SUPPORTED` / `GENERAL_ERROR`。
- 只读降级是否只允许 provider 明确声明支持。

### F4 显式外部插件加载

目标：支持用户明确配置插件类，使 table/storage provider 能从外部进入 registry。

配置来源候选：

| 来源 | 优点 | 风险 | 建议 |
| --- | --- | --- | --- |
| URL 参数 | 单库显式，测试简单 | URL 过长，敏感信息暴露 | 第一版可选 |
| 系统属性 | 易全局启用 | 测试污染、环境依赖 | 适合开发调试 |
| database setting | 可持久化配置 | 依赖数据库已打开 | 不适合 storage 打开前 |
| 配置文件 | 可控、可审计 | 新增文件发现规则 | 后续可加 |
| `ServiceLoader` | Java 标准 | 自动生效不可控 | 放到 F9 |

第一版建议：

- 支持显式 class name 列表，来源为 URL 参数或系统属性二选一。
- 类必须实现 `H2Plugin`。
- 外部 provider 不能覆盖内置 provider。
- 加载失败时，如果是显式配置则启动失败。

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F4-LOAD-CLASS-01` | 显式插件类可注册 table provider。 |
| `T-PLUGIN-F4-LOAD-FAIL-01` | 显式插件类加载失败时错误包含类名。 |
| `T-PLUGIN-F4-BUILTIN-CONFLICT-01` | 外部 provider 不能覆盖内置 `mvstore`。 |

拍板问题：

- 第一版配置入口选 URL 参数、系统属性，还是两者都支持。
- 是否允许一个插件注册多个 provider。
- 外部插件加载时机在 `Database` 构造前还是构造中。

### F5 稳定第三方 Storage SPI 最小面

目标：从“内部实验接口”收敛成可给第三方实现的最小稳定面。

必须收敛的接口：

- `StorageEngineProvider`
- `StorageEngine`
- `StorageEngineContext`
- `StorageMaintenance`
- `StorageMaintenanceResult`
- capability 命名和版本策略

设计原则：

- 不暴露 MVStore 私有对象。
- 不强迫第三方实现 backup、compact、online vacuum。
- 所有可选能力必须通过 capability 表达。
- `supports()` 必须无副作用。
- 生命周期方法需要明确幂等性和异常语义。

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F5-SPI-COMPAT-01` | 最小 fake storage provider 可编译并注册。 |
| `T-PLUGIN-F5-CAPABILITY-PURE-01` | `supports()` 不触发 storage 打开或重资源加载。 |
| `T-PLUGIN-F5-LIFECYCLE-01` | open / flush / close 失败路径释放资源。 |

拍板问题：

- storage SPI 是否继续放 `org.h2.api`。
- 是否需要 `@Experimental` 标识或文档约束。
- `StorageEngine.closeImmediately()` 是否足够，是否需要 graceful close。

### F6 插件依赖、版本和冲突治理

目标：避免外部插件在不兼容 H2 版本或依赖缺失时静默运行。

建议字段：

| 字段 | 说明 |
| --- | --- |
| plugin id | 稳定唯一标识。 |
| plugin version | 插件版本。 |
| h2 version range | 支持的 H2 版本范围。 |
| dependencies | 依赖的插件 id/version。 |
| providers | provider type/id 列表。 |
| capabilities | 声明能力。 |

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F6-H2-VERSION-RANGE-01` | H2 版本不匹配时拒绝加载。 |
| `T-PLUGIN-F6-DEPENDENCY-MISSING-01` | 插件依赖缺失时拒绝加载并给出诊断。 |
| `T-PLUGIN-F6-PROVIDER-CONFLICT-01` | provider 冲突时内置优先且失败。 |

拍板问题：

- version range 使用 Maven 风格、语义版本，还是简单最小/最大版本。
- 依赖解析是否支持可选依赖。

### F7 安全边界第一版

目标：在不做完整 sandbox 的前提下，先把风险边界收紧。

第一版建议：

- 外部插件默认只能提供 table/storage provider。
- 禁止外部插件扩展 SQL parser、optimizer、wire protocol。
- 插件配置中的敏感字段不能进入 trace 明文。
- 外部插件 classloader 生命周期和数据库生命周期绑定。
- 明确不承诺热卸载。

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F7-SENSITIVE-TRACE-01` | 敏感配置不会写入 trace 明文。 |
| `T-PLUGIN-F7-FORBIDDEN-CAPABILITY-01` | 禁止外部插件声明未开放 provider type。 |
| `T-PLUGIN-F7-CLASSLOADER-CLOSE-01` | 数据库关闭后释放插件 classloader 引用。 |

拍板问题：

- 是否需要单独 classloader，还是先复用当前 classpath。
- 敏感配置的识别规则。
- 禁止 provider type 是硬编码列表还是配置开关。

### F8 S1 / S2 Maintenance 接入

目标：把已有维护能力和 S2 在线空间回收纳入 `StorageMaintenance`。

建议顺序：

1. 先把 S1 已有 compact / recover / manifest 相关能力包装成 maintenance capability。
2. 再让 S2 只通过 `StorageMaintenance.vacuumOnline()` 进入。
3. MVStore provider 可以首先声明支持 S2 capability，其他 storage 默认不支持。
4. 所有工具层入口先查 capability，再调用具体实现。

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F8-S1-MAINTENANCE-01` | S1 compact 能力可通过 maintenance 查询。 |
| `T-PLUGIN-F8-S2-VACUUM-GATE-01` | 未声明 online vacuum 的 storage 拒绝 S2。 |
| `T-PLUGIN-F8-MVSTORE-S2-ONLY-01` | S2 第一版只对 MVStore provider 开启。 |

拍板问题：

- S1 是否必须先迁入，还是 S2 可先独立实现。
- `vacuumOnline()` 是否需要 options/result 结构升级。
- 工具层命令是否新增 SQL / API 入口。

### F9 自动发现、示例和生态补齐

目标：显式外部插件成熟后，再降低接入成本。

可选能力：

- `ServiceLoader` 自动发现，默认关闭或通过配置开启。
- 示例插件：最小 table provider、只读 fake storage provider、maintenance capability 示例。
- 插件开发文档：兼容承诺、测试模板、错误码、capability 命名规范。

测试编号：

| 编号 | 内容 |
| --- | --- |
| `T-PLUGIN-F9-SERVICELOADER-OFF-01` | 默认不自动加载 classpath 插件。 |
| `T-PLUGIN-F9-SERVICELOADER-ON-01` | 显式开启后可发现插件。 |
| `T-PLUGIN-F9-SAMPLE-COMPILE-01` | 示例插件可编译并通过最小测试。 |

拍板问题：

- `ServiceLoader` 默认是否关闭。
- 示例插件是否放在主仓库，还是独立样例目录。

## 暂不进入近期阶段的能力

| 能力 | 延后原因 | 重新评估条件 |
| --- | --- | --- |
| 热插拔 / 卸载 | 涉及事务、缓存、classloader 和打开对象生命周期 | 外部插件加载稳定并有真实运行时替换需求 |
| 插件市场 / 远程下载 | 供应链和安全风险高，和核心数据库能力弱相关 | 明确产品化分发需求 |
| SQL parser / optimizer 插件 | 会破坏兼容和优化器稳定性 | table/storage 插件生态成熟后另开 RFC |
| 跨进程插件管理 | 超出嵌入式数据库核心边界 | 有 server 模式插件管理需求 |
| 多版本同名插件并存 | classloader 和 provider id 解析复杂 | 出现同库多插件版本共存需求 |

## 开发计划文档建议

后续应为每个阶段单独建立可追踪开发计划，例如：

- `docs/h2db-plugin-f1-diagnostics-plan.md`
- `docs/h2db-plugin-f2-storage-id-plan.md`
- `docs/h2db-plugin-f4-external-loading-plan.md`
- `docs/h2db-plugin-f8-maintenance-plan.md`

每份计划至少包含：

- 范围与非范围。
- 任务清单。
- 测试编号。
- 验收命令。
- 回滚策略。
- 需要拍板的问题。

## 下一步建议

建议下一轮先做 F1，再做 F2/F3。

理由：

- F1 不改变磁盘格式，也不加载外部代码，风险最低。
- F1 会让后续外部插件加载、冲突、capability、S2 gate 都有可观察入口。
- F2/F3 是第二 storage engine 和外部 storage plugin 的硬前置。
- F4 之后才真正进入外部插件加载，届时错误和诊断基础已经具备。
