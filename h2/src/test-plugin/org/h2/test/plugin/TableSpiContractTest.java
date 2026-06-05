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

import org.h2.api.PluginCapability;
import org.h2.api.TableEngineProvider;
import org.h2.engine.Database;
import org.h2.engine.PluginRegistry;
import org.h2.engine.PluginSource;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;

/**
 * Table SPI contract tests.
 */
public class TableSpiContractTest {

    /**
     * T-PLUGIN-F5-TABLE-SPI-REGISTER-01.
     */
    @Test
    public void fakeTableProviderCanRegister() {
        PluginRegistry registry = new PluginRegistry();
        ContractTableProvider provider = new ContractTableProvider();

        registry.registerProvider("test.table.plugin", "1", provider, PluginSource.CONFIGURED_CLASS);

        assertEquals(provider, registry.findProvider(TableEngineProvider.TYPE, ContractTableProvider.ID));
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-CREATE-01.
     */
    @Test
    public void configuredTableProviderCanCreateTable() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableCreate;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            Database db = database(conn);
            assertNotNull(db.getPluginRegistry().findProvider(TableEngineProvider.TYPE, ContractTableProvider.ID));

            stat.execute("create table contract_table(id int primary key)");
            try (java.sql.ResultSet rs = stat.executeQuery("select count(*) from contract_table")) {
                assertTrue(rs.next());
            }

            assertEquals("CONTRACT_TABLE", ContractTableProvider.normalizedTableName());
            assertTrue(ContractTableProvider.tableEngineParams.isEmpty());
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-CRUD-01.
     */
    @Test
    public void tableProviderIntegratesWithBasicCrud() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableCrud;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table contract_crud(id int primary key, name varchar)");
            stat.execute("insert into contract_crud values(1, 'one'), (2, 'two')");

            try (java.sql.ResultSet rs = stat.executeQuery("select count(*) from contract_crud")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }

            try (java.sql.ResultSet rs = stat.executeQuery("select name from contract_crud where id = 2")) {
                assertTrue(rs.next());
                assertEquals("two", rs.getString(1));
            }
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-UPDATE-DELETE-01.
     */
    @Test
    public void tableProviderIntegratesWithUpdateAndDelete() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableUpdateDelete;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table contract_update(id int primary key, name varchar)");
            stat.execute("insert into contract_update values(1, 'one'), (2, 'two')");
            stat.execute("update contract_update set name = 'updated' where id = 2");
            stat.execute("delete from contract_update where id = 1");

            try (ResultSet rs = stat.executeQuery("select count(*), min(name) from contract_update")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("updated", rs.getString(2));
            }
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-INDEX-PLAN-01.
     */
    @Test
    public void tableProviderKeepsPrimaryKeyIndexUsable() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableIndexPlan;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table contract_index(id int primary key, name varchar)");
            stat.execute("insert into contract_index values(1, 'one'), (2, 'two')");

            try (ResultSet rs = stat.executeQuery("explain select name from contract_index where id = 2")) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).contains("PRIMARY_KEY"));
            }
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-DROP-01.
     */
    @Test
    public void tableProviderIntegratesWithDrop() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableDrop;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table contract_drop(id int primary key)");
            stat.execute("drop table contract_drop");

            try (ResultSet rs = stat.executeQuery("select count(*) from information_schema.tables "
                    + "where table_name = 'CONTRACT_DROP'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-SCRIPT-01.
     */
    @Test
    public void tableProviderCreateSqlRemainsScriptable() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableScript;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table contract_script(id int primary key)");

            StringBuilder script = new StringBuilder();
            try (ResultSet rs = stat.executeQuery("script nodata")) {
                while (rs.next()) {
                    script.append(rs.getString(1)).append('\n');
                }
            }
            assertTrue(script.toString().contains("CREATE MEMORY TABLE"));
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-PARAMS-01.
     */
    @Test
    public void explicitWithParamsIsPassedToContext() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableParams;MODE=REGULAR";

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            Database db = database(conn);
            assertNotNull(db.getPluginRegistry().findProvider(TableEngineProvider.TYPE, ContractTableProvider.ID));

            stat.execute("create table engine_param_table(id int) engine \"" + ContractTableProvider.ID
                    + "\" with \"p1\", \"p2\"");

            assertEquals("ENGINE_PARAM_TABLE", ContractTableProvider.normalizedTableName());
            assertEquals(Arrays.asList("p1", "p2"), ContractTableProvider.tableEngineParams);
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-CONTEXT-01.
     */
    @Test
    public void tableContextExposesStorageAndDatabaseState() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableContext;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table context_table(id int)");

            assertTrue(ContractTableProvider.databaseAvailable);
            assertTrue(ContractTableProvider.traceAvailable);
            assertEquals("mvstore", ContractTableProvider.storageEngineId);
            assertTrue(!ContractTableProvider.persistent);
            assertTrue(!ContractTableProvider.readOnly);
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-SCHEMA-CONTEXT-01.
     */
    @Test
    public void tableContextContainsExpectedSchema() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableSchemaContext;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create schema abc_schema");
            stat.execute("create table abc_schema.schema_context_table(id int)");

            assertEquals("SCHEMA_CONTEXT_TABLE", ContractTableProvider.tableName);
            assertEquals("ABC_SCHEMA", ContractTableProvider.schemaName);
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-SCHEMA-PARAMS-01.
     */
    @Test
    public void schemaDefaultParamsArePassedToContext() throws Exception {
        ContractTableProvider.reset();
        String url = "jdbc:h2:mem:pluginTableSchemaParams;DEFAULT_TABLE_ENGINE=" + ContractTableProvider.ID;

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create schema s with \"schema_p1\", \"schema_p2\"");
            stat.execute("create table s.schema_param_table(id int)");

            assertEquals("SCHEMA_PARAM_TABLE", ContractTableProvider.normalizedTableName());
            assertEquals(Arrays.asList("schema_p1", "schema_p2"), ContractTableProvider.tableEngineParams);
        }
    }

    /**
     * T-PLUGIN-F5-TABLE-SPI-DIAGNOSTIC-01.
     */
    @Test
    public void discoveredTableProviderIsVisibleInDiagnostics() throws Exception {
        String url = "jdbc:h2:mem:pluginTableDiagnostics";

        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            try (ResultSet rs = stat.executeQuery("select source, is_builtin from information_schema.plugins "
                    + "where plugin_id = '" + ContractTableProvider.PLUGIN_ID + "'")) {
                assertTrue(rs.next());
                assertEquals("SERVICE_LOADER", rs.getString(1));
                assertTrue(!rs.getBoolean(2));
            }
            try (ResultSet rs = stat.executeQuery("select provider_type, provider_id from "
                    + "information_schema.plugin_providers where plugin_id = '"
                    + ContractTableProvider.PLUGIN_ID + "'")) {
                assertTrue(rs.next());
                assertEquals("table", rs.getString(1));
                assertEquals(ContractTableProvider.ID, rs.getString(2));
            }
            try (ResultSet rs = stat.executeQuery("select capability_name from "
                    + "information_schema.plugin_capabilities where provider_type = 'table' "
                    + "and provider_id = '" + ContractTableProvider.ID + "'")) {
                assertTrue(rs.next());
                assertEquals(PluginCapability.TABLE_CREATE, rs.getString(1));
            }
        }
    }

    private static Database database(Connection conn) {
        SessionLocal session = (SessionLocal) ((JdbcConnection) conn).getSession();
        return session.getDatabase();
    }

}
