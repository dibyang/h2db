/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.h2.api.PluginCapability;
import org.h2.api.StorageEngine;
import org.h2.api.StorageEngineContext;
import org.h2.api.StorageEngineProvider;
import org.h2.api.StorageMaintenance;
import org.h2.api.StorageMaintenanceResult;
import org.h2.engine.PluginRegistry;
import org.h2.engine.PluginSource;
import org.junit.jupiter.api.Test;

/**
 * 第三方 Storage SPI 最小契约的 JUnit 验证。
 */
public class StorageSpiContractTest {

    /**
     * T-PLUGIN-F5-SPI-COMPAT-01.
     */
    @Test
    public void fakeStorageProviderCanRegister() {
        PluginRegistry registry = new PluginRegistry();
        FakeStorageProvider provider = new FakeStorageProvider();

        registry.registerProvider("fake.storage", "1", provider, PluginSource.CONFIGURED_CLASS);

        assertEquals(provider, registry.findProvider(StorageEngineProvider.TYPE, "fake"));
    }

    /**
     * T-PLUGIN-F5-CAPABILITY-PURE-01.
     */
    @Test
    public void supportsDoesNotOpenStorage() {
        FakeStorageProvider provider = new FakeStorageProvider();

        assertFalse(provider.supports(PluginCapability.STORAGE_VACUUM_ONLINE));
        assertEquals(0, provider.openCount);
    }

    /**
     * T-PLUGIN-F5-LIFECYCLE-01.
     */
    @Test
    public void defaultCloseFlushesAndClosesImmediately() {
        FakeStorageEngine engine = new FakeStorageEngine();

        engine.close();

        assertEquals(1, engine.flushCount);
        assertEquals(1, engine.closeImmediatelyCount);
    }

    private static final class FakeStorageProvider implements StorageEngineProvider {
        private int openCount;

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return "fake";
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.STORAGE_PERSISTENT.equals(capability);
        }

        @Override
        public StorageEngine open(StorageEngineContext context) {
            openCount++;
            return new FakeStorageEngine();
        }
    }

    private static final class FakeStorageEngine implements StorageEngine {
        private int flushCount;
        private int closeImmediatelyCount;

        @Override
        public String getEngineId() {
            return "fake";
        }

        @Override
        public boolean supports(String capability) {
            return false;
        }

        @Override
        public void flush() {
            flushCount++;
        }

        @Override
        public void closeImmediately() {
            closeImmediatelyCount++;
        }

        @Override
        public StorageMaintenance getMaintenance() {
            return new StorageMaintenance() {
                @Override
                public boolean supports(String capability) {
                    return false;
                }

                @Override
                public StorageMaintenanceResult compactClosed() {
                    return StorageMaintenanceResult.UNSUPPORTED;
                }

                @Override
                public StorageMaintenanceResult compactOnline() {
                    return StorageMaintenanceResult.UNSUPPORTED;
                }

                @Override
                public StorageMaintenanceResult vacuumOnline() {
                    return StorageMaintenanceResult.UNSUPPORTED;
                }
            };
        }
    }
}
