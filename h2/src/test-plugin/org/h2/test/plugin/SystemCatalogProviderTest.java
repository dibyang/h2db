/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import org.h2.api.H2Plugin;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.SystemCatalogContext;
import org.h2.api.SystemCatalogProvider;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.mvstore.db.MVStoreStorageEngineProvider;
import org.h2.mvstore.db.SecondaryMVStoreStorageEngineProvider;
import org.junit.jupiter.api.Test;

/**
 * System catalog provider 前置契约的 JUnit 验证。
 */
public class SystemCatalogProviderTest {

    /**
     * T-PLUGIN-P4-SYSTEM-CATALOG-BUILTIN-01.
     */
    @Test
    public void defaultMvStoreUsesBuiltinSystemCatalogProvider() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginSystemCatalogBuiltin", "sa", "")) {
            Database db = database(conn);

            assertEquals(MVStoreStorageEngineProvider.ID, db.getSystemCatalogProvider().getId());
            assertNotNull(db.getPluginRegistry().findProvider(SystemCatalogProvider.TYPE,
                    MVStoreStorageEngineProvider.ID));
        }
    }

    /**
     * T-PLUGIN-P4-SYSTEM-CATALOG-SECONDARY-01.
     */
    @Test
    public void secondaryMvStoreUsesMatchingSystemCatalogProvider() throws Exception {
        String url = "jdbc:h2:mem:pluginSystemCatalogSecondary;STORAGE_ENGINE="
                + SecondaryMVStoreStorageEngineProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            Database db = database(conn);

            assertEquals(SecondaryMVStoreStorageEngineProvider.ID, db.getSystemCatalogProvider().getId());
            assertNotNull(db.getPluginRegistry().findProvider(SystemCatalogProvider.TYPE,
                    SecondaryMVStoreStorageEngineProvider.ID));
        }
    }

    /**
     * T-PLUGIN-P4-SYSTEM-CATALOG-REGISTER-01.
     */
    @Test
    public void configuredSystemCatalogProviderCanRegister() throws Exception {
        String url = "jdbc:h2:mem:pluginSystemCatalog;PLUGIN_CLASSES=" + CatalogPlugin.class.getName();

        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            Database db = database(conn);

            assertNotNull(db.getPluginRegistry().findProvider(SystemCatalogProvider.TYPE, CatalogProvider.ID));
        }
    }

    /**
     * T-PLUGIN-P4-SYSTEM-CATALOG-DIAGNOSTIC-01.
     */
    @Test
    public void systemCatalogProviderIsVisibleInDiagnostics() throws Exception {
        String url = "jdbc:h2:mem:pluginSystemCatalogDiagnostics;PLUGIN_CLASSES="
                + CatalogPlugin.class.getName();

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            try (ResultSet rs = stat.executeQuery("select provider_type, provider_id from "
                    + "information_schema.plugin_providers where plugin_id = 'test.system.catalog'")) {
                assertTrue(rs.next());
                assertEquals(SystemCatalogProvider.TYPE, rs.getString(1));
                assertEquals(CatalogProvider.ID, rs.getString(2));
            }
            try (ResultSet rs = stat.executeQuery("select capability_name from "
                    + "information_schema.plugin_capabilities where provider_type = '"
                    + SystemCatalogProvider.TYPE + "' and provider_id = '" + CatalogProvider.ID + "'")) {
                assertTrue(rs.next());
                assertEquals(PluginCapability.SYSTEM_CATALOG, rs.getString(1));
            }
        }
    }

    private static Database database(Connection conn) {
        SessionLocal session = (SessionLocal) ((JdbcConnection) conn).getSession();
        return session.getDatabase();
    }

    /**
     * 测试用 system catalog 插件。
     */
    public static final class CatalogPlugin implements H2Plugin {
        @Override
        public String getId() {
            return "test.system.catalog";
        }

        @Override
        public String getVersion() {
            return "1";
        }

        @Override
        public String getDisplayName() {
            return "System Catalog Plugin";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new CatalogProvider());
        }
    }

    private static final class CatalogProvider implements SystemCatalogProvider {
        static final String ID = "catalog";

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return ID;
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.SYSTEM_CATALOG.equals(capability);
        }

        @Override
        public void validate(SystemCatalogContext context) {
            // No-op for the registration and diagnostics contract.
        }
    }
}
