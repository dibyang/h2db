/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.List;

import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.StorageEngine;
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
}
