/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;

import org.h2.api.H2Plugin;
import org.h2.api.PluginDependency;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.SystemCatalogProvider;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.engine.BuiltinPlugins;
import org.h2.engine.PluginLoader;
import org.h2.engine.PluginRegistry;
import org.h2.engine.PluginSource;
import org.h2.message.DbException;
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
    public void loadsConfiguredPluginClass() {
        PluginRegistry registry = configuredRegistry();

        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "external_table"));
    }

    /**
     * T-PLUGIN-F4-LOAD-FAIL-01.
     */
    @Test
    public void failsWhenConfiguredPluginClassIsMissing() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry("example.MissingPlugin"));

        assertTrue(e.getMessage().contains("Could not load configured plugin class"));
        assertTrue(e.getMessage().toLowerCase().contains("missingplugin"));
    }

    /**
     * T-PLUGIN-F4-BUILTIN-CONFLICT-01.
     */
    @Test
    public void rejectsConfiguredPluginConflictWithBuiltinProvider() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                configuredRegistry(ConflictingPlugin.class.getName()));

        assertTrue(e.getMessage().contains("Duplicate built-in plugin provider"));
        assertTrue(e.getMessage().contains("id=mvstore"));
    }

    /**
     * T-PLUGIN-F6-H2-VERSION-RANGE-01.
     */
    @Test
    public void rejectsUnsupportedH2VersionRange() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry(VersionMismatchPlugin.class.getName()));

        assertTrue(e.getMessage().contains("does not support this H2 version"));
        assertTrue(e.getMessage().contains("required=0.0"));
    }

    /**
     * T-PLUGIN-R7-VERSION-RANGE-SEMANTICS-01.
     */
    @Test
    public void acceptsSupportedH2VersionRange() {
        PluginRegistry registry = configuredRegistry(SupportedRangePlugin.class.getName());

        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "version_range_table"));
    }

    /**
     * T-PLUGIN-P14-MULTI-VERSION-COEXIST-01.
     */
    @Test
    public void allowsMultipleVersionsWithDistinctProviderIds() {
        String classes = VersionedV1Plugin.class.getName() + "," + VersionedV2Plugin.class.getName();
        PluginRegistry registry = configuredRegistry(classes);

        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "versioned_v1_table"));
        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "versioned_v2_table"));
    }

    /**
     * T-PLUGIN-P14-DEPENDENCY-RANGE-01.
     */
    @Test
    public void resolvesDependenciesByVersionRange() {
        String classes = DependsOnVersion2Plugin.class.getName() + "," + VersionedV1Plugin.class.getName()
                + "," + VersionedV2Plugin.class.getName();
        PluginRegistry registry = configuredRegistry(classes);

        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "depends_v2_table"));
    }

    /**
     * T-PLUGIN-P14-DEPENDENCY-RANGE-MISSING-01.
     */
    @Test
    public void rejectsDependencyWhenNoVersionMatches() {
        String classes = DependsOnVersion3Plugin.class.getName() + "," + VersionedV2Plugin.class.getName();
        DbException e = assertThrows(DbException.class, () -> configuredRegistry(classes));

        assertTrue(e.getMessage().contains("Configured plugin dependency is missing"));
        assertTrue(e.getMessage().contains("dependency=test.versioned"));
        assertTrue(e.getMessage().contains("version=[3,4)"));
    }

    /**
     * T-PLUGIN-P14-DUPLICATE-DISCOVERED-VERSION-01.
     */
    @Test
    public void rejectsDuplicateDiscoveredPluginVersion() {
        String classes = DuplicateVersionAPlugin.class.getName() + "," + DuplicateVersionBPlugin.class.getName();
        DbException e = assertThrows(DbException.class, () -> configuredRegistry(classes));

        assertTrue(e.getMessage().contains("Duplicate configured plugin version"));
        assertTrue(e.getMessage().contains("plugin=test.duplicate.version"));
        assertTrue(e.getMessage().contains("version=1"));
    }

    /**
     * T-PLUGIN-F6-DEPENDENCY-MISSING-01.
     */
    @Test
    public void rejectsMissingPluginDependency() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry(MissingDependencyPlugin.class.getName()));

        assertTrue(e.getMessage().contains("Configured plugin dependency is missing"));
        assertTrue(e.getMessage().contains("dependency=missing.plugin"));
    }

    /**
     * T-PLUGIN-P3-DEPENDENCY-CYCLE-01.
     */
    @Test
    public void rejectsPluginDependencyCycle() {
        String classes = CycleAPlugin.class.getName() + "," + CycleBPlugin.class.getName();
        DbException e = assertThrows(DbException.class, () -> configuredRegistry(classes));

        assertTrue(e.getMessage().contains("Configured plugin dependency cycle"));
        assertTrue(e.getMessage().contains("plugin=test.cycle.a"));
    }

    /**
     * T-PLUGIN-P3-INVALID-DESCRIPTOR-01.
     */
    @Test
    public void rejectsInvalidPluginDescriptorBeforeSecurityValidation() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry(EmptyIdPlugin.class.getName()));

        assertTrue(e.getMessage().contains("Configured plugin descriptor is invalid"));
        assertTrue(e.getMessage().contains("plugin id is empty"));
    }

    /**
     * T-PLUGIN-P3-NO-PROVIDER-01.
     */
    @Test
    public void rejectsPluginWithoutProviders() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry(NoProviderPlugin.class.getName()));

        assertTrue(e.getMessage().contains("Configured plugin descriptor is invalid"));
        assertTrue(e.getMessage().contains("reason=no providers"));
    }

    /**
     * T-PLUGIN-P16-INVALID-DEPENDENCY-01.
     */
    @Test
    public void rejectsInvalidPluginDependencies() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry(NullDependencyPlugin.class.getName()));

        assertTrue(e.getMessage().contains("Configured plugin descriptor is invalid"));
        assertTrue(e.getMessage().contains("reason=dependency is null"));
    }

    /**
     * T-PLUGIN-P16-INVALID-DEPENDENCY-02.
     */
    @Test
    public void rejectsEmptyPluginDependencyVersion() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry(EmptyDependencyVersionPlugin.class.getName()));

        assertTrue(e.getMessage().contains("Configured plugin descriptor is invalid"));
        assertTrue(e.getMessage().contains("dependency version is empty"));
    }

    /**
     * T-PLUGIN-R7-DEPENDENCY-ORDER-01.
     */
    @Test
    public void registersConfiguredPluginsInDependencyOrder() throws Exception {
        String classes = DependentPlugin.class.getName() + "," + ConfiguredPlugin.class.getName();
        PluginRegistry registry = configuredRegistry(classes);

        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "external_table"));
        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "dependent_table"));
    }

    /**
     * T-PLUGIN-F6-PROVIDER-CONFLICT-01.
     */
    @Test
    public void providerConflictIncludesPluginDiagnostics() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                configuredRegistry(ConflictingPlugin.class.getName()));

        assertTrue(e.getMessage().contains("existingPlugin=h2.mvstore"));
        assertTrue(e.getMessage().contains("newSource=CONFIGURED_CLASS"));
    }

    /**
     * T-PLUGIN-F7-FORBIDDEN-CAPABILITY-01.
     */
    @Test
    public void rejectsForbiddenProviderType() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry(ForbiddenProviderPlugin.class.getName()));

        assertTrue(e.getMessage().contains("provider type is not allowed"));
        assertTrue(e.getMessage().contains("type=parser"));
    }

    /**
     * T-PLUGIN-P12-PROVIDER-PERMISSIONS-01.
     */
    @Test
    public void rejectsProviderTypeOutsidePluginAllowedList() {
        DbException e = assertThrows(DbException.class, () ->
                configuredRegistry(DisallowedProviderTypePlugin.class.getName()));

        assertTrue(e.getMessage().contains("provider type is not permitted"));
        assertTrue(e.getMessage().contains("type=table"));
        assertTrue(e.getMessage().contains("plugin=restricted.plugin"));
        assertTrue(e.getMessage().contains("pluginAllowedTypes=[system_catalog]"));
    }

    /**
     * T-PLUGIN-P12-PROVIDER-PERMISSIONS-02.
     */
    @Test
    public void acceptsProviderTypeWithinPluginAllowedList() {
        PluginRegistry registry = configuredRegistry(AllowedProviderTypePlugin.class.getName());

        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "constrained_table"));
    }

    /**
     * T-PLUGIN-F9-SERVICELOADER-OFF-01.
     */
    @Test
    public void serviceLoaderDiscoveryIsDisabledByDefault() {
        PluginRegistry registry = new PluginRegistry();
        int loaded = PluginLoader.loadDiscoveredPlugins(registry, false,
                Arrays.asList(new ConfiguredPlugin()), PluginSource.SERVICE_LOADER);

        assertTrue(loaded == 0);
        assertTrue(registry.findProvider(TableEngineProvider.TYPE, "external_table") == null);
    }

    /**
     * T-PLUGIN-F9-SERVICELOADER-ON-01.
     */
    @Test
    public void serviceLoaderDiscoveryCanBeEnabled() {
        PluginRegistry registry = new PluginRegistry();
        int loaded = PluginLoader.loadDiscoveredPlugins(registry, true,
                Arrays.asList(new ConfiguredPlugin()), PluginSource.SERVICE_LOADER);

        assertTrue(loaded == 1);
        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "external_table"));
    }

    /**
     * T-PLUGIN-R8-SERVICE-RESOURCE-01.
     */
    @Test
    public void serviceLoaderDiscoversRealServiceResource() {
        PluginRegistry registry = new PluginRegistry();
        int loaded = PluginLoader.loadServiceLoaderPlugins(registry, true);

        assertTrue(loaded >= 1);
        assertNotNull(registry.findProvider(TableEngineProvider.TYPE, "service_resource_table"));
    }

    /**
     * T-PLUGIN-R8-SAMPLE-PLUGIN-01.
     */
    @Test
    public void serviceLoaderCanStillBeDisabledForLowLevelTests() {
        PluginRegistry registry = new PluginRegistry();
        int loaded = PluginLoader.loadServiceLoaderPlugins(registry, false);

        assertTrue(loaded == 0);
        assertTrue(registry.findProvider(TableEngineProvider.TYPE, "service_resource_table") == null);
    }

    /**
     * T-PLUGIN-F9-SAMPLE-COMPILE-01.
     */
    @Test
    public void samplePluginCompilesAndProvidesTableProvider() {
        ConfiguredPlugin plugin = new ConfiguredPlugin();

        assertTrue(plugin.getProviders().iterator().hasNext());
        assertTrue(plugin.getH2VersionRange().equals("*"));
    }

    /**
     * T-PLUGIN-P5-URL-LOAD-DISABLED-01.
     */
    @Test
    public void rejectsPluginLoadingFromJdbcUrlSettings() {
        SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(
                "jdbc:h2:mem:pluginUrlLoadingDisabled;PLUGIN_CLASSES="
                        + ConfiguredPlugin.class.getName(), "sa", ""));

        assertTrue(e.getMessage().contains("Unsupported connection setting"));
        assertTrue(e.getMessage().contains("PLUGIN_CLASSES"));
    }

    private static PluginRegistry configuredRegistry() {
        return configuredRegistry(ConfiguredPlugin.class.getName());
    }

    private static PluginRegistry configuredRegistry(String pluginClasses) {
        PluginRegistry registry = new PluginRegistry();
        BuiltinPlugins.register(registry);
        PluginLoader.loadConfiguredPlugins(registry, pluginClasses);
        return registry;
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
     * 测试用 H2 版本范围匹配插件。
     */
    public static final class SupportedRangePlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.version.range";
        }

        @Override
        public String getH2VersionRange() {
            return "[0.0,999.0)";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("version_range_table"));
        }
    }

    public static final class VersionedV1Plugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.versioned";
        }

        @Override
        public String getVersion() {
            return "1";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("versioned_v1_table"));
        }
    }

    public static final class VersionedV2Plugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.versioned";
        }

        @Override
        public String getVersion() {
            return "2";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("versioned_v2_table"));
        }
    }

    public static final class DependsOnVersion2Plugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.depends.v2";
        }

        @Override
        public Iterable<PluginDependency> getDependencies() {
            return Arrays.asList(new PluginDependency("test.versioned", "[2,3)"));
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("depends_v2_table"));
        }
    }

    public static final class DependsOnVersion3Plugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.depends.v3";
        }

        @Override
        public Iterable<PluginDependency> getDependencies() {
            return Arrays.asList(new PluginDependency("test.versioned", "[3,4)"));
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("depends_v3_table"));
        }
    }

    public static final class DuplicateVersionAPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.duplicate.version";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("duplicate_version_a"));
        }
    }

    public static final class DuplicateVersionBPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.duplicate.version";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("duplicate_version_b"));
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
     * 测试用依赖排序插件。
     */
    public static final class DependentPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.dependent";
        }

        @Override
        public Iterable<PluginDependency> getDependencies() {
            return Arrays.asList(new PluginDependency("test.configured", "1"));
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("dependent_table"));
        }
    }

    /**
     * 测试用非法 provider type 插件。
     */
    /**
     * 测试用依赖环插件 A。
     */
    public static final class CycleAPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.cycle.a";
        }

        @Override
        public Iterable<PluginDependency> getDependencies() {
            return Arrays.asList(new PluginDependency("test.cycle.b", "1"));
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("cycle_a_table"));
        }
    }

    /**
     * 测试用依赖环插件 B。
     */
    public static final class CycleBPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.cycle.b";
        }

        @Override
        public Iterable<PluginDependency> getDependencies() {
            return Arrays.asList(new PluginDependency("test.cycle.a", "1"));
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("cycle_b_table"));
        }
    }

    /**
     * 测试用无效 plugin id 插件。
     */
    public static final class EmptyIdPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return " ";
        }
    }

    /**
     * 测试用无 provider 插件。
     */
    public static final class NoProviderPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.no.provider";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Collections.emptyList();
        }
    }

    public static final class NullDependencyPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.null.dependency";
        }

        @Override
        public Iterable<PluginDependency> getDependencies() {
            return Arrays.asList((PluginDependency) null);
        }
    }

    public static final class EmptyDependencyVersionPlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.empty.dependency.version";
        }

        @Override
        public Iterable<PluginDependency> getDependencies() {
            return Arrays.asList(new PluginDependency("test.configured", ""));
        }
    }

    public static final class ForbiddenProviderPlugin extends ConfiguredPlugin {
        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new ForbiddenProvider());
        }
    }

    public static final class AllowedProviderTypePlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "test.allowed.provider";
        }

        @Override
        public Iterable<String> getAllowedProviderTypes() {
            return Arrays.asList(TableEngineProvider.TYPE);
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("constrained_table"));
        }
    }

    public static final class DisallowedProviderTypePlugin extends ConfiguredPlugin {
        @Override
        public String getId() {
            return "restricted.plugin";
        }

        @Override
        public Iterable<String> getAllowedProviderTypes() {
            return Arrays.asList(SystemCatalogProvider.TYPE);
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new TestTableProvider("restricted_table"));
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
