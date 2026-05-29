# H2 MVStore 数据库文件损坏排查计划

## 背景

本文件记录 `fspool_db.mv.db` 偶发损坏问题的已知证据、已完成实验、当前判断、后续复现计划和修复方向。目标是让后续任意一次继续排查时，可以从本文直接恢复上下文，而不依赖对话历史。

当前讨论范围是 H2 MVStore `.mv.db` 文件损坏，典型外层异常为：

- `JdbcSQLNonTransientConnectionException: File corrupted while reading record`
- `MVStoreException: File is corrupted - unable to recover a valid set of chunks`
- `MVStoreException: File corrupted in chunk ..., expected page length ..., got 0`

用户已确认的运行条件：

- 服务使用 TCP server 模式承载业务连接。
- JDBC URL 未配置 `FILE_LOCK=NO`。
- 数据库文件位于物理盘。
- 自动 compact 已关闭后仍偶发损坏。
- 仍存在 `SHUTDOWN COMPACT`、退出 compact、备份和定期自动备份 `H2DBTool.autoBackup`。
- `No space left on device` 日志是专门测试制造的异常，当前排查主线中不作为根因证据。

## 目标

- 找到一种稳定或半稳定复现 `.mv.db` 损坏的方法。
- 区分真实根因与噪声：磁盘满、多进程写、备份冲突、compact、关闭流程、异常退出。
- 给出最小修复方案，并能用复现实验证明修复有效。
- 在讨论出修复结论前不修改生产代码；实验代码和副本文件只放在 `h2/temp/repro` 等临时区域。
- 所有排查到的、可能导致文件损坏或数据丢失的场景，都必须补正式测试代码或明确记录无法自动化的原因；未覆盖测试前，不得标记该场景已修复或已排除。
- 正式修复方案必须先形成仓库内设计文档，经讨论确认后再进入生产代码修改阶段。

## 执行门禁

本节是后续继续排查和修复的硬约束：

- **故障模式登记门禁**：任何新发现的风险场景，必须先补到“故障模式登记表”，并分配唯一编号。
- **测试覆盖门禁**：每个故障模式编号都必须映射到至少一个正式测试编号；测试暂未实现时，状态标记为“待补测试”，不能视为闭环。
- **修复前文档门禁**：修改 `h2/src/main` 生产代码前，必须先新增或更新 `docs/h2db-corruption-fix-rfc.md`，写清楚事实依据、候选方案、兼容性、回滚、测试矩阵和风险；该文档讨论通过前，只允许继续做只读诊断、复现实验和测试设计。当前草案见 [h2db-corruption-fix-rfc.md](h2db-corruption-fix-rfc.md)。
- **验证门禁**：生产修复完成前，必须能用本文登记的测试矩阵验证修复效果，至少覆盖 P0/P1 故障模式，并说明 P2/P3 的剩余风险。

## 非目标

- 不直接修复用户给出的原始损坏库文件。
- 不把用户本地证据文件提交进仓库。
- 不把 `FILE_LOCK=NO` / 多 JVM 同写作为当前主线根因，除非出现新的生产配置证据。
- 不在未确认方案前修改 `h2/src/main` 或生产调用方代码。

## 当前证据

### 证据文件

用户提供的只读证据文件：

| 文件 | 说明 | 当前结论 |
| --- | --- | --- |
| `C:\Users\admin\Documents\WXWork\1688850767058383\Cache\File\2026-05\fspool_db.trace.db` | H2 trace 日志 | 从 `2026-05-13 14:52:28` 起反复打开失败，错误集中在 `RandomAccessStore.readStoreHeader`。 |
| `C:\Users\admin\Documents\WXWork\1688850767058383\Cache\File\2026-05\fspool_db.trace (2).db` | H2 trace 日志 | 包含用户测试制造的 `No space left on device`，该线索已按用户说明排除；后续仍有重复打开坏库的日志。 |
| `C:\Users\admin\Documents\WXWork\1688850767058383\Cache\File\2026-05\fspool_db.mv.db.20260509102112` | 损坏 `.mv.db` 文件 | 修复前 `MVStoreTool -info` 复现 `unable to recover a valid set of chunks`，`-dump` 可顺序扫出大量完整 chunk；修复实现后 `-info` 已能只读输出 chunk 信息。 |

注意：证据文件不在仓库内。继续排查时如果本机路径不存在，需要重新向用户索取或使用新的坏库样本。

### 损坏库结构结论

对 `fspool_db.mv.db.20260509102112` 做只读分析：

- 文件长度约 `2641920` 字节。
- 文件头两份一致，指向 `chunk:a1aac, block:52, version:a1aac`。
- `chunk a1aac` 的时间换算约为 `2026-05-09 10:21:08 +08:00`。
- `MVStoreTool -dump` 能扫到文件尾 `eof`，说明不是简单的文件尾截断。
- 从当前版本 `a1aac` 的 meta tree 走图后，发现 1 个关键 live chunk 引用错位：
  - 当前 layout 记录：`chunk.a0d89 = ... block:22 ... livePages:1`
  - 物理扫描显示：`block:22` 实际是 `chunk:a1aab`
  - 物理扫描显示：真正的 `chunk:a0d89` 位于 `block:282`
- 对副本执行 `MVStoreTool -repair` 无法恢复，多个版本均报 `unable to recover a valid set of chunks`，最后触发 `MVStoreTool.rollback` 的 NPE。

当前判断：损坏形态更像“空间复用或 compact/关闭流程导致的 live chunk 位置引用错位”，而不是随机全文件破坏。

## 相关代码路径

| 路径 | 关注点 |
| --- | --- |
| `h2/src/main/org/h2/mvstore/RandomAccessStore.java:185` | `readStoreHeader()` 读取文件头、选择 last chunk、恢复 chunk 集合。 |
| `h2/src/main/org/h2/mvstore/RandomAccessStore.java:318` | 找不到完整有效 chunk 集时抛出 `unable to recover a valid set of chunks`。 |
| `h2/src/main/org/h2/mvstore/FileStore.java:987` | `findLastChunkWithCompleteValidChunkSet()` 验证 layout 中 live chunk 引用是否合法。 |
| `h2/src/main/org/h2/mvstore/FileStore.java:1009` | layout 指向位置无效时，若同 id chunk 在其他物理位置存在，会尝试把候选 chunk 重定向到该物理位置。 |
| `h2/src/main/org/h2/mvstore/FileStore.java:1019` | live chunk 引用位置无效时判定候选 last chunk 不可用。 |
| `h2/src/main/org/h2/mvstore/FileStore.java:1136` | `readChunkHeaderAndFooter()` 校验 chunk header/footer 与位置是否一致。 |
| `h2/src/main/org/h2/mvstore/FileStore.java:1508` | `storeBuffer()` 持久化 chunk。 |
| `h2/src/main/org/h2/mvstore/FileStore.java:1829` | 后台 writer 写入路径。 |
| `h2/src/main/org/h2/mvstore/FileStore.java:1946` | `readPage()` 根据 page pos 找 chunk 并读取页。 |
| `h2/src/main/org/h2/mvstore/Page.java:591` | 页长度错误时抛出 `expected page length ..., got ...`。 |
| `h2/src/main/org/h2/mvstore/MVStore.java:971` | `compactFile()` 会临时 `setRetentionTime(0)` 并调用 `compactStore()`。 |
| `h2/src/main/org/h2/mvstore/RandomAccessStore.java:438` | `compactStore()` 也会 `setRetentionTime(0)`，需要重点审查空间复用安全性。 |
| `h2/src/main/org/h2/mvstore/RandomAccessStore.java:527` | `compactMoveChunks()` 会先写 header 并 `sync()`，再移动 chunk、写 layout、再次 `sync()`。 |
| `h2/src/main/org/h2/mvstore/RandomAccessStore.java:644` | `moveChunk()` 的顺序是读旧 chunk、写新位置、释放旧空间、更新内存中的 `chunk.block`、写入 layout 元数据。 |
| `h2/src/main/org/h2/mvstore/db/Store.java:153` | 将 MVStore 文件损坏异常转换为 JDBC `90030`。 |
| `h2/src/main/org/h2/engine/Database.java:1269` | 数据库关闭时根据 compact mode 判断是否执行关闭 compact。 |
| `h2/src/main/org/h2/engine/Database.java` | Query 后置 `checkCompact()` 自动回收入口已移除；显式 `SHUTDOWN COMPACT` 和关闭流程 compact 仍保留。 |
| `h2/src/main/org/h2/command/dml/TransactionCommand.java:77` | `SHUTDOWN COMPACT` 命令入口。 |

