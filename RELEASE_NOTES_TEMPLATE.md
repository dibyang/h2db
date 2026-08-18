# h2db 2.3.1

[English](RELEASE_NOTES_TEMPLATE.en.md)

## 摘要

本次 2.3.1 是 MVStore 稳定性修复版本，重点修复在线空间回收与关闭并发时的生命周期竞态，以及废弃 chunk 元数据物理区间重叠导致数据库无法重新打开的问题。

## 兼容性

本 release 继续保持 H2 嵌入式与服务端数据库模型，Maven 坐标为 `net.xdob.h2db:h2db:2.3.1`。本版本不改变 SQL、JDBC API 或 MVStore 磁盘格式，2.3.0 数据库可直接升级使用。

## 变更

* 修复 MVStore 在线空间回收与 store close 的生命周期竞态；关闭开始后不再允许启动新的回收写入。
* 在重建空闲空间位图前校验所有已分配 chunk 的物理区间；即使存在 clean shutdown 标记，也不会跳过重叠检查。
* 对只涉及废弃且无存活页 chunk 的重叠元数据进入恢复路径，避免打开数据库时出现重复空闲空间标记错误。
* 增加重叠废弃 chunk、存活 chunk 冲突和在线回收/关闭并发的确定性回归测试。

## 安全

本 release 未声明单独安全修复。发布凭据、GPG 私钥和 staging secrets 不应提交到仓库。

## 存储与恢复说明

MVStore 空间回收能力仍是实验性维护 API，不改变 SQL 入口，也不自动调度。符合条件的旧损坏文件在可写打开并正常关闭后可固化恢复后的元数据；涉及存活数据区间重叠或无法确认一致性的文件仍会拒绝打开，不能把本修复视为通用数据库损坏修复工具。升级或修复前仍应保留原始文件备份。

## SQL 与 JDBC 说明

本版本没有 SQL 语义或 JDBC API 行为变更。

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.3.1</version>
</dependency>
```

## 验证

主要验证命令：

```powershell
cd h2
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runH2LegacySmoke
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat runMvStoreReclamationJUnitCheck
.\gradlew.bat runH2TestAllCi
.\gradlew.bat --rerun-tasks runLongRunJUnitCheck
.\gradlew.bat --rerun-tasks longRunTestDistZip longRunTestDistTar
```

### LongRun 验收

| Profile | 命令 | 结果 | 关键指标 |
| --- | --- | --- | --- |
| comprehensive 12h | `java -jar h2-longrun.jar -c config/comprehensive.properties` | PASS | 1,776,387,749 次操作，58 次 reopen 检查，23 次 recovery 检查，4,308 次回收全部成功，0 warnings，0 suspicious log lines。 |

## 已知问题

* MVStore 空间回收能力是实验性维护 API，不暴露 SQL，不自动调度。
* 自动恢复仅适用于能够确认废弃、无存活页的重叠 chunk；存活区间重叠仍按损坏拒绝打开。
* 2.3.1 能处理本次已固化的重叠废弃 chunk 故障，不保证自动修复其他类型的文件截断、页损坏或存储介质错误。
