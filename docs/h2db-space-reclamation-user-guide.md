# H2 MVStore 空间回收维护 API 用户指南（实验）

## 定位

`MVStoreSpaceReclamation` 是面向受控维护窗口的 MVStore 空间回收 API。它用于回收关闭自动 compact 或大量删除后留下的 `.mv.db` 文件膨胀空间。

当前能力不暴露 SQL，不自动调度，不改变 `.mv.db` 磁盘格式。外部用户可以在明确关闭数据库或进入维护窗口后，通过 Java API 显式触发。

代码层公开状态：

- `MVStoreSpaceReclamation.getApiStatus()`：当前返回 `EXPERIMENTAL_MAINTENANCE_API`。
- `MVStoreSpaceReclamation.getEntryPoint()`：当前返回 `JAVA_MAINTENANCE_API`。

这两个值用于让集成方在启动检查、发布说明或诊断页面中明确显示当前能力的稳定级别和入口形态。

## 适用场景

- `.mv.db` 文件明显大于实际 live 数据。
- 应用可以安排维护窗口，暂停写入并确保没有其他进程打开同一个数据库文件。
- 希望在不导出/导入业务数据的情况下重写 MVStore 文件。
- 需要可诊断、可回滚的 shadow compact / full-copy 维护路径。

## 不适用场景

- 不能作为 `.mv.db` 文件损坏 root cause 修复手段。
- 不支持 `FILE_LOCK=NO`、`nolock:` 或多 JVM embedded 同写。
- 不适合在业务写入持续进行时无条件切换 prepared shadow。
- 当前版本不提供真正的版本扫描增量追平；源文件变化时默认拒绝切换。

## 基本用法

```java
MVStoreSpaceReclamationResult result = MVStoreSpaceReclamation.compactClosedStore(
        fileName,
        MVStoreSpaceReclamationOptions.builder()
                .keepBackup(true)
                .build());
```

`compactClosedStore()` 会生成 shadow 文件、校验 shadow、备份源文件、切换文件，并在失败时优先恢复备份。

## 分阶段用法

如果调用方希望把生成 shadow 和最终切换拆开：

```java
MVStoreSpaceReclamation.compactToShadow(fileName, MVStoreSpaceReclamationOptions.DEFAULT);
MVStoreSpaceReclamationAnalysis analysis = MVStoreSpaceReclamation.analyzePreparedShadow(fileName);
if (analysis.isSourceUnchanged()) {
    MVStoreSpaceReclamation.switchToShadow(fileName, MVStoreSpaceReclamationOptions.DEFAULT);
}
```

如果 shadow 生成后源文件发生变化，默认 `switchToShadow()` 会拒绝切换，避免静默丢掉新增数据。确认可以接受维护态 full-copy 降级时，可显式开启：

```java
MVStoreSpaceReclamation.switchToShadow(
        fileName,
        MVStoreSpaceReclamationOptions.builder()
                .refreshShadowIfSourceChanged(true)
                .build());
```

## 诊断输出

`MVStoreSpaceReclamationResult` 提供以下诊断信息：

- `getSourceSize()`
- `getCompactedSize()`
- `getSavedBytes()`
- `getSavedPercent()`
- `getDiagnosticSummary()`

建议在 GitHub Release 或 Maven Central 发布说明中提示用户保留该摘要，便于定位失败阶段、文件大小变化和是否发生替换。

如果需要把阶段事件接入应用日志，可设置诊断监听器：

```java
MVStoreSpaceReclamationOptions options = MVStoreSpaceReclamationOptions.builder()
        .diagnosticListener(event -> {
            System.out.println(event.getPhase() + " " + event.getMessage());
        })
        .build();
```

事件阶段由 `MVStoreSpaceReclamationPhase` 表示，当前包括 `PREPARING`、`VERIFYING`、`SHADOW_READY`、`FALLBACK_FULL_COPY`、`SWITCHING`、`COMPLETED` 和 `ABORTED`。监听器不应抛出异常；如果监听器抛出运行时异常，该异常会被忽略，避免诊断系统反向影响维护操作。

## 残留文件和恢复

维护过程中可能出现以下文件：

- `<db>.mv.db.reclaim.shadow`
- `<db>.mv.db.reclaim.backup`
- `<db>.mv.db.reclaim.manifest`

如果进程在切换前后中断，调用：

```java
MVStoreSpaceReclamation.recover(fileName);
```

如果源库存在且只需要清理残留，调用：

```java
MVStoreSpaceReclamation.cleanUp(fileName);
```

## 发布建议

GitHub Release 和 Maven Central 面向外部用户发布时，应明确标注：

- 该 API 属于实验性维护入口。
- 必须在受控维护窗口使用。
- 默认不自动追平 copy 期间写入。
- 源库变化默认拒绝切换；显式开启 `refreshShadowIfSourceChanged` 才会降级 full-copy。
- 发布包不改变 `.mv.db` 格式，回滚到旧版本后已完成切换的新文件仍按普通 MVStore 文件打开。

## 验证命令

维护该能力时至少运行：

```powershell
.\gradlew.bat compileJava
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
```