## 已完成实验

| 实验 | 结果 | 结论 |
| --- | --- | --- |
| MVStore 乱序/部分写 fault injection，不 compact | 乱序/部分写可通过恢复 | 普通写入乱序不是当前最强主线。 |
| MVStore 显式 `compactFile(1000)` fault injection | 曾在 `seed=28 stop=21 partialWrite=false` 复现 `unable to recover a valid set of chunks` | compact 路径是高优先级嫌疑。 |
| `nolock:` 绕过文件锁，多 JVM 同写 JDBC DB | 可复现 `File corrupted in chunk ... got 0`，最终 reopen 只剩初始行 | 可证明多写者危险，但与用户生产条件不匹配，当前降级为负面参考。 |
| 同 JVM TCP server + embedded `SCRIPT DROP TO` + TCP `SHUTDOWN COMPACT` | autoBackup 报 `90135` exclusive mode，未造成文件损坏 | `autoBackup` 不是 TCP socket，但同 JVM embedded 会共享同一个 `Database` 实例；其失败处理可能误判正常关闭 compact。 |
| `autoBackup` failedHandler 等价 `Runtime.halt()` | 多轮 reopen 正常 | 目前未证明 autoBackup 单独导致损坏。 |
| JDBC `SHUTDOWN` + fault injection | `partialWrite=true` 时 30 轮中 2 轮复现 `90030` | JDBC 外层异常形态可复现，但 root 更像 chunk 集不可恢复。 |
| 对用户坏库执行 `MVStoreTool -info/-dump/-repair` | 修复前 `-info` 失败，`-dump` 能扫，`-repair` 失败；修复实现后 `-info` 已能只读打开 | 坏库是 layout/live chunk 引用错位；离线 repair 是否要支持更强自动修复仍需单独评审。 |
| `MVStoreForensics` 只读诊断工具 | 对用户坏库输出 `a0d89 layoutBlock=22 physicalBlocks=[282] layoutBlockChunkIds=[a1aab]` | 用户样本明确属于“layout 指向旧位置，同 id chunk 已在新位置，旧位置已被其他 chunk 复用”。 |
| `ReproCompactForensics scan-match`，`partialWrite=true` | 命中 `seed=0 stop=131`，样本保存为 `h2/temp/repro/compact-forensics/match-seed-0-stop-131-partial-true.mv.db`；4 个 live chunk layout 指向旧 block，同 id chunk 在其他物理 block | 证明 compact fault injection 能稳定捕获同类“搬迁后 layout 未同步”的损坏形态。 |
| `ReproCompactForensics scan-match`，`partialWrite=false` | 命中 `seed=2 stop=242`，样本保存为 `h2/temp/repro/compact-forensics/match-seed-2-stop-242-partial-false.mv.db`；`chunk 9b layoutBlock=81c physicalBlocks=[a3]` | 不依赖半写也能复现 live chunk 位置错位，更贴近物理盘正常整块写失败/乱序落盘场景。 |
| `ReproCompactForensics scan-overwrite-range` | 已扫 `partial=false seed=0..2 stop=1..500` 和 `partial=true seed=0..2 stop=1..260`，暂未抓到“旧 block 被新 chunk header 覆盖”的严格实验样本 | 用户样本的“旧位被复用覆盖”仍需扩大 workload 或增加更精确 fault hook。 |
| `RecoveryTrace` 真实恢复路径跟踪 | 用户坏库 full scan 从 `26f` 往前时，先发现完整 `82654@1af`，但因中途 `block 248` 被误识别为 `chunk:0,len:0` 伪 header，最终返回 `82622@1aa`；实验样本 `9b@a3` 同理被 `block 45e` 伪 header 清掉候选。 | `discoverChunk()` 的 header 识别过宽，会把普通页面内容或空洞误当成 chunk header，导致完整物理副本没有进入 `validChunksById`，从而让重定向逻辑没有候选。 |
| `RecoveryTrace --strict-discover` 对照实验 | 临时忽略 `id<=0` 或 `len<=0` 的伪 header 后，`partialWrite=false seed=2 stop=242` 样本可 `OPEN_OK`；用户坏库能把 `82654@1af` 纳入 full scan，但 strict-only 仍会在读取 `a0d89` layout 页时报页面长度异常。 | 最小复现已证明“伪 header 清空候选”是一个独立可修复点；用户真实坏库还需要解决“layout 页位于待重定向 chunk 内”的循环依赖。注意：正式修复不能简单拒绝所有 `id=0`，因为 chunk id 理论上可能回绕复用，应重点校验 `len>0` 和必备 header 字段。 |
| `RecoveryTrace --strict-discover --prime-valid-chunks` 对照实验 | 在 strict discover 基础上，候选校验前把已发现的物理 chunk 先放入 `chunks` 缓存，用户坏库和实验样本均可 `OPEN_OK`；用户坏库候选 `a1aac@52` 校验 `197` 个 layout chunk 条目通过。 | 用户坏库不是无解的随机全文件损坏；恢复算法若能先用 full scan/尾部发现的物理 chunk 读取 layout 页，再用 layout 动态元数据校验，就有机会恢复当前版本。该临时实现会碰到 occupancy 元数据语义，不能直接作为最终生产补丁。 |

## 最新进展：2026-05-28

### 诊断工具

临时工具位于 `h2/temp/repro`，不属于生产代码：

- `MVStoreForensics.java`：只读解析 `MVStoreTool.dump()` 输出，报告 file header、layout root、layout chunk 条目、无效 live chunk 引用、同 id 物理副本、layout 指向 block 当前实际 chunk id。
- `ReproCompactForensics.java`：基于 `FilePathReorderWrites` 和真实磁盘文件，围绕 `MVStore.compactFile(1000)` 做 fault injection。
- 新增扫描模式：
  - `scan-match` / `scan-match-range`：只在出现“layout 指向位置无效，但同 id chunk 物理副本存在于别处”时停止。
  - `scan-overwrite` / `scan-overwrite-range`：只在上述条件之外，再满足“layout 指向的旧 block 已有其他 chunk header”时停止。

已验证用户坏库：

```text
INFO_OPEN=ERROR File is corrupted - unable to recover a valid set of chunks [2.2.229/6]
HEADER chunk=a1aac block=52 version=a1aac pos=000000
ROOT key=a1aac:8ce raw=0x286ab0000023385
LAYOUT visitedPages=62 missingPages=0 ambiguousPages=7 chunkEntries=197
LIVE_REFS invalid=1
INVALID_LIVE_REF id=a0d89 layoutBlock=22 livePages=1 physicalBlocks=[282] layoutBlockChunkIds=[a1aab]
```

同时确认用户坏库中 `block 282` 的 `chunk a0d89` header/footer 成对存在：

