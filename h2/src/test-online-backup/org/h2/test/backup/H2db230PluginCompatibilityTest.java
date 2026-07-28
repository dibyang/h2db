/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.PluginProvider;
import org.h2.engine.BuiltinPlugins;
import org.h2.engine.PluginLoader;
import org.h2.engine.PluginRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P9 旧插件二进制兼容测试。
 */
public class H2db230PluginCompatibilityTest {

    @TempDir
    Path directory;

    /**
     * 仅使用 2.3.0 API 编译的旧插件可以由 2.4.x 加载，且无需实现新增
     * participant SPI。
     * <p>
     * T-H2BR-23X-PLUGIN-COMPAT-01。
     */
    @Test
    public void loadsPluginCompiledOnlyAgainst230Api() throws Exception {
        Path legacyJar = legacyJar();
        Path sourceDirectory = Files.createDirectories(
                directory.resolve("source/compat"));
        Path classes = Files.createDirectories(
                directory.resolve("classes"));
        Path source = sourceDirectory.resolve("Legacy230Plugin.java");
        Files.write(source, legacyPluginSource().getBytes(
                StandardCharsets.UTF_8));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "兼容测试必须在 JDK 而不是 JRE 下运行");
        int result = compiler.run(null, null, null, "-encoding", "UTF-8",
                "-source", "8", "-target", "8", "-classpath",
                legacyJar.toString(), "-d", classes.toString(),
                source.toString());
        assertTrue(result == 0, "2.3.0 插件源码编译失败");

        PluginRegistry registry = new PluginRegistry();
        BuiltinPlugins.register(registry);
        PluginLoader.loadConfiguredPlugins(registry,
                "compat.Legacy230Plugin", classes.toString());
        PluginProvider provider =
                registry.findProvider("transaction", "legacy230");
        assertNotNull(provider);
        assertFalse(provider
                instanceof OnlineBackupParticipantProvider);
    }

    private static Path legacyJar() {
        String configured = System.getProperty("h2db.compat23.jar");
        Path jar = configured != null ? Paths.get(configured)
                : Paths.get(System.getProperty("user.dir"), "build",
                        "libs", "h2db-2.3.0.jar");
        assertTrue(Files.isRegularFile(jar),
                "缺少 Gradle 解析的 2.3.0 兼容验证 jar: " + jar);
        return jar;
    }

    private static String legacyPluginSource() {
        return "package compat;\n"
                + "import java.util.Collections;\n"
                + "import org.h2.api.H2Plugin;\n"
                + "import org.h2.api.PluginProvider;\n"
                + "public final class Legacy230Plugin "
                + "implements H2Plugin {\n"
                + "  public String getId() { return \"compat.legacy230\"; }\n"
                + "  public String getVersion() { return \"1\"; }\n"
                + "  public String getDisplayName() { "
                + "return \"Legacy 2.3.0\"; }\n"
                + "  public Iterable<? extends PluginProvider> "
                + "getProviders() { return Collections.singletonList("
                + "new LegacyProvider()); }\n"
                + "  private static final class LegacyProvider "
                + "implements PluginProvider {\n"
                + "    public String getType() { return \"transaction\"; }\n"
                + "    public String getId() { return \"legacy230\"; }\n"
                + "    public boolean supports(String capability) { "
                + "return false; }\n"
                + "  }\n"
                + "}\n";
    }
}
