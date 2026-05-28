# H2 MVStore 文件损坏恢复增强 RFC（草案）

## 背景

用户生产环境偶发 `.mv.db` 文件损坏，典型异常包括：

- `JdbcSQLNonTransientConnectionException: File corrupted while reading record`
- `MVStoreException: File is corrupted - unable to recover a valid set of chunks`
- `MVStoreException: File corrupted in chunk ..., expected page length ..., got ...`

当前只读排查已经确认：

- 用户坏库不是简单尾部截断。
- 用户坏库存在 compact/空间复用相关的 live chunk 位置错位。
- `discoverChunk()` 可能被普通页面内容或空洞误导，把完整 chunk 候选清掉。
- layout 遍历和 chunk 重定向存在循环依赖：要读到 `chunk.X` 元数据才能重定向 `X`，但 `chunk.X` 元数据页可能就在已搬迁的 `X` 里。
- 临时诊断路径 `--strict-discover --prime-valid-chunks` 可只读打开用户坏库，但该路径不是可直接合入的生产修复。

详细证据见 [h2db-corruption-investigation-plan.md](h2db-corruption-investigation-plan.md)。

## 目标

- 增强 MVStore recovery，使其能恢复“layout 指向旧 block，但同 id chunk 物理副本完整存在”的文件。
- 避免 `discoverChunk()` 被明显伪 chunk header 清掉完整候选。
- 打破 layout 遍历和 chunk 重定向之间的循环依赖。
- 不改变 `.mv.db` 磁盘格式。
- 为所有已登记故障模式补正式测试代码，并用测试验证修复效果。

## 非目标

- 不支持 `FILE_LOCK=NO`、`nolock:` 或多 JVM embedded 同写这类 unsupported 场景。
- 不在生产库原文件上执行破坏性 repair。
- 不把 `No space left on device` 作为当前主线根因，但保留通用回归测试。
- 不在方案讨论完成前修改 `h2/src/main` 生产代码。

## 现状/已有流程

关键源码路径：

| 路径 | 当前行为 |
| --- | --- |
| `RandomAccessStore.readStoreHeader()` | 根据 store header、tail chunk、quick/full scan 选择可恢复 last chunk。 |
| `FileStore.discoverChunk()` | 从后往前根据 footer/header 找 chunk 候选；遇到区间内任意可解析 header 会清空候选。 |
| `FileStore.findLastChunkWithCompleteValidChunkSet()` | 遍历 layout map，检查 live chunk 是否都指向有效位置；同 id 有物理副本时尝试改写 `c.block`。 |
| `FileStore.getChunksFromLayoutMap()` | 遍历 layout 时把 layout chunk 元数据放入 `chunks`，但如果 `chunks` 已有同 id 对象，会复用已有对象。 |
| `FileStore.readPage()` / `getChunk()` | 读 page 时按 `chunks` 或 layout 中的 chunk block 定位物理位置。 |

当前失败链条：

1. compact 移动 chunk 后，旧 block 释放并可能被复用。
2. 可恢复的 layout 仍指向旧 block。
3. full scan 可能本来能找到完整的新位置 chunk。
4. `discoverChunk()` 被伪 header 清掉该完整候选，导致 `validChunksById` 没有可重定向目标。
5. 即使找到了物理副本，layout 页本身可能位于被搬迁 chunk 内，遍历 layout 前还没有机会执行重定向。

## 核心约束

- Java 8 兼容。
- 不改变磁盘格式，不新增必须持久化的字段。
- recovery 逻辑必须 read-only 可用。
- 不能用 header-only chunk 覆盖 layout 中的 `livePages`、`occupancy` 等动态元数据。
- chunk id 理论上可能回绕到 `0`，不能简单把所有 `id=0` 判为非法。
- 生产修复前必须先补或明确规划对应测试；当前 `TestMVStoreRecoveryCorruption` 已覆盖登记表中所有测试编号，生产修复实现后默认模式和 characterize 模式均已通过。

## 接口设计

推荐先采用“恢复期物理读取视图 + 更严格 chunk 发现”的增量方案，不改变公开 API 和磁盘格式。候选内部接口如下：

| 名称 | 类型 | 用途 |
| --- | --- | --- |
| `isPlausibleDiscoveredChunk(C chunk)` | 私有/受保护方法 | 判断 `discoverChunk()` 中读到的 header/footer 是否足以成为候选或清空当前候选。硬性条件至少包括 `len > 0`、`block > 0`、header/footer 位置一致、id 匹配。 |
| `validChunksById` | 恢复临时 map | 保存 full scan 发现的物理完整 chunk。 |
| `recoveryPhysicalChunksById` | 候选临时读取视图 | 只在候选校验期间辅助 `readPage()` / `getChunk()` 定位物理 chunk，不作为 layout 动态元数据来源。 |
| `withRecoveryPhysicalChunks(Map<Integer, C>, RecoveryWork)` | 内部作用域方法或 try/finally 模式 | 在单个 last chunk 候选校验期间启用物理读取视图，候选结束后必须清空，避免污染正常打开状态。 |
| `readPage()` fallback | 内部恢复分支 | 仅当按 layout block 读取页面失败，且同 id 物理候选 header/footer 完整时，临时用物理候选 block 重读该 page。 |