```text
282000 chunkHeader chunk:a0d89,block:0,len:3,...
+002f80 chunkFooter chunk:a0d89,len:3,version:a0d89,...
```

这说明用户坏库不是简单“只有 header 没 footer”的半写样本，而是更接近 compact 空间复用过程中元数据与物理位置脱节。

### 关键复现结果

`partialWrite=false` 的复现命令：

```powershell
java -cp "h2\temp\repro\classes;h2\build\classes\java\main" ReproCompactForensics scan-match 40 500 false 1
```

命中结果：

```text
CORRUPT seed=2 stop=242 partial=false file=h2/temp/repro/compact-forensics/seed-2-stop-242-partial-false.mv.db
MATCH infoOk=false dumpOk=true headerChunk=c2 headerBlock=9f headerVersion=c2 layoutPages=4 chunkEntries=39 invalidLiveRefs=1 invalidWithPhysicalCopy=1
INVALID_LIVE_REF id=9b layoutBlock=81c livePages=619 physicalBlocks=[a3] layoutBlockChunkIds=[]
```

结论：

- 该样本不依赖 partial write，仍能复现 `unable to recover a valid set of chunks`。
- 损坏核心与用户样本同类：layout 中 live chunk 的 block 是旧位置，同 id chunk 实际已经出现在另一个物理 block。
- 与用户样本差异：该实验样本旧 block 当前没有可识别 chunk header；用户样本旧 block 已经被 `chunk a1aab` 复用覆盖。

`partialWrite=true` 的对照样本：

```powershell
java -cp "h2\temp\repro\classes;h2\build\classes\java\main" ReproCompactForensics scan-match 6 260 true 1
```

命中 `seed=0 stop=131 partial=true`，4 个 live chunk 出现同类位置错位。该样本用于说明故障窗口，但主线优先使用 `partialWrite=false` 样本。

### 真实恢复跟踪结果

新增临时工具 `h2/temp/repro/org/h2/mvstore/RecoveryTrace.java` 和 `BlockScan.java`，只读跟踪 `RandomAccessStore.readStoreHeader()` 的候选选择。关键发现如下：

- 用户坏库的生产恢复路径并不是首先卡在 `a0d89`，而是卡在 layout 中第一个 live 引用 `82654@1af`。
- `BlockScan` 确认 `82654@1af` 的 header/footer 成对完整：`HEADER block=1af chunk=82654 len=c0`，`FOOTER endBlock=26f chunk=82654 block=1af len=c0`。
- 但 `discoverChunk(26f)` 没有返回 `82654@1af`，而是返回前一个 `82622@1aa`。逐步跟踪显示：算法先把 `82654@1af` 设为候选，随后在 `block 248` 把普通页面内容误解析成 `0@248/v0/next0`，触发“候选区间内出现 header 就清空候选”的规则。
- 实验样本 `match-seed-2-stop-242-partial-false.mv.db` 复现同类模式：`9b@a3` header/footer 完整，但 `discoverChunk(465)` 被 `block 45e` 的伪 header 清空候选，最终返回 `c2@9f`。
- 临时 `--strict-discover` 对照路径只忽略 `id<=0` 或 `len<=0` 的伪 header 后，实验样本可直接 `OPEN_OK`。这说明至少有一个最小修复方向成立：`discoverChunk()` 不能把任意可被 `parseMap()` 解析为空 map 的页面内容当成有效 chunk header。正式修复需要兼容 chunk id 回绕，不能仅以 `id==0` 判无效。
- 用户坏库在 strict-only 路径下继续失败，首个错误是读取 `map=0` 的 layout 页：`pos=0x28362400002a8cc chunk=a0d89 offset=0xaa3`。离线 dump 证明物理 `a0d89@282 +000aa3` 是合法 layout leaf，但生产恢复在读它之前先按 layout 旧记录 `a0d89 block=22` 定位，于是读到 `a1aab` 内容并报页面长度异常。
- 临时 `--strict-discover --prime-valid-chunks` 在候选校验前把已发现的物理 chunk 先放进 `chunks` 缓存后，用户坏库 `OPEN_OK`，`a1aac@52` 候选校验 `197` 个 layout chunk 条目通过。该结果确认第二层失败不是页面真实损坏，而是 layout 遍历与 chunk 重定向之间存在循环依赖。

### 当前源码判断

`RandomAccessStore.moveChunk()` 的关键顺序：

1. 从旧 block 读取整个 chunk。
2. 把 chunk 写入新 block。
3. 调用 `free(start, length)` 释放旧空间。
4. 更新内存对象 `chunk.block = block`。
5. 调用 `saveChunkMetadataChanges(chunk)` 把新 block 写入 layout 的内存 map。
6. 之后依赖后续 `store(...)` 把 layout 持久化成新的 chunk 并 `sync()`。

因此存在一个可复现窗口：chunk 已经被搬到新位置，旧空间已释放并可能被复用，但能够描述新位置的 layout chunk 尚未成为可恢复版本。此时崩溃或乱序落盘会留下“物理 chunk 已移动、当前可选 layout 仍指向旧 block”的状态。

`FileStore.findLastChunkWithCompleteValidChunkSet()` 已有“同 id chunk 在其他位置存在时重定向”的恢复逻辑，但用户坏库仍然打不开，说明后续还需要验证以下可能：

- full scan 的 `discoverChunk()` 是否把可用的同 id 物理 chunk 纳入 `validChunksById`。当前已确认：`id=0,len=0` 伪 header 会把完整候选清掉，导致实验样本无法重定向；最终修复应优先校验 `len>0`、字段完整性和 header 形态。
- layout map 遍历过程中是否先读到了依赖错误 block 的 page，导致还没执行到重定向逻辑就抛异常。当前已确认：用户坏库的 `chunk.a0d89` 条目所在 layout leaf 本身就在被搬迁的 `a0d89` chunk 里，必须先临时使用物理 `a0d89@282` 才能读到动态元数据。
- 7 个 ambiguous layout page 是否使恢复算法选择了与 `MVStoreTool.dump()` 离线分析不同的页面副本。
- 重定向只改内存 `c.block`，但后续候选 last chunk 校验仍因动态 occupancy、root page 或其他 live chunk 失败。

## 当前假设优先级

