# 开源发布审查

[English](OPEN_SOURCE_RELEASE.en.md)

本文记录 h2db 的开源发布准备情况和发布前检查项。

## 当前结论

本仓库已经具备开源 Java 项目所需的基础发布材料：

* `LICENSE.txt`：包含 H2 的 MPL 2.0 / EPL 1.0 双许可证文本。
* `NOTICE.txt`：说明上游 H2 来源。
* `README.md`：说明项目身份、构建方式、Maven 坐标、许可证、支持方式和发布材料入口。
* `CONTRIBUTING.md`：说明开发、测试、兼容性和许可证要求。
* `SECURITY.md`：说明私密漏洞报告路径。
* `CODE_OF_CONDUCT.md` 和 `SUPPORT.md`。
* `THIRD-PARTY-NOTICES.md`：提供依赖许可证审查清单。
* GitHub issue、PR 和 release 模板。

## 公开发布前阻塞项

公开发布前仍需维护者确认：

* 确认 h2db 展示为独立维护的 fork，而不是官方 H2 发布。
* 确认最终仓库 URL、维护者身份和 POM 中的联系邮箱。
* 确认发布版本和 Maven 坐标在 `README.md`、`h2/gradle.properties` 和生成 POM 中一致。
* 启用或明确说明私密安全漏洞报告渠道。
* 对精确发布构建执行已解析依赖的许可证审查。
* 确认签名密钥和发布仓库凭据只通过本地安全文件或 CI secrets 提供。
* 上传前检查 source、javadoc、binary 和 POM 产物。
* 确认 `net.xdob` Maven Central namespace 已验证，或改用你能控制的 namespace。

## 发布清单

1. 从干净工作区开始。
2. 检查 `LICENSE.txt`、`NOTICE.txt` 和 `THIRD-PARTY-NOTICES.md`。
3. 确认 release 中没有来源未记录的二进制文件或生成文件。
4. 在 `h2/` 目录构建：

   ```sh
   ./gradlew clean jar javadocJar sourceJar generatePomFileForMavenPublication
   ```

5. 运行本次发布选定的项目测试命令。
6. 检查生成 POM 的许可证、SCM、developer 和依赖元数据。
7. 检查源码和二进制归档中是否包含必要许可证和 notice 文件。
8. 签名 release 产物。
9. 使用 Maven Central 发布流程发布。
10. 在新工作区下载已发布产物并进行 smoke test。
11. Maven Central 产物验证成功后再创建 Git tag。
12. 发布 GitHub Release，包含 release notes、checksums 和已知问题。

## 合规说明

H2 派生文件中的源文件头标识了 H2 Group 版权和 MPL 2.0 / EPL 1.0 许可证选择。Gradle POM 元数据必须与这些许可证选择保持一致。

本文是发布工程清单，不是法律意见。首次公开分发前，维护者或法律顾问应进行审查。

详细公开发布流程见 `MAVEN_CENTRAL_RELEASE.md` 和 `GITHUB_RELEASE.md`。
