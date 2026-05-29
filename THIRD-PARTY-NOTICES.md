# 第三方通知

[English](THIRD-PARTY-NOTICES.en.md)

本清单来自 `h2/build.gradle`，用于发布审查。每次 release 前都应结合已解析依赖图和发布 POM 重新检查。

## 运行时与可选依赖

| 组件 | 版本 | 范围 | 许可证 |
| --- | --- | --- | --- |
| `javax.servlet:javax.servlet-api` | 4.0.1 | implementation / optional servlet integration | CDDL-1.1 OR GPL-2.0 with Classpath exception |
| `jakarta.servlet:jakarta.servlet-api` | 5.0.0 | implementation / optional servlet integration | EPL-2.0 OR GPL-2.0 with Classpath exception |
| `org.apache.lucene:lucene-core` | 8.5.2 | implementation / optional full-text search | Apache-2.0 |
| `org.apache.lucene:lucene-analyzers-common` | 8.5.2 | implementation / optional full-text search | Apache-2.0 |
| `org.apache.lucene:lucene-queryparser` | 8.5.2 | implementation / optional full-text search | Apache-2.0 |
| `org.slf4j:slf4j-api` | 1.7.30 | implementation / optional logging API | MIT |
| `org.osgi:org.osgi.core` | 5.0.0 | implementation / OSGi metadata | Apache-2.0 |
| `org.osgi:org.osgi.service.jdbc` | 1.1.0 | compileOnly / OSGi JDBC service | Apache-2.0 |
| `org.locationtech.jts:jts-core` | 1.17.0 | implementation / spatial support | EPL-2.0 OR EDL-1.0 |

## 测试依赖

| 组件 | 版本 | 范围 | 许可证 |
| --- | --- | --- | --- |
| `org.slf4j:slf4j-nop` | 1.7.30 | testImplementation | MIT |
| `org.postgresql:postgresql` | 42.4.0 | testImplementation | BSD-2-Clause |
| `org.junit.jupiter:junit-jupiter-engine` | 5.6.2 | testImplementation | EPL-2.0 |
| `org.ow2.asm:asm` | 9.4 | testImplementation | BSD-3-Clause |

## 构建插件

| 组件 | 版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| `net.researchgate:gradle-release` | 2.6.0 | release automation | Apache-2.0 |

## 发布要求

分发源码或二进制产物前：

* 重新生成或检查已解析依赖树。
* 从已发布 POM 或上游 release 确认每个依赖的许可证。
* 确保二进制分发所需的许可证文本包含在 release artifact 或 distribution bundle 中。
* 确认没有已提交的生成产物或二进制文件缺少来源说明。

本表是发布审查清单，不替代法律意见。首次公开分发或依赖版本变化时，应重新核对依赖许可证和二进制分发义务。