| 优先级 | 假设 | 依据 | 下一步验证 |
| --- | --- | --- | --- |
| P0 | compact 或关闭 compact 中的空间复用导致 live chunk 位置错位 | 坏库中 `chunk.a0d89` layout 指向 `block:22`，而实际在 `block:282`；显式 compact fault injection 已复现相近错误 | 针对 `compactFile()` / `compactStore()` 做精确 fault injection，记录最后 header、layout、chunk 位置。 |
| P1 | 进程在 chunk move、layout 更新、header 更新之间异常退出，恢复算法无法选出有效版本 | `-dump` 显示物理 chunk 完整，但 `readStoreHeader()` 无法恢复有效 chunk 集 | 在关键写入点注入 `halt` / partial write / reorder write。 |
| P1 | `SHUTDOWN COMPACT` 或退出 compact 与业务关闭/连接池回收重叠 | 用户存在 `SHUTDOWN COMPACT` 和退出 compact；trace 中后续打开都从 header 恢复失败 | 搭建 TCP server + 持续业务写入 + `SHUTDOWN COMPACT` + kill/restart 的压力复现。 |
| P1 | `discoverChunk()` 把普通页面或空洞误识别为 `chunk:0,len:0` 伪 header，清空本来完整的候选 chunk | 用户坏库 `82654@1af` 和实验样本 `9b@a3` 均被伪 header 清空；临时 strict discover 可让实验样本 `OPEN_OK` | 把该行为固化为最小单元/回归测试，评估生产修复应要求 header 必须包含合法 `chunk`、`len>0`、`version` 等字段，并确认 chunk id 回绕兼容性。 |
| P1 | layout 遍历和 chunk 重定向存在循环依赖：要读到 `chunk.X` 元数据才能重定向 `X`，但 `chunk.X` 元数据页本身可能就在已搬迁的 `X` 里 | 用户坏库 `a0d89 +000aa3` 是合法 layout leaf；strict-only 按旧 `block=22` 读取失败，strict+prime 后 `OPEN_OK` | 设计只在恢复候选校验期间使用的“物理 chunk 读取视图”，既能读取搬迁后的 layout 页，又不丢失 layout 中的动态 occupancy 元数据。 |
| P2 | `H2DBTool.autoBackup` 的 embedded 连接与 TCP server 共用同 JVM `Database`，在关闭 compact 期间进入失败分支并触发副作用 | 已复现 `90135` exclusive mode；代码中 failedHandler 会被调用 | 在调用方确认 failedHandler 行为；验证是否会删除 token、重启、kill 或触发恢复。 |
| P2 | H2 恢复逻辑虽已有“同 id chunk 重定向”尝试，但在用户坏库形态下仍未成功 | 坏库中真实 `a0d89` 存在于 `block:282`，layout 指向 `block:22`，且 `block:22` 已被 `a1aab` 覆盖；`MVStoreTool -repair` 仍失败 | 继续模拟 `findLastChunkWithCompleteValidChunkSet()` 的候选选择，确认失败发生在 full scan、layout traversal、重定向后校验还是 ambiguous page 选择。 |
| P3 | 连接未关闭、GC close、连接池清理在坏库后放大告警 | trace 中有 `90018` | 作为伴随问题，不作为根因主线。 |

## 故障模式登记表

本节用于记录所有已经排查到、可能导致 `.mv.db` 文件损坏或数据丢失的场景。后续补测试代码时，每个用例应引用这里的编号；修复完成后，也按编号逐项回归。

