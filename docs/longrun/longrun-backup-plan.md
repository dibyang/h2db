# LongRun Backup Plan (same-process execution)

## 目标

在同一个 `h2-longrun` JVM 内并发执行备份，而不启用独立脚本进程。  
读写压力使用 TCP server 连接，备份连接使用文件模式 JDBC URL（与数据库文件目录一致）执行 `SCRIPT TO`。

## 方案

1. `WorkloadRunner` 在测试循环启动时创建 `JdbcBackupCoordinator` 并在截止时间前周期触发备份。
2. `SqlWorkload` 使用 `org.h2.tools.Server` 启动 TCP server，并把真正的读写连接改为 `jdbc:h2:tcp://127.0.0.1:<port>/<dbName>;MODE=REGULAR`。
3. 备份线程通过 `workload.getJdbcUrl()` 获取文件模式 URL（例如 `jdbc:h2:/abs/path/sql-longrun;MODE=REGULAR`），执行：
   - `SCRIPT TO '<backup-file>'`
   - 产生日志 `.md5`
   - 按保留数清理旧备份
4. 备份任务失败会记录到主循环，`backup.failOnError=false` 时只告警；`true` 时中断测试。

## 配置项

- `backup.enabled=false|true`（默认 `false`）
- `backup.interval=5m`（默认 `5m`）
- `backup.maxRetained=10`（默认 `10`）
- `backup.failOnError=false|true`（默认 `false`）

### 备份目录

固定为 `workDir/backup`，例如 `work/soak-30d/backup`。

## 当前限制

- 对 `MVStoreWorkload`：读写仍走嵌入式访问，备份使用文件模式 URL；需要继续观察与并发备份带来的兼容性表现。
- 目前不再使用 `AUTO_SERVER=TRUE`，也不再回退到 `org.h2.tools.Backup` 文件级备份。

## Soak 示例

`h2/src/longrun/resources/soak-30d.properties`:

```properties
backup.enabled=true
backup.interval=5m
backup.maxRetained=10
backup.failOnError=false
```
