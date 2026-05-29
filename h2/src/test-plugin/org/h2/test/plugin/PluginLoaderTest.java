/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;

import org.h2.api.H2Plugin;
import org.h2.api.PluginDependency;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.table.Table;
import org.junit.jupiter.api.Test;

/**
 * 显式外部插件加载的 JUnit 验证。
 */
public class PluginLoaderTest {

    /**
     * T-PLUGIN-F4-LOAD-CLASS-01.
     */
    @Test
    public void loadsConfiguredPluginClass() throws Exception {
        try (Connection conn = DriverManager.getConnection(url("pluginLoad",
                ConfiguredPlugin.class.getName()), "sa", "")) {
            Database db = database(conn);

            assertNotNull(db.getPluginRegistry().findProvider(TableEngineProvider.TYPE, "external_table"));
        }
    }

    /**
     * T-PLUGIN-F4-LOAD-FAIL-01.
     */
    @Test
    public void failsWhenConfiguredPluginClassIsMissing() {
        SQLException e = assertThrows(SQLException.class, () ->
                DriverManager.getConnection(url("pluginMissing", "example.MissingPlugin"), "sa", ""));

        assertTrue(e.getMessage().contains("Could not load configured plugin class"));
        assertTrue(e.getMessage().toLowerCase().contains("missingplugin"));
    }

    /**
     * T-PLUGIN-F4-BUILTIN-CONFLICT-01.
     */
    @Test
    public void rejectsConfiguredPluginConflictWithBuiltinProvider() {
        SQLException e = assertThrows(SQLException.class, () ->
                DriverManager.getConnection(url("pluginConflict", ConflictingPlugin.class.getName()), "sa", ""));

        assertTrue(e.getMessage().contains("Duplicate built-in plugin provider"));
        assertTrue(e.getMessage().contains("id=mvstore"));
    }

    /**
     * T-PLUGIN-F6-H2-VERSION-RANGE-01.
     */
    @Test
    public void rejectsUnsupportedH2VersionRange() {
        SQLException e = assertThrows(SQLException.class, () ->
                DriverManager.getConnection(url("pluginVersion", VersionMismatchPlugin.class.getName()), "sa", ""));

        assertTrue(e.getMessage().contains("does not support this H2 version"));
        assertTrue(e.getMessage().contains("required=0.0"));
    }

    /**
     * T-PLUGIN-F6-DEPENDENCY-MISSING-01.
     */
    @Test
    public void rejectsMissingPluginDependency() {
        SQLException e = assertThrows(SQLException.class, () ->
                DriverManager.getConnection(url("pluginDependency", MissingDependencyPlugin.class.getName()),
                        "sa", ""));

        assertTrue(e.getMessage().contains("Configured plugin dependency is missing"));
        assertTrue(e.getMessage().contains("dependency=missing.plugin"));
    }

    /**
     * T-PLUGIN-F6-PROVIDER-CONFLICT-01.
     */
    @Test
    public void providerConflictIncludesPluginDiagnostics() {
        SQLException e = assertThrows(SQLException.class, () ->
                DriverManager.getConnection(url("pluginConflictDiagnostics", ConflictingPlugin.class.getName()),
                        "sa", ""));

        assertTrue(e.getMessage().contains("existingPlugin=h2.mvstore"));
        assertTrue(e.getMessage().contains("newSource=CONFIGURED_CLASS"));
    }

    /**
     * T-PLUGIN-F7-FORBIDDEN-CAPABILITY-01.
     */
    @Test
    public void rejectsForbiddenProviderType() {
        SQLException e = assertThrows(SQLException.class, () ->
                DriverManager.getConnection(url("pluginForbiddenType", ForbiddenProviderPlugin.class.getName()),
                        "sa", ""));

        assertTrue(e.getMessage().contains("provider type is not allowed"));
        assertTrue(e.getMessage().contains("type=parser"));
    }

    private static String url(String name, String pluginClass) {
        return "jdbc:h2:mem:" + name + ";PLUGIN_CLASSES=" + pluginClass;
    }

    private static Database database(Connection conn) {
        SessionLocal session = (SessionLocal) ((JdbcConnection) conn).getSession();
        return session.getDatabase();
    }

    /**
     * 测试用外部插件。
     */
    public static class ConfiguredPlugin implements H2Plugin {

        @Override
        public String getId() {
            return "test.configured";
        }

        @Override
        public String getVersion() {
            return "1";
        }

        @Override
        public String getDisplayName() {
            return "Configured Plugin";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("external_table"));
        }
    }

    /**
     * 测试用冲突插件。
     */
    public static final class ConflictingPlugin extends ConfiguredPlugin {
        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("mvstore"));
        }
    }

    /**
     * 测试用 H2 版本不匹配插件。
     */
    public static final class VersionMismatchPlugin extends ConfiguredPlugin {
        @Override
        public String getH2VersionRange() {
            return "0.0";
        }
    }

    /**
     * 测试用缺失依赖插件。
     */
    public static final class MissingDependencyPlugin extends ConfiguredPlugin {
        @Override
        public Iterable<PluginDependency> getDependencies() {
            return Arrays.asList(new PluginDependency("missing.plugin", "1"));
        }
    }

    /**
     * 测试用非法 provider type 插件。
     */
    public static final class ForbiddenProviderPlugin extends ConfiguredPlugin {
        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new ForbiddenProvider());
        }
    }

    private static final class ForbiddenProvider implements PluginProvider {

        @Override
        public String getType() {
            return "parser";
        }

        @Override
        public String getId() {
            return "forbidden";
        }

        @Override
        public boolean supports(String capability) {
            return false;
        }
    }

    private static final class TestTableProvider implements TableEngineProvider {
        private final String id;

        TestTableProvider(String id) {
            this.id = id;
        }

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.TABLE_CREATE.equals(capability);
        }

        @Override
        public Table createTable(CreateTableData data, org.h2.api.TableEngineContext context) {
            throw new UnsupportedOperationException();
        }
    }
}