| 编号 | 类别 | 可能触发损坏的场景 | 当前证据 / 状态 | 后续测试方向 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| C-01 | compact 空间复用 | `moveChunk()` 已把 chunk 写到新 block，但 layout 新位置尚未持久化，进程崩溃或写入乱序后，恢复只能看到仍指向旧 block 的 layout。 | `partialWrite=false seed=2 stop=242` 已复现 `chunk 9b layoutBlock=81c physicalBlocks=[a3]`。 | 新增 compact fault-injection 回归测试，断言 reopen 后不出现 `unable to recover a valid set of chunks`，且 `MVStoreForensics` 无 invalid live ref。 | P0 |
| C-02 | compact 空间复用 | 旧 block 已释放并被其他 chunk 复用覆盖，但当前可恢复 layout 仍引用旧 block。 | 用户坏库已确认 `a0d89 layoutBlock=22 physicalBlocks=[282] layoutBlockChunkIds=[a1aab]`。实验中严格覆盖形态暂未命中。 | 扩大 `scan-overwrite-range` 或增加精确 fault hook，构造“旧 block 被新 chunk header 覆盖”的确定性样本。 | P0 |
| C-03 | compact 写入顺序 | `compactMoveChunks()` 第一阶段移动 chunk 后执行 `store(leftmostBlock, originalBlockCount)` 之前或期间崩溃，导致 move metadata 与 last chunk/header 不一致。 | 由 `RandomAccessStore.java:527` 到 `moveChunk()` 顺序推导；现有 match 样本与该窗口一致。 | 在 `moveChunk()` 后、`store(...)` 前注入失败；记录 header chunk、layout root、物理 chunk 集。 | P0 |
| C-04 | compact 写入顺序 | `store(...)` 已写出部分 layout，但 `sync()` 或后续 header 更新未完成，恢复候选 last chunk 选择到不完整版本。 | `unable to recover a valid set of chunks` 复现样本说明候选集合无法通过校验；具体断点待精确。 | 在 `store(...)` 写 layout root、chunk footer、header 前后分别注入失败。 | P1 |
| C-05 | compact retention | `compactFile()` / `compactStore()` 临时 `setRetentionTime(0)`，可能允许仍被可恢复旧 layout 引用的 chunk 过早进入复用窗口。 | 源码路径明确；用户样本和实验样本均与旧 block 复用/搬迁有关。 | 对比 retention 为 0 与非 0 的 fault-injection 结果，验证是否减少 invalid live ref。 | P1 |
| C-06 | 关闭 compact | `SHUTDOWN COMPACT` 或数据库退出 compact 在业务进程关闭、连接池清理、watchdog kill 之间被中断。 | 用户确认存在 `SHUTDOWN COMPACT` 和退出 compact；JDBC shutdown fault injection 可复现 `90030`。 | TCP server + 持续写入 + `SHUTDOWN COMPACT` + kill/restart 压测。 | P1 |
| C-07 | Query 后置自动回收 | 普通 Query/SQL 执行路径如果挂接 `Database.checkCompact()`，会在业务访问后异步触发 `compactFile()`，扩大和业务写入、TCP 连接生命周期、备份脚本的交叠窗口。 | 用户明确要求移除 Query 会引发回收空间的入口；显式 compact 能力保留。 | 移除 `Database.checkCompact()`、`CompactStatus`、`h2_compact_check_time` 和 `/etc/aio/h2_compact_check_time` 触发链；增加测试确认 Query 路径不再暴露自动回收 hook。 | P2 |
| S-01 | 空间回收 | 关闭自动 compact 后，大量写入再删除会留下很大的 `.mv.db` 文件，即使 live 数据只剩很少。 | `T-NO-AUTO-COMPACT-BLOAT-01` 已稳定复现；`compactFile()` 对纯 dead/free 空间不保证缩文件，离线 full compact 可收缩。 | 保留 bloat 回归和离线 compact 收缩回归；后续讨论产品维护窗口或 H2 层更温和空间回收策略。 | P2 |
| I-01 | 文件系统故障 | 正常整块写失败或写入乱序，不发生半写，也可能留下不可恢复 chunk 集。 | `partialWrite=false seed=2 stop=242` 已复现。 | 保留 `partialWrite=false` 作为主回归模式，避免修复只覆盖 torn write。 | P0 |
| I-02 | 文件系统故障 | 半写 / torn write 发生在 chunk、footer、store header 或 layout page。 | `partialWrite=true seed=0 stop=131` 已复现 4 个 invalid live refs；JDBC shutdown 有 `90030` 对照。 | 用 `partialWrite=true` 覆盖 header、footer、layout root、data page 的恢复能力。 | P1 |
| I-03 | 文件系统故障 | 文件头两份一致但指向的 current chunk 不是可恢复完整集合。 | 用户坏库两份 header 一致指向 `a1aac`，但 `-info` 失败。 | 构造双 header 一致但 layout/live ref 错位的样本，验证恢复算法能回退到更早候选。 | P1 |
| I-04 | 磁盘满 | `No space left on device` 可导致写入失败；本次用户日志中的该项是专项测试噪声，不作为当前根因。 | 用户已明确排除为生产根因；仍属于通用文件损坏风险。 | 作为低优先级磁盘满回归，验证失败后不会留下比原版本更坏的文件。 | P3 |
| I-05 | 存储缓存 / fsync | OS、磁盘缓存或虚拟化环境可能使 `sync()` 语义弱于预期，放大 compact 写入顺序窗口。 | 当前无直接证据；`FilePathReorderWrites` 用于模拟此类行为。 | 后续如支持自定义 FilePath，加入“sync 前后乱序落盘”精确测试。 | P2 |
| R-01 | 恢复算法 | `findLastChunkWithCompleteValidChunkSet()` 有同 id chunk 重定向逻辑，但用户坏库仍失败。 | 用户坏库中 `a0d89` header/footer 成对存在，`MVStoreTool -repair` 仍失败。 | 写只读模拟/单元测试，定位失败在 full scan、layout traversal、重定向后校验还是候选排序。 | P0 |
| R-02 | 恢复算法 | layout map 遍历过程中可能先读取依赖错误 block 的 page，尚未执行到同 id 重定向就失败。 | 用户坏库已确认：`chunk.a0d89` 相关 layout leaf 位于物理 `a0d89@282 +000aa3`，但 layout 旧记录仍指向 `block=22`；strict-only 读取该页失败，strict+prime 后通过。 | 构造 layout page 位于被搬迁 chunk 的样本，验证 traversal 前置重定向或独立物理读取视图。 | P0 |
| R-03 | 恢复算法 | full scan 的 `discoverChunk()` 未把真实物理副本纳入 `validChunksById`，导致重定向逻辑没有候选。 | 用户坏库 `82654@1af` 和实验样本 `9b@a3` 均有完整 header/footer，但分别被 `block 248`、`block 45e` 的伪 header 清空候选；实验样本在临时 strict discover 下可 `OPEN_OK`。 | 固化 `discoverChunk()` 伪 header 回归测试；修复方向是提高 header 合法性校验，至少不能让 `id=0,len=0` 的空 map 清空候选，同时保留 id 回绕兼容性。 | P0 |
| R-04 | 恢复工具 | `MVStoreTool -repair` 对 layout/live chunk 错位样本无法恢复，甚至可能触发 rollback NPE。 | 修复前用户坏库副本已复现 repair 失败和 NPE；修复后错位样本、无可用 chunk 的坏文件和声明长度超过实际文件的不完整 chunk 均不再 NPE。 | 已补 repair 回归测试；是否支持更强离线自动修复文件另行评审。 | P2 |
| R-05 | 恢复算法 | 候选校验前如果把 full scan 找到的物理 chunk 直接塞进 `chunks`，可以读通搬迁后的 layout 页，但可能用 header-only chunk 覆盖 layout 动态元数据。 | 临时 `--strict-discover --prime-valid-chunks` 可打开用户坏库；但该实现可能让 `getChunksFromLayoutMap()` 复用 header-only chunk，丢失 occupancy/livePages 等动态字段。 | 正式修复需要区分“用于读取页面的物理位置候选”和“来自 layout 的权威 chunk 元数据”，不能简单全量替换 `chunks`。 | P0 |
| R-06 | 恢复算法 | recovery 物理 chunk 候选如果只按 `chunk id` 匹配，chunk id 回绕或复用时可能把另一代同 id chunk 当成当前 layout 条目的物理副本。 | 代码审查发现第一版物理视图按 id 建索引；已收紧为 `id + version + len` 匹配，同 id 多世代时禁用无版本信息的 id 兜底。 | 已补 generation match 白盒测试；后续如构造真实 id 回绕文件，可再补端到端样本。 | P2 |
| O-01 | 备份并发 | `H2DBTool.autoBackup` 使用 embedded URL 执行 `SCRIPT DROP TO`，同 JVM 下与 TCP server 共享 `Database`，遇到 shutdown compact 会进入 `90135` exclusive mode。 | 已复现 `90135`，未直接复现损坏；failedHandler 可能触发副作用。 | TCP server + embedded backup + `SHUTDOWN COMPACT` 压测；断言 `90135` 不触发破坏性恢复或 kill。 | P2 |
| O-02 | 备份失败处理 | autoBackup 失败后删除 token、调用 failedHandler；如果 failedHandler 会重启、kill 或自动恢复，可能打断 compact。 | 用户贴出的代码中 failedHandler 可被调用；实际产品行为待确认。 | 用 fake failedHandler 模拟 kill/restart，验证 compact 期间失败处理不会扩大文件损坏。 | P1 |
| O-03 | 自动恢复 | `H2DBTool.autoRestore` 会移动现有 `.mv.db` 后执行 `RUNSCRIPT`；如果在服务仍持有数据库时触发，可能造成运行中 DB 文件被移动或替换。 | 代码层面存在风险；尚未确认生产触发链。 | 搭建 live TCP server + restore 误触发测试；验证文件锁、路径移动和恢复流程边界。 | P2 |
| O-04 | 连接生命周期 | 未关闭连接、GC close、连接池清理与关闭 compact 重叠，可能放大关闭窗口或造成重复打开坏库。 | trace 有 `90018`，当前判断为伴随问题。 | 连接泄漏 + shutdown compact 压测，确保修复后 close 失败不触发二次破坏动作。 | P3 |
| O-05 | 多写者 | `FILE_LOCK=NO`、`nolock:` 或多 JVM embedded 同写同一文件可直接破坏 MVStore。 | `nolock:` 实验可复现 `File corrupted in chunk ... got 0`；用户生产已确认不是该配置。 | 保留负面/防误用测试或文档测试，确保不把该 unsupported 场景误判为当前主线。 | P3 |
| O-06 | 备份一致性 | `SCRIPT DROP TO` 长时间运行时设置 timeout，失败分支可能把临时 SQL、digest 或 token 状态留下不一致。 | 当前未证明会写坏 `.mv.db`，但会影响恢复路径和误判。 | 测试 backup timeout、digest 缺失、SQL 半文件，不应触发覆盖健康库。 | P3 |
| D-01 | 数据丢失但文件可打开 | 文件未报 corrupt，但由于恢复回退到更早 chunk 集，可能丢失最近已提交数据。 | 用户提到“有时是丢部分数据”；目前样本主要是打不开。 | 每个复现用例都写入单调递增 marker，reopen 后校验已确认 commit 的上界和允许回退范围。 | P1 |

### 后续测试代码约束

后续补测试时建议统一使用以下验收标准，避免只验证“能打开”而漏掉静默数据丢失：

- 每个 fault-injection 用例都记录 seed、stop、partialWrite、最后一次成功 commit 版本和 marker。
- reopen 后必须同时做 `MVStoreTool -info`、业务数据 marker 校验、layout/live chunk 校验。
- 对 compact 相关测试，至少覆盖 `partialWrite=false` 和 `partialWrite=true` 两类；主线优先保证 `partialWrite=false`。
- 对 JDBC / TCP server 测试，业务连接必须走 TCP；embedded 连接只用于模拟 `H2DBTool.autoBackup` 或误用场景。
- 对恢复算法测试，断言不能只是不抛异常，还要确认没有 invalid live ref、没有错误选择旧 layout page、没有超过预期的数据回退。
- 所有测试类和工具保持 JDK 8 兼容；临时 fault hook 进入正式测试前要避免依赖本机绝对路径。

建议后续测试编号：

