/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.h2.api.PluginCapability;
import org.h2.engine.Constants;
import org.junit.jupiter.api.Test;

/**
 * 插件 INFORMATION_SCHEMA 元数据的 JUnit 验证。
 */
public class PluginInformationSchemaTest {

    /**
     * T-PLUGIN-R1-SQL-METADATA-01.
     */
    @Test
    public void exposesBuiltinPluginProviderRows() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginInfoSchema;DB_CLOSE_DELAY=-1",
                "sa", "");
                Statement stat = conn.createStatement()) {
            try (ResultSet rs = stat.executeQuery("select plugin_id, plugin_version, source, is_builtin "
                    + "from information_schema.plugins where plugin_id = 'h2.mvstore'")) {
                assertTrue(rs.next());
                assertEquals("h2.mvstore", rs.getString(1));
                assertEquals(Constants.VERSION, rs.getString(2));
                assertEquals("BUILTIN", rs.getString(3));
                assertTrue(rs.getBoolean(4));
            }
            try (ResultSet rs = stat.executeQuery("select provider_type, provider_id from "
                    + "information_schema.plugin_providers where plugin_id = 'h2.mvstore' "
                    + "and provider_type = 'storage' and provider_id = 'mvstore'")) {
                assertTrue(rs.next());
                assertEquals("storage", rs.getString(1));
                assertEquals("mvstore", rs.getString(2));
            }
        }
    }

    /**
     * T-PLUGIN-R1-CAPABILITY-ROWS-01.
     */
    @Test
    public void exposesPluginCapabilityRows() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginCapabilityInfo;DB_CLOSE_DELAY=-1",
                "sa", "");
                Statement stat = conn.createStatement();
                ResultSet rs = stat.executeQuery("select capability_name from "
                        + "information_schema.plugin_capabilities where provider_type = 'storage' "
                        + "and provider_id = 'mvstore' and capability_name = '"
                        + PluginCapability.STORAGE_PERSISTENT + "'")) {
            assertTrue(rs.next());
            assertEquals(PluginCapability.STORAGE_PERSISTENT, rs.getString(1));
        }
    }

    /**
     * T-PLUGIN-P7-EXTERNAL-INFO-SCHEMA-01.
     */
    @Test
    public void exposesDiscoveredPluginProviderRows() throws Exception {
        String url = "jdbc:h2:mem:pluginDiscoveredInfo";
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            try (ResultSet rs = stat.executeQuery("select source, is_builtin from information_schema.plugins "
                    + "where plugin_id = 'test.configured'")) {
                assertTrue(rs.next());
                assertEquals("SERVICE_LOADER", rs.getString(1));
                assertTrue(!rs.getBoolean(2));
            }
            try (ResultSet rs = stat.executeQuery("select provider_type, provider_id from "
                    + "information_schema.plugin_providers where plugin_id = 'test.configured'")) {
                assertTrue(rs.next());
                assertEquals("table", rs.getString(1));
                assertEquals("external_table", rs.getString(2));
            }
            try (ResultSet rs = stat.executeQuery("select capability_name from "
                    + "information_schema.plugin_capabilities where provider_type = 'table' "
                    + "and provider_id = 'external_table'")) {
                assertTrue(rs.next());
                assertEquals(PluginCapability.TABLE_CREATE, rs.getString(1));
            }
        }
    }
}
