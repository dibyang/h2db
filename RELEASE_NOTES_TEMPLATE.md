# h2db 2.4.0

[English](RELEASE_NOTES_TEMPLATE.en.md)

## 摘要

本次 release 的重点是协调式在线备份与影子恢复、DML 快路径 V1，以及跨执行器、存储、网络和工具进程的生命周期可靠性。在线备份通过数据库操作门禁、持久化数据库身份、prepared snapshot、参与者协调、原子 bundle、影子校验和 generation 激活栅栏，保证数据库与外部持久化参与者的一致性。

## 兼容性

本 release 继续保持 H2 嵌入式与服务端数据库模型，Maven 坐标为 `net.xdob.h2db:h2db:2.4.0`。已验证 2.3 数据库文件、传统 zip 备份、2.3 API 插件，以及新旧版本之间的常规 JDBC 和传统备份路径。

协调式在线备份默认关闭，并在数据库打开时固定。启用后会写入内部数据库身份元数据；此后不得再使用 2.3.x 对该数据库执行写操作。旧数据库可继续使用传统备份，或通过 clone onboarding 接入协调式在线备份。

## 变更

* 增加协调式在线备份与影子恢复：operation gate、数据库 identity/schema epoch、MVStore prepared snapshot、参与者 SPI、原子 manifest、shadow validation、generation token 与 activation fence。
* 增加 TCP 在线备份控制协议 v21/v22；旧客户端仍可走常规 JDBC 和传统备份。
* 增加 provider 认证规范、故障矩阵、性能报告及中英文在线备份运维手册。
* 完成实验性 DML 快路径 V1，覆盖简单 `INSERT ... VALUES` 与 JDBC batch 的计划识别、只读参数视图、批参数视图、表级批写和事务/错误兼容保护。
* 修复 LOB 立即关闭竞态、SourceCompiler 并发输出、FilePathDisk 重试收敛、GUI Console 调用线程中断污染，以及多处 executor/session/server/auth/pool/file-lock/network 生命周期中断语义。
* 补充 GitHub issue、贡献和安全问题入口。

## 安全

本 release 未声明单独安全修复。在线备份控制路径增加身份、generation token 与激活栅栏校验；发布凭据、GPG 私钥和 staging secrets 不应提交到仓库。

## 存储与恢复说明

协调式在线备份产生原子 bundle 并在独立 shadow 目录完成恢复与验证，只有通过 generation 激活栅栏后才允许切换。generation registry、活动指针、CAS 切换和崩溃恢复由部署/路由层负责。首批生产灰度仅认证一个真实 `adb_ldb` 参与者。

## SQL 与 JDBC 说明

DML 快路径保持实验性和可回退。生成键、触发器、约束、delta table、`INSERT SELECT`、`MERGE`、`UPDATE` 与 `DELETE` 继续走原生执行路径。本 release 没有本仓库内 ADB/LDB 集成吞吐结论，不声明 1.5x 或 3x 性能目标。

## Maven

```xml
<dependency>
    <groupId>net.xdob.h2db</groupId>
    <artifactId>h2db</artifactId>
    <version>2.4.0</version>
</dependency>
```

## 验证

主要验证命令：

```powershell
cd h2
.\gradlew.bat runPluginArchitectureCheck
.\gradlew.bat runH2LegacySmoke
.\gradlew.bat runOnlineBackupCheck
.\gradlew.bat runMvStoreSpaceReclamationCheck
.\gradlew.bat runMvStoreRecoveryCheck
.\gradlew.bat --rerun-tasks runLongRunJUnitCheck
.\gradlew.bat --rerun-tasks longRunTestDistZip
.\gradlew.bat runH2TestAllCi
.\gradlew.bat clean jar sourceJar javadocJar generatePomFileForMavenPublication
.\gradlew.bat publishToMavenLocal "-Dmaven.repo.local=build\test-m2-release-clean"
```

### 在线备份专项验收

* 在线备份检查：82/82。
* 插件架构检查：140/140。
* 2.3 兼容矩阵、故障矩阵、影子恢复及 generation 激活栅栏验证通过。
* 持续 DML 场景 prepare 延迟 p99/max：2ms/2ms。
* 混合 DML、DDL 与长事务场景 prepare 延迟 p99/max：21ms/21ms。

## 已知问题

* 首批生产灰度只认证一个真实 `adb_ldb` 参与者，新增 provider 必须先通过认证规范和故障矩阵。
* 协调式在线备份不会替代部署层的 generation registry、活动指针、CAS 和崩溃恢复。
* DML 快路径仍是实验性 V1，覆盖范围和性能声明受上述边界限制。
