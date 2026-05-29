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
import java.util.List;
import org.h2.api.H2Plugin;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.TableEngineProvider;
import org.h2.engine.PluginRegistry;
import org.h2.engine.PluginSource;
import org.junit.jupiter.api.Test;

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
        private final PluginProvider provider;

        TestPlugin(PluginProvider provider) {
            this.provider = provider;
        }

        @Override
        public String getId() {
            return "test.plugin";
        }

        @Override
        public String getVersion() {
            return "1";
        }

        @Override
        public String getDisplayName() {
            return "Test Plugin";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(provider);
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
}
