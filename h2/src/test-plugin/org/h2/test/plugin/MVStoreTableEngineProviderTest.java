/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.StorageEngine;
import org.h2.api.TableEngine;
import org.h2.api.TableEngineContext;
import org.h2.api.TableEngineProvider;
import org.h2.command.ddl.CreateTableData;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.Trace;
import org.h2.mvstore.db.MVStoreTableEngineProvider;
import org.h2.mvstore.db.MVTable;
import org.h2.schema.Schema;
import org.h2.table.Column;
import org.h2.table.Table;
import org.h2.value.TypeInfo;
import org.junit.jupiter.api.Test;

/**
 * MVStore table provider 的 JUnit 验证。
 */
public class MVStoreTableEngineProviderTest {

    /**
     * T-PLUGIN-BUILTIN-MVSTORE-PROVIDER-01.
     */
    @Test
    public void registersBuiltinMvStoreTableProvider() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginTableProviderRegistry", "sa", "")) {
            Database db = database(conn);
            PluginProvider provider = db.getPluginRegistry().findProvider(
                    TableEngineProvider.TYPE, MVStoreTableEngineProvider.ID);

            assertNotNull(provider);
            assertTrue(provider.supports(PluginCapability.TABLE_CREATE));
        }
    }

    /**
     * T-PLUGIN-DEFAULT-TABLE-ENGINE-01.
     */
    @Test
    public void createsMvTableThroughProvider() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginTableProviderCreate", "sa", "")) {
            SessionLocal session = (SessionLocal) ((JdbcConnection) conn).getSession();
            Database db = session.getDatabase();
            TableEngineProvider provider = (TableEngineProvider) db.getPluginRegistry().findProvider(
                    TableEngineProvider.TYPE, MVStoreTableEngineProvider.ID);
            CreateTableData data = createTableData(session, "P3_TABLE");

            Table table = provider.createTable(data, new Context(db, data.schema));

            assertTrue(table instanceof MVTable);
        }
    }

    /**
     * T-PLUGIN-CREATE-SQL-COMPAT-01.
     */
    @Test
    public void defaultCreateTableDoesNotWriteMvstoreEngineToScript() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginCreateSqlCompat", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table test(id int primary key)");

            try (java.sql.ResultSet rs = stat.executeQuery("script nodata")) {
                StringBuilder script = new StringBuilder();
                while (rs.next()) {
                    script.append(rs.getString(1)).append('\n');
                }
                assertTrue(script.toString().contains("CREATE MEMORY TABLE"));
                assertFalse(script.toString().contains("ENGINE"));
            }
        }
    }

    /**
     * T-PLUGIN-P4-BUILTIN-PROVIDER-PATH-01.
     */
    @Test
    public void defaultCreateTableDoesNotUseLegacyTableEngineCache() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginDefaultProviderPath", "sa", "");
                Statement stat = conn.createStatement()) {
            Database db = database(conn);

            stat.execute("create table test(id int primary key)");

            assertTrue(legacyTableEngines(db).isEmpty());
            assertNotNull(db.getPluginRegistry().findProvider(TableEngineProvider.TYPE,
                    MVStoreTableEngineProvider.ID));
        }
    }

    /**
     * T-PLUGIN-LEGACY-TABLE-ENGINE-01.
     */
    @Test
    public void keepsLegacyExplicitTableEngineClassName() throws Exception {
        RecordingTableEngine.createTableData = null;
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginLegacyEngine;MODE=REGULAR", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table legacy(id int) engine \"" + RecordingTableEngine.class.getName() + "\"");

            assertNotNull(RecordingTableEngine.createTableData);
            assertTrue(RecordingTableEngine.class.getName().equals(RecordingTableEngine.createTableData.tableEngine));
        }
    }

    /**
     * T-PLUGIN-DEFAULT-TABLE-ENGINE-CLASSNAME-01.
     */
    @Test
    public void keepsLegacyDefaultTableEngineClassName() throws Exception {
        RecordingTableEngine.createTableData = null;
        String url = "jdbc:h2:mem:pluginDefaultLegacyEngine;DEFAULT_TABLE_ENGINE="
                + RecordingTableEngine.class.getName();
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table default_legacy(id int)");

            assertNotNull(RecordingTableEngine.createTableData);
            assertTrue(RecordingTableEngine.class.getName().equals(RecordingTableEngine.createTableData.tableEngine));
        }
    }

    private static CreateTableData createTableData(SessionLocal session, String tableName) {
        Database db = session.getDatabase();
        CreateTableData data = new CreateTableData();
        data.schema = db.getMainSchema();
        data.tableName = tableName;
        data.id = db.allocateObjectId();
        data.session = session;
        data.persistData = db.isPersistent();
        data.persistIndexes = db.isPersistent();
        data.columns.add(new Column("ID", TypeInfo.TYPE_INTEGER));
        return data;
    }

    private static Database database(Connection conn) {
        SessionLocal session = (SessionLocal) ((JdbcConnection) conn).getSession();
        return session.getDatabase();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TableEngine> legacyTableEngines(Database db) throws Exception {
        Field field = Database.class.getDeclaredField("tableEngines");
        field.setAccessible(true);
        return (Map<String, TableEngine>) field.get(db);
    }

    private static final class Context implements TableEngineContext {
        private final Database database;
        private final Schema schema;

        Context(Database database, Schema schema) {
            this.database = database;
            this.schema = schema;
        }

        @Override
        public Database getDatabase() {
            return database;
        }

        @Override
        public Schema getSchema() {
            return schema;
        }

        @Override
        public StorageEngine getStorageEngine() {
            return database.getStorageEngine();
        }

        @Override
        public Trace getTrace() {
            return database.getTrace(Trace.DATABASE);
        }

        @Override
        public List<String> getTableEngineParams() {
            return Collections.emptyList();
        }

        @Override
        public boolean isPersistent() {
            return database.isPersistent();
        }

        @Override
        public boolean isReadOnly() {
            return database.isReadOnly();
        }
    }

    /**
     * 旧 TableEngine 类名加载路径使用的测试引擎。
     */
    public static final class RecordingTableEngine implements TableEngine {
        static CreateTableData createTableData;

        @Override
        public Table createTable(CreateTableData data) {
            createTableData = data;
            return data.session.getDatabase().getStore().createTable(data);
        }
    }
}