| 测试编号 | 覆盖故障模式 | 预期断言 |
| --- | --- | --- |
| T-COMPACT-MOVE-01 | C-01、I-01、D-01 | `partialWrite=false` 下 compact 中断后 reopen 成功，已确认 commit 不丢，forensics 无 invalid live ref。 |
| T-COMPACT-OVERWRITE-01 | C-02、C-03、R-01 | 构造旧 block 被复用覆盖的样本，恢复能选择有效 chunk 集或明确回退到可读版本。 |
| T-COMPACT-STORE-ORDER-01 | C-03、C-04、I-03 | 在 `moveChunk()`、`store(...)`、chunk footer、store header 前后注入失败，验证候选 last chunk 可恢复或安全回退。 |
| T-COMPACT-RETENTION-01 | C-05 | 对比 retention 策略，验证修复后不会过早复用仍可能被旧 layout 引用的 chunk。 |
| T-SHUTDOWN-COMPACT-01 | C-06、O-04 | TCP 业务写入中执行 `SHUTDOWN COMPACT` 并注入退出，重启后无 `90030`。 |
| T-QUERY-NO-AUTO-COMPACT-01 | C-07 | 普通 Query/SQL 路径不再暴露 `Database.checkCompact()` 自动回收 hook，关闭 auto compact 参数下 marker 仍可正常查询和重开读取。 |
| T-NO-AUTO-COMPACT-BLOAT-01 | S-01 | 关闭自动 compact 后，大量写入再删除只剩 marker，`.mv.db` 文件仍保持明显膨胀且 fill rate 很低。 |
| T-OFFLINE-COMPACT-SHRINK-01 | S-01 | 对同一膨胀样本执行离线 full compact，生成的新文件明显缩小且 marker 可读。 |
| T-BACKUP-COMPACT-01 | O-01、O-02 | embedded `SCRIPT DROP TO` 遇到 exclusive mode 不触发破坏性 failedHandler。 |
| T-BACKUP-FAILED-HANDLER-01 | O-02 | backup 失败、exclusive mode、timeout 等场景下 failedHandler 不应在 compact 窗口触发破坏性 kill/restart/restore。 |
| T-BACKUP-TIMEOUT-CONSISTENCY-01 | O-06 | backup timeout、digest 缺失、SQL 半文件时，不覆盖健康库，不留下会误导 autoRestore 的一致性状态。 |
| T-RESTORE-LIVE-01 | O-03 | live server 状态下误触发 restore 不允许移动或替换正在使用的 `.mv.db`。 |
| T-CONNECTION-LIFECYCLE-01 | O-04 | 连接泄漏、GC close、连接池关闭与 shutdown compact 重叠时，不触发二次破坏动作，错误日志可诊断。 |
| T-RECOVERY-REPOINT-01 | R-01、R-02、R-03 | 同 id chunk 物理副本存在时，恢复路径能重定向或安全回退。 |
| T-DISCOVER-FALSE-HEADER-01 | R-03 | 在完整 chunk 数据区内部构造普通页面或空洞可被解析成 `chunk:0,len:0` 的内容，full scan 不应因此丢弃 header/footer 完整的外层 chunk。 |
| T-DISCOVER-ID-WRAP-01 | R-03 | 如果构造或模拟合法 `chunk:0,len>0` 的回绕场景，header 校验不应误伤合法 chunk；伪 header 测试应主要依赖 `len=0` / 字段缺失等条件。 |
| T-RECOVERY-GENERATION-MATCH-01 | R-06 | recovery 物理 chunk 读取视图必须匹配 `id + version + len`，不能把同 id 但不同版本的物理 chunk 用作重定向目标。 |
| T-RECOVERY-LAYOUT-CYCLE-01 | R-02、R-05 | layout leaf 位于被搬迁 chunk 内时，恢复候选校验应能先用物理副本读取 layout 页，再用 layout 动态元数据完成重定向校验。 |
| T-RECOVERY-PHYSICAL-VIEW-01 | R-05 | 恢复期间的物理 chunk 读取视图不能用 header-only chunk 覆盖 layout 中的 livePages、occupancy 等动态元数据。 |
| T-REPAIR-ROBUST-01 | R-04 | `MVStoreTool -repair` 对错位样本、短坏文件和不完整 chunk 不 NPE，并输出可诊断结果。 |
| T-DISK-FULL-01 | I-04 | 磁盘满写失败后，旧版本仍可打开，错误路径不覆盖健康文件。 |
| T-TORN-WRITE-01 | I-02 | chunk、footer、store header、layout page 半写后，恢复能打开旧版本或给出明确可诊断错误，不扩大损坏。 |
| T-FSYNC-REORDER-01 | I-05 | 模拟 sync 前后写入乱序或弱 fsync 语义，验证 compact 写入顺序窗口不会留下不可恢复 chunk 集。 |
| T-UNSUPPORTED-MULTIWRITER-01 | O-05 | 多写者 unsupported 场景作为负面样本保留，用于区分生产主线。 |
| T-DATA-MARKER-REGRESSION-01 | D-01 | 所有恢复类测试统一写入单调 marker，reopen 后校验已确认 commit 上界，防止静默数据丢失。 |

### 已落地测试入口

- `h2/src/test/org/h2/test/store/TestMVStoreRecoveryCorruption.java`
  - 覆盖本表登记的所有测试编号：`T-COMPACT-MOVE-01`、`T-TORN-WRITE-01`、`T-COMPACT-OVERWRITE-01`、`T-COMPACT-STORE-ORDER-01`、`T-COMPACT-RETENTION-01`、`T-FSYNC-REORDER-01`、`T-DISK-FULL-01`、`T-RECOVERY-REPOINT-01`、`T-DISCOVER-FALSE-HEADER-01`、`T-DISCOVER-ID-WRAP-01`、`T-RECOVERY-GENERATION-MATCH-01`、`T-RECOVERY-LAYOUT-CYCLE-01`、`T-RECOVERY-PHYSICAL-VIEW-01`、`T-REPAIR-ROBUST-01`、`T-SHUTDOWN-COMPACT-01`、`T-QUERY-NO-AUTO-COMPACT-01`、`T-NO-AUTO-COMPACT-BLOAT-01`、`T-OFFLINE-COMPACT-SHRINK-01`、`T-BACKUP-COMPACT-01`、`T-BACKUP-FAILED-HANDLER-01`、`T-BACKUP-TIMEOUT-CONSISTENCY-01`、`T-RESTORE-LIVE-01`、`T-CONNECTION-LIFECYCLE-01`、`T-UNSUPPORTED-MULTIWRITER-01`、`T-DATA-MARKER-REGRESSION-01`。
  - 主要 compact 样本参数：整块写 `seed=2`、`powerFailureCountdown=242`、`partialWrite=false`；半写样本 `seed=1`、`powerFailureCountdown=142`、`partialWrite=true`。
  - 默认运行时作为正式修复验收测试，修复实现后当前版本已通过，并校验 `data[-1]` marker 或 JDBC `MARKER` 表未丢。
  - 带 `-Dh2.test.mvStoreRecoveryCorruption.characterize=true` 运行时，修复前用于稳定复现已知恢复失败和 `MVStoreTool.repair` NPE；修复实现后当前版本也已通过。
  - 带 `-Dh2.test.mvStoreRecoveryCorruption.keepFiles=true` 可保留生成的 `.mv.db` 样本用于离线 dump。
  - 当前测试已把所有已登记风险映射到可执行入口；其中 `H2DBTool.autoBackup/autoRestore` 不在本仓库内，相关用例用 H2 侧 TCP、embedded backup、exclusive mode、坏 SQL、live 文件移动等边界模拟风险，正式接入产品模块时仍需补模块级测试。
  - Gradle 入口：在 `h2/` 目录执行 `.\gradlew.bat runMvStoreRecoveryCheck`。PowerShell 下运行当前已知失败复现模式时执行 `.\gradlew.bat "-Dh2.test.mvStoreRecoveryCorruption.characterize=true" runMvStoreRecoveryCheck`。

### 故障模式测试覆盖追踪表

本表用于确保“所有排查到可能导致文件损坏的情况都要补测试代码”。新增故障模式时必须同步更新本表；正式修复合入前，P0/P1 项必须至少达到“测试已实现并失败可复现 / 修复后通过”的状态。