待确认点：

- 物理读取视图更倾向放在 `FileStore` 的短生命周期字段中，通过 `try/finally` 在 `findLastChunkWithCompleteValidChunkSet()` 单个候选校验前后设置和清理；这样对现有调用链侵入最小。
- `readPage()` 是否直接感知 fallback，还是在 `getChunk()` 返回临时 block 修正后的副本，需要以最小代码改动和不污染 `chunks` 为准。
- `getChunksFromLayoutMap()` 目前会 `chunks.putIfAbsent()` 并复用已有同 id 对象，修复时必须防止 header-only 物理候选抢占 layout 权威元数据。

## 数据结构

### 物理 chunk 候选

来源：store header、tail chain、full scan `discoverChunk()`。

约束：

- 只证明“某 id 在某物理 block 上 header/footer 成对完整”。
- 不证明该 chunk 在当前候选 last chunk 的 layout 中是 live。
- 不携带权威 occupancy 信息。

### layout chunk 元数据

来源：候选 last chunk 对应的 layout map。

约束：

- 是 live/dead、occupancy、livePages 的权威来源。
- 其 `block` 可能因为 compact 崩溃窗口而指向旧位置。
- 当同 id 物理候选存在时，只允许用物理候选修正读取位置，不应丢失 layout 动态字段。

## 状态机

| 状态 | 说明 |
| --- | --- |
| `HeaderCandidate` | 从 store header 或 tail chunk 得到候选 last chunk。 |
| `FullScanCandidates` | full scan 收集物理完整 chunk。 |
| `LayoutTraversal` | 遍历候选 last chunk 的 layout map。 |
| `PhysicalReadFallback` | layout 遍历读页时，如果 layout block 无效，临时用同 id 物理候选读取。 |
| `MetadataRepoint` | 读到 layout 条目后，用 layout 动态元数据加物理候选 block 完成校验。 |
| `Recovered` | 候选 last chunk 通过完整 chunk 集校验。 |
| `RejectedCandidate` | 候选缺少 live chunk、页面校验失败或元数据不一致。 |

## 时序流程

候选方案 A（推荐优先实现）：

1. `discoverChunk()` 提高 header 合法性校验，避免伪 header 清空完整候选。
2. full scan 填充 `validChunksByLocation` 和 `validChunksById`。
3. 校验某个 last chunk 候选前，创建只在本次校验有效的物理读取视图。
4. 遍历 layout map 时，若 `readPage()` 根据 layout 旧 block 失败，并且同 id 物理候选存在，则用物理候选 block 重读该 page。
5. 读到 layout chunk 元数据后，仍以 layout 元数据为准；只把 `block` 修正到物理候选 block。
6. 候选校验通过后，再进入正常 `chunks` 初始化流程。

候选方案 B（暂不推荐先做）：修改 compact 写入顺序或 retention 策略，降低崩溃窗口。该方向可能更靠近根因，但会触碰写入路径和性能/空间复用行为，第一阶段不应先改。

候选方案 C（暂不推荐先做）：增强 `MVStoreTool.repair` 直接修复文件。当前 repair NPE 应修，但它属于工具健壮性和离线救援，不应替代引擎只读 recovery 的最小修复。

## 异常处理

- 伪 header：不应清空当前完整候选，继续向前扫描。
- layout page 旧 block 读失败：仅在 recovery 候选校验期间尝试物理读取视图。
- 物理候选 header/footer 不匹配：不得用于重定向。
- layout 动态元数据与物理候选明显冲突：拒绝该 last chunk 候选，尝试更早候选。
- 所有候选失败：保持现有 `ERROR_FILE_CORRUPT`，但建议增强诊断输出。

## 幂等性

recovery 打开本身不应修改文件。只读打开、多次打开、失败重试应得到一致结果。任何 repair 或导出型工具必须另行设计，不在本 RFC 第一阶段范围内。

## 回滚策略

- 不改变磁盘格式，代码回滚后旧文件仍按旧逻辑打开。
- 新增测试可保留。
- 如果恢复增强存在风险，可通过保守条件限制在 full scan recovery 分支，不影响 clean shutdown 快路径。

## 兼容性

- Java 8 兼容。
- `.mv.db` 磁盘格式兼容。
- 不改变 JDBC 错误码和公开 API。
- 需要验证 chunk id 回绕场景，避免误伤合法 `chunk:0,len>0`。

## 灰度/迁移

- 第一阶段只增强 read-only recovery 逻辑和诊断，不改变写入路径、不改变 `.mv.db` 格式。
- 第二阶段再评估 compact 写入顺序或 retention 策略是否需要调整。
- 生产侧可先通过关闭/限制 `SHUTDOWN COMPACT`、退出 compact 和破坏性 failedHandler 降低触发概率，但这不是 H2 引擎修复的替代。
- 如果第一阶段引入误判风险，应把 fallback 限定在 full scan recovery 分支；clean shutdown 快路径和正常读写路径不启用物理读取视图。

