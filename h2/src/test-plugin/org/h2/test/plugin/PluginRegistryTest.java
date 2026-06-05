/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.h2.api.H2Plugin;
import org.h2.api.PluginCapability;
import org.h2.api.PluginDependency;
import org.h2.api.PluginProvider;
import org.h2.api.TableEngineProvider;
import org.h2.engine.PluginRegistry;
import org.h2.engine.PluginSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * 插件注册中心的 JUnit 测试。
 */
public class PluginRegistryTest {

    /**
     * T-PLUGIN-BUILTIN-REGISTRY-01.
     */
    @Test
    public void registersAndFindsProvider() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider provider = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        registry.registerProvider("test.plugin", "1", provider, PluginSource.BUILTIN);

        assertSame(provider, registry.findProvider(TableEngineProvider.TYPE, "sample"));
        assertNotNull(registry.getProviders(TableEngineProvider.TYPE).get("sample"));
        assertEquals("test.plugin",
                registry.getProviders(TableEngineProvider.TYPE).get("sample").getPluginId());
        assertEquals(PluginSource.BUILTIN,
                registry.getProviders(TableEngineProvider.TYPE).get("sample").getSource());
    }

    /**
     * T-PLUGIN-CAPABILITY-UNSUPPORTED-01.
     */
    @Test
    public void checksCapabilities() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider provider = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        registry.registerProvider("test.plugin", "1", provider, PluginSource.BUILTIN);

        assertTrue(registry.supports(TableEngineProvider.TYPE, "sample", PluginCapability.TABLE_CREATE));
        assertFalse(registry.supports(TableEngineProvider.TYPE, "sample", "unknown.capability"));
        assertFalse(registry.supports(TableEngineProvider.TYPE, "missing", PluginCapability.TABLE_CREATE));
    }

    /**
     * T-PLUGIN-PROVIDER-ID-CONFLICT-01.
     */
    @Test
    public void rejectsDuplicateProviderIds() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider first = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);
        PluginProvider second = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        registry.registerProvider("test.plugin", "1", first, PluginSource.BUILTIN);

        assertThrows(IllegalArgumentException.class, () ->
                registry.registerProvider("other.plugin", "1", second, PluginSource.CONFIGURED_CLASS));
    }

    /**
     * T-PLUGIN-P2-INVALID-PLUGIN-01.
     */
    @Test
    public void rejectsInvalidPluginDescriptors() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider provider = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        assertIllegalArgumentContains(() -> registry.registerPlugin(null, PluginSource.BUILTIN),
                "Plugin must not be null");
        assertIllegalArgumentContains(() -> registry.registerPlugin(new TestPlugin("", "1",
                Arrays.asList(provider)), PluginSource.BUILTIN), "Plugin id must not be empty");
        assertIllegalArgumentContains(() -> registry.registerPlugin(new TestPlugin("test.plugin", "",
                Arrays.asList(provider)), PluginSource.BUILTIN), "Plugin version must not be empty");
        assertIllegalArgumentContains(() -> registry.registerPlugin(new TestPlugin("test.plugin", "1",
                "", Arrays.asList(provider), Collections.<PluginDependency>emptyList()), PluginSource.BUILTIN),
                "Plugin display name must not be empty");
        assertIllegalArgumentContains(() -> registry.registerPlugin(new TestPlugin("test.plugin", "1",
                null), PluginSource.BUILTIN), "Plugin providers must not be null");
        assertIllegalArgumentContains(() -> registry.registerPlugin(new TestPlugin("test.plugin", "1",
                Collections.<PluginProvider>emptyList()), PluginSource.BUILTIN),
                "Plugin must provide at least one provider");
        assertIllegalArgumentContains(() -> registry.registerPlugin(new TestPlugin("test.plugin", "1",
                Arrays.asList(provider)), null), "Plugin source must not be null");
    }

    /**
     * T-PLUGIN-P2-INVALID-PROVIDER-01.
     */
    @Test
    public void rejectsInvalidProviderDescriptors() {
        PluginRegistry registry = new PluginRegistry();

        assertIllegalArgumentContains(() -> registry.registerProvider("", "1",
                new TestProvider(TableEngineProvider.TYPE, "sample", PluginCapability.TABLE_CREATE),
                PluginSource.BUILTIN), "Plugin id must not be empty");
        assertIllegalArgumentContains(() -> registry.registerProvider("test.plugin", "",
                new TestProvider(TableEngineProvider.TYPE, "sample", PluginCapability.TABLE_CREATE),
                PluginSource.BUILTIN), "Plugin version must not be empty");
        assertIllegalArgumentContains(() -> registry.registerProvider("test.plugin", "1",
                null, PluginSource.BUILTIN), "Plugin provider must not be null");
        assertIllegalArgumentContains(() -> registry.registerProvider("test.plugin", "1",
                new TestProvider("", "sample", PluginCapability.TABLE_CREATE), PluginSource.BUILTIN),
                "Provider type must not be empty");
        assertIllegalArgumentContains(() -> registry.registerProvider("test.plugin", "1",
                new TestProvider(TableEngineProvider.TYPE, "", PluginCapability.TABLE_CREATE),
                PluginSource.BUILTIN), "Provider id must not be empty");
        assertIllegalArgumentContains(() -> registry.registerProvider("test.plugin", "1",
                new TestProvider(TableEngineProvider.TYPE, "sample", PluginCapability.TABLE_CREATE),
                null), "Plugin source must not be null");
    }

    /**
     * T-PLUGIN-BUILTIN-REGISTRY-01.
     */
    @Test
    public void registersPluginProviders() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider provider = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);
        H2Plugin plugin = new TestPlugin(provider);

        registry.registerPlugin(plugin, PluginSource.BUILTIN);

        assertSame(provider, registry.findProvider(TableEngineProvider.TYPE, "sample"));
    }

    /**
     * T-PLUGIN-F1-INFO-SCHEMA-01.
     */
    @Test
    public void returnsProviderDiagnostics() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider provider = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        registry.registerProvider("test.plugin", "1", provider, PluginSource.BUILTIN);

        List<PluginRegistry.ProviderDiagnostic> diagnostics = registry.getProviderDiagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals(TableEngineProvider.TYPE, diagnostics.get(0).getType());
        assertEquals("sample", diagnostics.get(0).getId());
        assertEquals("test.plugin", diagnostics.get(0).getPluginId());
        assertEquals("1", diagnostics.get(0).getPluginVersion());
        assertEquals(PluginSource.BUILTIN, diagnostics.get(0).getSource());
    }

    /**
     * T-PLUGIN-F1-CAPABILITY-LIST-01.
     */
    @Test
    public void returnsSupportedCapabilityDiagnostics() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider provider = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        registry.registerProvider("test.plugin", "1", provider, PluginSource.BUILTIN);

        List<String> capabilities = registry.getProviderDiagnostics().get(0).getCapabilities();
        assertEquals(1, capabilities.size());
        assertEquals(PluginCapability.TABLE_CREATE, capabilities.get(0));
    }

    /**
     * T-PLUGIN-P16-DEPENDENCY-DIAGNOSTIC-01.
     */
    @Test
    public void returnsDependencyDiagnostics() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider provider = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        registry.registerPlugin(new TestPlugin("test.plugin", "1", Arrays.asList(provider),
                Arrays.asList(new PluginDependency("test.base", "[1,2)"))), PluginSource.CONFIGURED_CLASS);

        List<PluginRegistry.DependencyDiagnostic> diagnostics = registry.getDependencyDiagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals("test.plugin", diagnostics.get(0).getPluginId());
        assertEquals("1", diagnostics.get(0).getPluginVersion());
        assertEquals("test.base", diagnostics.get(0).getDependencyPluginId());
        assertEquals("[1,2)", diagnostics.get(0).getDependencyVersion());
        assertEquals(PluginSource.CONFIGURED_CLASS, diagnostics.get(0).getSource());
    }

    /**
     * T-PLUGIN-P17-DISPLAY-NAME-DIAGNOSTIC-01.
     */
    @Test
    public void returnsPluginDisplayNameDiagnostics() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider provider = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        registry.registerPlugin(new TestPlugin("test.plugin", "1", "Display Plugin",
                Arrays.asList(provider), Collections.<PluginDependency>emptyList()), PluginSource.CONFIGURED_CLASS);

        List<PluginRegistry.PluginDiagnostic> diagnostics = registry.getPluginDiagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals("test.plugin", diagnostics.get(0).getPluginId());
        assertEquals("1", diagnostics.get(0).getPluginVersion());
        assertEquals("Display Plugin", diagnostics.get(0).getDisplayName());
        assertEquals(PluginSource.CONFIGURED_CLASS, diagnostics.get(0).getSource());
    }

    /**
     * T-PLUGIN-P14-VERSION-RANGE-REGISTRY-01.
     */
    @Test
    public void matchesRegisteredPluginVersionRanges() {
        PluginRegistry registry = new PluginRegistry();

        registry.registerProvider("test.versioned", "1.0.0",
                new TestProvider(TableEngineProvider.TYPE, "sample_v1", PluginCapability.TABLE_CREATE),
                PluginSource.CONFIGURED_CLASS);
        registry.registerProvider("test.versioned", "2.1.0",
                new TestProvider(TableEngineProvider.TYPE, "sample_v2", PluginCapability.TABLE_CREATE),
                PluginSource.CONFIGURED_CLASS);

        assertTrue(registry.hasPlugin("test.versioned"));
        assertTrue(registry.hasPlugin("test.versioned", "1.0.0"));
        assertTrue(registry.hasPlugin("test.versioned", "[2.0,3.0)"));
        assertTrue(registry.hasPlugin("test.versioned", "*"));
        assertFalse(registry.hasPlugin("test.versioned", "[3.0,4.0)"));
        assertFalse(registry.hasPlugin("missing.versioned", "*"));
    }

    /**
     * T-PLUGIN-P14-DUPLICATE-PLUGIN-VERSION-01.
     */
    @Test
    public void rejectsDuplicatePluginIdAndVersion() {
        PluginRegistry registry = new PluginRegistry();

        registry.registerPlugin(new TestPlugin("test.duplicate", "1",
                Arrays.asList(new TestProvider(TableEngineProvider.TYPE, "duplicate_a",
                        PluginCapability.TABLE_CREATE))), PluginSource.CONFIGURED_CLASS);

        assertIllegalArgumentContains(() -> registry.registerPlugin(new TestPlugin("test.duplicate", "1",
                Arrays.asList(new TestProvider(TableEngineProvider.TYPE, "duplicate_b",
                        PluginCapability.TABLE_CREATE))), PluginSource.CONFIGURED_CLASS),
                "Duplicate plugin version");
    }

    /**
     * T-PLUGIN-F1-CONFLICT-DIAGNOSTIC-01.
     */
    @Test
    public void duplicateProviderErrorContainsDiagnostics() {
        PluginRegistry registry = new PluginRegistry();
        PluginProvider first = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);
        PluginProvider second = new TestProvider(TableEngineProvider.TYPE, "sample",
                PluginCapability.TABLE_CREATE);

        registry.registerProvider("test.plugin", "1", first, PluginSource.BUILTIN);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                registry.registerProvider("other.plugin", "1", second, PluginSource.CONFIGURED_CLASS));
        assertTrue(e.getMessage().contains("type=table"));
        assertTrue(e.getMessage().contains("id=sample"));
        assertTrue(e.getMessage().contains("existingPlugin=test.plugin"));
        assertTrue(e.getMessage().contains("existingSource=BUILTIN"));
        assertTrue(e.getMessage().contains("newSource=CONFIGURED_CLASS"));
    }

    private static final class TestPlugin implements H2Plugin {
        private final String id;
        private final String version;
        private final String displayName;
        private final Iterable<? extends PluginProvider> providers;
        private final Iterable<PluginDependency> dependencies;

        TestPlugin(PluginProvider provider) {
            this("test.plugin", "1", Arrays.asList(provider));
        }

        TestPlugin(String id, String version, Iterable<? extends PluginProvider> providers) {
            this(id, version, "Test Plugin", providers, Collections.<PluginDependency>emptyList());
        }

        TestPlugin(String id, String version, Iterable<? extends PluginProvider> providers,
                Iterable<PluginDependency> dependencies) {
            this(id, version, "Test Plugin", providers, dependencies);
        }

        TestPlugin(String id, String version, String displayName, Iterable<? extends PluginProvider> providers,
                Iterable<PluginDependency> dependencies) {
            this.id = id;
            this.version = version;
            this.displayName = displayName;
            this.providers = providers;
            this.dependencies = dependencies;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return providers;
        }

        @Override
        public Iterable<PluginDependency> getDependencies() {
            return dependencies;
        }
    }

    private static final class TestProvider implements PluginProvider {
        private final String type;
        private final String id;
        private final String capability;

        TestProvider(String type, String id, String capability) {
            this.type = type;
            this.id = id;
            this.capability = capability;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean supports(String capability) {
            return this.capability.equals(capability);
        }
    }

    private static void assertIllegalArgumentContains(Executable executable, String message) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, executable);
        assertTrue(e.getMessage().contains(message));
    }
}