| 故障模式 | 对应测试编号 | 当前状态 | 关闭条件 |
| --- | --- | --- | --- |
| C-01 | T-COMPACT-MOVE-01、T-DATA-MARKER-REGRESSION-01 | 已补修复验收测试 `TestMVStoreRecoveryCorruption`；当前默认模式和 characterize 模式均通过 | 正式测试能稳定复现 compact move 窗口，并在修复后通过。 |
| C-02 | T-COMPACT-OVERWRITE-01、T-RECOVERY-LAYOUT-CYCLE-01 | 已补修复验收入口，当前已通过；用户坏库已证明“旧位置被其他 chunk 占用”形态 | 构造旧 block 被复用覆盖样本，验证恢复可读或安全回退。 |
| C-03 | T-COMPACT-MOVE-01、T-COMPACT-STORE-ORDER-01 | 已补修复验收入口，覆盖 compact move 关键 stop 点，当前已通过 | 覆盖 `moveChunk()` 后、`store(...)` 前后的失败注入点。 |
| C-04 | T-COMPACT-STORE-ORDER-01 | 已补修复验收入口，覆盖 layout/header/footer 写出顺序窗口，当前已通过 | 覆盖 layout、footer、header 部分写出或乱序写出后的候选恢复。 |
| C-05 | T-COMPACT-RETENTION-01 | 已补修复验收入口，对比 retention 非 0 时 compact 搬迁失败窗口，当前已通过 | 验证 retention 策略不会过早复用仍可能被旧 layout 引用的 chunk。 |
| C-06 | T-SHUTDOWN-COMPACT-01、T-DATA-MARKER-REGRESSION-01 | 已补 TCP `SHUTDOWN COMPACT` 边界入口，characterize 模式通过 | TCP 写入中 shutdown compact 注入退出后可恢复且 marker 不越界丢失。 |
| C-07 | T-QUERY-NO-AUTO-COMPACT-01 | 已补 Query 后置自动回收 hook 移除验收入口，验证 `Database.checkCompact()` 不再存在，普通查询和 marker 重开读取正常 | Query/SQL 路径不再触发自动空间回收；显式 compact 入口单独验证。 |
| S-01 | T-NO-AUTO-COMPACT-BLOAT-01、T-OFFLINE-COMPACT-SHRINK-01 | 已补空间膨胀复现和离线 compact 收缩验收入口，当前已通过 | 关闭自动 compact 时文件可明显膨胀；离线 full compact 能收缩并保留数据。 |
| I-01 | T-COMPACT-MOVE-01、T-FSYNC-REORDER-01 | 已补 compact move 修复验收入口；非 compact 乱序写入入口作为对照通过 | 整块写失败或乱序落盘下恢复行为可验证。 |
| I-02 | T-TORN-WRITE-01 | 已补修复验收入口，`partialWrite=true seed=1 stop=142` 当前已通过 | 半写覆盖 header/footer/layout/data page 后有确定断言。 |
| I-03 | T-COMPACT-STORE-ORDER-01 | 已补修复验收入口，当前已通过 | 双 header 一致但 current chunk 不完整时能回退或报可诊断错误。 |
| I-04 | T-DISK-FULL-01 | 已补磁盘满模拟边界入口；用户已说明非当前主线 | 磁盘满不覆盖健康版本，错误路径可诊断。 |
| I-05 | T-FSYNC-REORDER-01 | 已补非 compact 写入乱序对照入口 | 弱 fsync / 写入重排模拟下无不可恢复 chunk 集。 |
| R-01 | T-RECOVERY-REPOINT-01 | 已补修复验收入口，当前已通过 | 同 id 物理副本存在时能重定向或安全回退。 |
| R-02 | T-RECOVERY-LAYOUT-CYCLE-01 | 已补修复验收入口，当前已通过；用户样本已证明 layout 读取循环依赖 | layout leaf 位于搬迁 chunk 内时可打破读取循环依赖。 |
| R-03 | T-DISCOVER-FALSE-HEADER-01、T-DISCOVER-ID-WRAP-01 | 已补伪 header 修复验收入口和 id 回绕边界入口，当前已通过；独立二进制定点样本仍可后续加强 | 伪 header 不清空完整候选，同时兼容合法 id 回绕。 |
| R-04 | T-REPAIR-ROBUST-01 | 已补修复验收入口，当前已通过；覆盖错位样本、无可用 chunk 坏文件和不完整 chunk，未再复现 `MVStoreTool.repair` NPE | repair 不 NPE，并输出可诊断结果。 |
| R-05 | T-RECOVERY-PHYSICAL-VIEW-01、T-RECOVERY-LAYOUT-CYCLE-01 | 已补修复验收入口，当前已通过；临时工具已验证方向 | 物理读取视图可读 layout 页，但不覆盖 layout 动态元数据。 |
| R-06 | T-RECOVERY-GENERATION-MATCH-01 | 已补白盒修复验收入口，当前已通过；物理读取视图对已知 layout 元数据按 `id + version + len` 匹配，同 id 多世代时禁用无版本信息的 id 兜底 | 同 id 不同版本的 chunk 不会被当成当前 layout 条目的物理副本。 |
| O-01 | T-BACKUP-COMPACT-01 | 已补 H2 侧 TCP + embedded backup exclusive mode 边界入口；characterize 模式通过 | embedded backup 遇到 shutdown compact 不破坏数据库。 |
| O-02 | T-BACKUP-FAILED-HANDLER-01、T-BACKUP-COMPACT-01 | 已补 exclusive mode / backup 失败边界入口；产品 `failedHandler` 破坏性动作需在引用模块补测 | failedHandler 在 compact 窗口不触发破坏性动作。 |
| O-03 | T-RESTORE-LIVE-01 | 已补 live `.mv.db` 文件移动边界入口；characterize 模式通过 | live server 状态下 restore 不移动/替换正在使用的数据库文件。 |
| O-04 | T-CONNECTION-LIFECYCLE-01、T-SHUTDOWN-COMPACT-01 | 已补连接泄漏 + shutdown compact 边界入口；characterize 模式通过 | 连接生命周期异常不触发二次损坏动作。 |
| O-05 | T-UNSUPPORTED-MULTIWRITER-01 | 已补负面边界入口，验证未关闭锁时第二写者被拒绝 | unsupported 多写者场景作为负面样本保留并可区分。 |
| O-06 | T-BACKUP-TIMEOUT-CONSISTENCY-01 | 已补坏 SQL / 半备份状态不破坏健康库入口；真正 SQL timeout 可在产品模块继续补测 | backup timeout/半文件/digest 缺失不误导恢复。 |
| D-01 | T-DATA-MARKER-REGRESSION-01 | 已补 MVStore marker 与 JDBC marker 校验，所有恢复/边界场景都复用 marker 思路 | 所有恢复类测试都校验 marker，防止静默数据丢失。 |

## 复现计划

### 阶段 1：固化坏库诊断脚本

目标：把手工 `MVStoreTool -dump` 分析变成可重复的只读诊断工具。

交付物：

- 临时脚本或 Java harness，输入 `.mv.db`，输出：
  - file header 指向的 chunk/version/block
  - 从 last chunk meta root 遍历到的 layout chunk 列表
  - live chunk 引用与物理 chunk header/footer 的一致性
  - 同 id chunk 是否存在其他物理副本
- 不修改原始 `.mv.db`。

验收：

- 对当前坏库输出 `a0d89 block:22` 无效、`a0d89 block:282` 存在。

### 阶段 2：compact fault injection 最小复现

目标：复现 layout 指向旧 block、物理 chunk 已被新 chunk 覆盖或搬迁的状态。

建议实验矩阵：