## 测试方案

正式测试必须覆盖 [h2db-corruption-investigation-plan.md](h2db-corruption-investigation-plan.md) 中的“故障模式测试覆盖追踪表”。

优先级：

| 优先级 | 测试 |
| --- | --- |
| P0 | `T-DISCOVER-FALSE-HEADER-01`、`T-RECOVERY-LAYOUT-CYCLE-01`、`T-RECOVERY-PHYSICAL-VIEW-01`、`T-COMPACT-MOVE-01` |
| P1 | `T-COMPACT-STORE-ORDER-01`、`T-SHUTDOWN-COMPACT-01`、`T-TORN-WRITE-01`、`T-DATA-MARKER-REGRESSION-01` |
| P2/P3 | backup、restore、disk full、多写者负面样本、连接生命周期测试、`T-RECOVERY-GENERATION-MATCH-01`、`T-NO-AUTO-COMPACT-BLOAT-01`、`T-OFFLINE-COMPACT-SHRINK-01` |

验收标准：

- 修复前测试能复现目标失败或明确刻画当前失败行为。
- 修复后测试通过。
- 所有恢复类测试校验 marker，避免只验证“能打开”。
- 当前已落地验证入口：
  - `.\gradlew.bat compileMvStoreRecoveryCheck`
  - `.\gradlew.bat "-Dh2.test.mvStoreRecoveryCorruption.characterize=true" runMvStoreRecoveryCheck`
  - `.\gradlew.bat runMvStoreRecoveryCheck`
- 当前结果：生产修复实现后，characterize 模式和默认模式均通过；用户提供的坏库副本执行 `MVStoreTool -info` 也已能只读输出 chunk 信息，不再报 `unable to recover a valid set of chunks`；`MVStoreTool.repair` 对错位样本、无可用 chunk 的坏文件和不完整 chunk 均不再 NPE；recovery 物理 chunk 读取视图已收紧为 `id + version + len` 匹配，同 id 多世代时禁用无版本信息的 id 兜底，避免同 id 不同世代误重定向；关闭自动 compact 后文件膨胀与离线 full compact 收缩已补充回归测试。

## 风险点

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 使用 header-only chunk 覆盖 layout 动态元数据 | 可能误判 live/dead page，引入静默数据丢失 | 物理读取视图与 layout 元数据对象分离。 |
| header 合法性校验过严 | 可能误伤合法旧库或 id 回绕场景 | 增加 `T-DISCOVER-ID-WRAP-01`，只把 `len=0` / 字段缺失等作为硬拒绝条件。 |
| fallback 过宽 | 可能读取错误物理副本 | 仅在 recovery 候选校验期间启用，且要求 header/footer 成对完整、id 匹配。 |
| 测试依赖本机坏库 | 不可复现 | 使用 fault injection 构造最小样本，不提交用户坏库。 |

## 分阶段实施计划

1. **测试先行（已完成）**：补全 `TestMVStoreRecoveryCorruption`，覆盖登记表中所有测试编号，不改生产恢复逻辑。
2. **方案确认（已完成）**：本文档讨论通过，明确物理读取视图实现边界和 `discoverChunk()` header 合法性规则。
3. **恢复增强第一阶段（已完成）**：修复 `discoverChunk()` 伪 header 清空候选问题，先让完整物理 chunk 进入 `validChunksById`。
4. **恢复增强第二阶段（已完成）**：实现 recovery 物理读取视图，解决 layout 遍历循环依赖；物理候选按 `id + version + len` 匹配，同 id 多世代时禁用无版本信息的 id 兜底，避免同 id 不同世代误重定向。
5. **工具健壮性阶段（已完成最小修复）**：`MVStoreTool.repair` 在当前错位样本、无可用 chunk 的坏文件和不完整 chunk 上不再触发 NPE；是否支持更强的离线自动修复文件另行评审。
6. **回归验证（已完成）**：已运行 `compileJava`、`runMvStoreRecoveryCheck` 默认模式、characterize 模式和既有 `TestReorderWrites`，默认模式已转绿。
7. **扩展排查**：再评估 compact retention、backup/shutdown 交互、关闭自动 compact 后的文件膨胀治理和产品侧 `H2DBTool` 模块级测试。

## 开放问题

- 物理读取视图已采用 `readPage()` fallback 加 `getChunk()` 临时物理候选兜底的组合方式实现；后续代码审查重点看该作用域是否足够窄。
- 是否需要给 recovery 增加更详细 trace 输出，还是只增强异常消息？
- compact 写入顺序是否也需要改，还是第一阶段只增强 recovery？当前建议第一阶段只增强 recovery。
- 用户生产侧是否能暂时限制 `SHUTDOWN COMPACT` 和退出 compact，降低复发概率？