| 变量 | 取值 |
| --- | --- |
| 写入模式 | MVStore API、JDBC 表写入 |
| compact 类型 | `compactFile(maxCompactTime)`、`SHUTDOWN COMPACT`、数据库退出 compact |
| 文件行为 | 正常写、乱序写、指定第 N 次写后 halt、指定 chunk/footer/header 后 halt |
| retention | 默认 retention、compact 中 `setRetentionTime(0)` |
| reopen 检查 | `MVStoreTool -info`、JDBC `SELECT COUNT(*)`、全表校验、layout/live chunk 校验 |

重点断点：

- 新 chunk 已写入，但 layout 尚未稳定。
- layout 已写入，但 store header 尚未稳定。
- old chunk 被复用/覆盖后，当前 layout 仍引用旧 block。
- `writeCleanShutdown()` 前后。

### 阶段 3：TCP server + shutdown compact 复现

目标：贴近用户生产拓扑，不再依赖 `FILE_LOCK=NO`。

场景：

1. 启动 H2 TCP server，业务连接只走 TCP。
2. 多线程持续写入和校验固定表。
3. 周期执行 `SCRIPT DROP TO` 模拟 `H2DBTool.autoBackup`。
4. 随机执行 `SHUTDOWN COMPACT` 或模拟服务退出 compact。
5. 在 compact 关键窗口注入进程 halt、线程中断、连接关闭、重启。

验收：

- 复现 `90030 File corrupted while reading record`。
- 最好复现 `unable to recover a valid set of chunks` 或 layout/live chunk 错位。
- 记录每轮 seed、写入次数、最后成功 SQL、最后 chunk header、文件头。

### 阶段 4：修复方案验证

候选修复方向：

| 方向 | 内容 | 验证方式 |
| --- | --- | --- |
| 降低 compact 风险 | 关闭或限制生产 `SHUTDOWN COMPACT` / 退出 compact；避免在业务运行期 compact | 压测中不再出现 layout 错位。 |
| 调整 compact retention | 审查 `setRetentionTime(0)` 是否允许过早复用仍可能被当前 layout 引用的 chunk | fault injection 对比默认 retention 与 0 retention。 |
| 增强恢复 | 当 layout 指向的 live chunk 位置无效，但同 id chunk 的其他有效物理副本存在时，尝试重定向 | 对坏库副本验证是否可只读打开或 dump 出 SQL。 |
| 增强关闭流程 | compact/关闭期间禁止 backup failedHandler 触发重启或二次恢复；区分 `90135` 正常独占态 | TCP + backup + shutdown compact 压测。 |
| 增强诊断 | 在 trace 中输出 header chunk、候选 last chunk、invalid live chunk id/block | 故障后能直接定位错位 chunk。 |

## 当前不建议做的事

- 不要直接在生产库上执行 `MVStoreTool -repair`；当前样本证明它修不好这种损坏，还可能产生 `.temp/.back` 等副作用。
- 不要把 `No space left on device` 当成本次主线根因；用户已说明这是测试噪声。
- 不要为了“先止血”开启 `FILE_LOCK=NO` 或允许多进程 embedded 写同一文件。
- 不要在业务运行期随意执行 `SHUTDOWN COMPACT` 验证；必须用副本或临时库。

## 继续排查的快速入口

从仓库根目录 `D:\work\java\h2db` 开始：

```powershell
git status --short
```

确认没有非预期生产代码改动。

```powershell
.\gradlew.bat compileJava
```

如需验证当前 H2 代码仍可编译，进入 `h2/` 工程或按当前仓库 Gradle 入口执行。

```powershell
java -cp h2\build\classes\java\main org.h2.mvstore.MVStoreTool -info "C:\Users\admin\Documents\WXWork\1688850767058383\Cache\File\2026-05\fspool_db.mv.db.20260509102112"
```

修复前该命令可只读复现 `unable to recover a valid set of chunks`；修复实现后当前版本应输出 chunk 信息。

```powershell
java -cp h2\build\classes\java\main org.h2.mvstore.MVStoreTool -dump "C:\Users\admin\Documents\WXWork\1688850767058383\Cache\File\2026-05\fspool_db.mv.db.20260509102112"
```

只读 dump 物理 chunk。重点关注：

- `000000 fileHeader ... chunk:a1aac ... block:52`
- `052000 chunkHeader chunk:a1aac`
- layout 中 `chunk.a0d89 = ... block:22 ... livePages:1`
- `022000 chunkHeader chunk:a1aab`
- `282000 chunkHeader chunk:a0d89`

```powershell
javac -cp "h2\build\classes\java\main;h2\temp\repro\classes" -d h2\temp\repro\classes h2\temp\repro\org\h2\mvstore\RecoveryTrace.java h2\temp\repro\org\h2\mvstore\BlockScan.java
```

编译临时只读诊断工具。

```powershell
java -cp "h2\temp\repro\classes;h2\build\classes\java\main" org.h2.mvstore.RecoveryTrace "C:\Users\admin\Documents\WXWork\1688850767058383\Cache\File\2026-05\fspool_db.mv.db.20260509102112" 26f
```

跟踪生产 `discoverChunk()` 为什么从 `26f` 往前没有返回完整的 `82654@1af`。当前关键输出应包含：

- `DEBUG_DISCOVER_CANDIDATE candidate=82654@1af`
- `DEBUG_DISCOVER_HEADER_IN_MIDDLE block=248 header=0@248`
- `FULL_DISCOVER from=26f found=82622@1aa`

```powershell
java -cp "h2\temp\repro\classes;h2\build\classes\java\main" org.h2.mvstore.RecoveryTrace h2\temp\repro\compact-forensics\match-seed-2-stop-242-partial-false.mv.db --strict-discover
```

验证最小复现样本在临时 strict discover 下是否仍可 `OPEN_OK`。该命令只验证修复方向，不代表生产代码已修复。

```powershell
java -cp "h2\temp\repro\classes;h2\build\classes\java\main" org.h2.mvstore.RecoveryTrace "C:\Users\admin\Documents\WXWork\1688850767058383\Cache\File\2026-05\fspool_db.mv.db.20260509102112" --strict-discover --prime-valid-chunks
```

验证用户坏库在“严格发现 chunk + 候选校验前预置物理 chunk”临时路径下是否 `OPEN_OK`。当前结果为 `TRACE_CANDIDATE afterFullScan=false rank=1 chunk=a1aac@52 ... checked=197 ... reason=ok` 和 `OPEN_OK`。

## 开放问题

- 生产中的退出 compact 具体由哪段业务生命周期触发，是否与 watchdog/restart 管理器重叠？
- `H2DBTool.autoBackup` 的 `failedHandler` 在实际产品中会做什么？是否会触发节点下线、进程退出、删除 token、自动恢复？
- 损坏前后是否有服务异常退出、kill、系统重启、断电、杀进程、容器停止超时？
- Query 后置自动回收入口已移除后，是否所有运行实例和历史版本都已升级到同一行为？
- 是否存在定期 `SHUTDOWN COMPACT` 或人工维护脚本在业务仍有连接时执行？
- 是否有更多坏库样本可验证是否都存在“live chunk 引用错位”。
- 正式修复中如何实现“恢复期间的物理 chunk 读取视图”，既打破 layout 遍历循环依赖，又不让 header-only chunk 覆盖 layout 动态元数据？
- `discoverChunk()` 的 header 合法性校验如何兼容 chunk id 回绕到 `0` 的理论场景？

## 工作约束

- 讨论出修复结论前，不修改 `h2/src/main` 生产代码。
- 所有复现实验优先放在 `h2/temp/repro`，并确保不进入提交。
- 对用户提供的坏库只做只读分析；任何 repair、rollback、指针修正实验都必须先复制副本。
- 新增生产修复前必须补复现实验或回归测试，并至少执行最小编译验证。
