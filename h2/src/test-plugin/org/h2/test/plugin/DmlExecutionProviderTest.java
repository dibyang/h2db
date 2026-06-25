/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;

import org.h2.api.BoundParameterBatchView;
import org.h2.api.BoundParameterView;
import org.h2.api.DmlExecutionContext;
import org.h2.api.DmlExecutionPlan;
import org.h2.api.DmlExecutionProvider;
import org.h2.api.DmlPrepareContext;
import org.h2.api.H2Plugin;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.engine.SessionLocal;
import org.h2.result.Row;
import org.h2.table.Column;
import org.h2.table.Table;
import org.junit.jupiter.api.Test;

/**
 * DML execution provider tests.
 */
public class DmlExecutionProviderTest {

    /**
     * T-DML-HOOK-PREPARE-01.
     */
    @Test
    public void providerCanIdentifySimpleInsertValuesPlan() throws Exception {
        RecordingDmlProvider.reset();

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:dmlPrepareHook;DB_CLOSE_DELAY=-1", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table test_target(id int, name varchar)");
            RecordingDmlProvider.reset();

            try (PreparedStatement prep = conn.prepareStatement(
                    "insert into test_target(id, name) values (?, ?)")) {
                assertTrue(RecordingDmlProvider.supportedPlan);
                assertEquals(1, RecordingDmlProvider.prepareCalls);
                assertEquals(DmlPrepareContext.INSERT_VALUES, RecordingDmlProvider.statementType);
                assertEquals("TEST_TARGET", RecordingDmlProvider.tableName);
                assertEquals(2, RecordingDmlProvider.columnCount);
                assertTrue(RecordingDmlProvider.batchCapable);
                assertFalse(RecordingDmlProvider.generatedKeys);

                prep.setInt(1, 1);
                prep.setString(2, "one");
                assertEquals(1, prep.executeUpdate());
                assertEquals(1, RecordingDmlProvider.executeCalls);
                assertEquals(2, RecordingDmlProvider.parameterCount);
            }

            try (ResultSet rs = stat.executeQuery("select name from test_target where id = 1")) {
                assertTrue(rs.next());
                assertEquals("one", rs.getString(1));
            }
        }
    }

    /**
     * T-DML-HOOK-FALLBACK-01.
     */
    @Test
    public void insertSelectDoesNotUsePrepareFastPathAndStillExecutesNatively() throws Exception {
        RecordingDmlProvider.reset();

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:dmlPrepareFallback;DB_CLOSE_DELAY=-1",
                "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table source_table(id int primary key)");
            stat.execute("insert into source_table values(10)");
            stat.execute("create table target_table(id int primary key)");
            RecordingDmlProvider.reset();

            assertEquals(1, stat.executeUpdate("insert into target_table select id from source_table"));
            assertEquals(0, RecordingDmlProvider.prepareCalls);
            assertEquals(0, RecordingDmlProvider.executeCalls);

            try (ResultSet rs = stat.executeQuery("select count(*) from target_table")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    /**
     * T-DML-HOOK-BATCH-01.
     */
    @Test
    public void executeBatchExposesBatchParameterView() throws Exception {
        RecordingDmlProvider.reset();

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:dmlBatchHook;DB_CLOSE_DELAY=-1", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table test_target(id int, name varchar)");
            RecordingDmlProvider.reset();

            try (PreparedStatement prep = conn.prepareStatement(
                    "insert into test_target(id, name) values (?, ?)")) {
                prep.setInt(1, 1);
                prep.setString(2, "one");
                prep.addBatch();
                prep.setInt(1, 2);
                prep.setString(2, "two");
                prep.addBatch();
                assertEquals(1, RecordingDmlProvider.prepareCalls);
                assertEquals("[1, 1]", RecordingDmlProvider.describe(prep.executeLargeBatch()));
                assertEquals(1, RecordingDmlProvider.batchExecuteCalls);
                assertEquals(2, RecordingDmlProvider.batchRowCount);
            }

            try (ResultSet rs = stat.executeQuery("select id, name from test_target order by id")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("one", rs.getString(2));
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals("two", rs.getString(2));
                assertFalse(rs.next());
            }
        }
    }

    /**
     * T-DML-HOOK-BATCH-01 clearBatch branch.
     */
    @Test
    public void clearBatchKeepsEmptyBatchSemantics() throws Exception {
        RecordingDmlProvider.reset();

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:dmlBatchClear;DB_CLOSE_DELAY=-1", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table test_target(id int, name varchar)");
            RecordingDmlProvider.reset();

            try (PreparedStatement prep = conn.prepareStatement(
                    "insert into test_target(id, name) values (?, ?)")) {
                prep.setInt(1, 1);
                prep.setString(2, "one");
                prep.addBatch();
                prep.clearBatch();
                assertEquals(0, prep.executeBatch().length);
                assertEquals(0, RecordingDmlProvider.batchExecuteCalls);
            }
        }
    }

    /**
     * T-DML-HOOK-BATCH-FAIL-01.
     */
    @Test
    public void batchFailureFallsBackToNativePartialFailureSemantics() throws Exception {
        RecordingDmlProvider.reset();

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:dmlBatchFail;DB_CLOSE_DELAY=-1", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table test_target(id int primary key, name varchar)");
            RecordingDmlProvider.reset();

            try (PreparedStatement prep = conn.prepareStatement(
                    "insert into test_target(id, name) values (?, ?)")) {
                prep.setInt(1, 1);
                prep.setString(2, "one");
                prep.addBatch();
                prep.setInt(1, 1);
                prep.setString(2, "duplicate");
                prep.addBatch();
                BatchUpdateException ex = assertThrows(BatchUpdateException.class, prep::executeBatch);
                int[] counts = ex.getUpdateCounts();
                assertEquals(2, counts.length);
                assertEquals(1, counts[0]);
                assertEquals(Statement.EXECUTE_FAILED, counts[1]);
                assertEquals(0, RecordingDmlProvider.batchExecuteCalls);
            }

            try (ResultSet rs = stat.executeQuery("select count(*) from test_target")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    /**
     * Service-loaded plugin for DML hook tests.
     */
    public static final class RecordingDmlPlugin implements H2Plugin {

        @Override
        public String getId() {
            return "test.dml.fast.path";
        }

        @Override
        public String getVersion() {
            return "1";
        }

        @Override
        public String getDisplayName() {
            return "DML Fast Path Test Plugin";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new RecordingDmlProvider());
        }
    }

    private static final class RecordingDmlProvider implements DmlExecutionProvider {

        private static int prepareCalls;
        private static boolean supportedPlan;
        private static String statementType;
        private static String tableName;
        private static int columnCount;
        private static boolean batchCapable;
        private static boolean generatedKeys;
        private static int executeCalls;
        private static int parameterCount;
        private static int batchExecuteCalls;
        private static int batchRowCount;

        static void reset() {
            prepareCalls = 0;
            supportedPlan = false;
            statementType = null;
            tableName = null;
            columnCount = 0;
            batchCapable = false;
            generatedKeys = false;
            executeCalls = 0;
            parameterCount = 0;
            batchExecuteCalls = 0;
            batchRowCount = 0;
        }

        static String describe(long[] counts) {
            ArrayList<String> values = new ArrayList<>();
            for (long count : counts) {
                values.add(Long.toString(count));
            }
            return values.toString();
        }

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return "recording-dml";
        }

        @Override
        public boolean supports(String capability) {
            return PluginCapability.DML_INSERT_VALUES_FAST_PATH.equals(capability)
                    || PluginCapability.PARAMETERS_BOUND_VIEW.equals(capability)
                    || PluginCapability.DML_INSERT_BATCH_FAST_PATH.equals(capability);
        }

        @Override
        public DmlExecutionPlan prepareDml(DmlPrepareContext context) {
            prepareCalls++;
            statementType = context.getStatementType();
            tableName = context.getTableName();
            columnCount = context.getColumnCount();
            batchCapable = context.isBatchCapable();
            generatedKeys = context.requestsGeneratedKeys();
            supportedPlan = DmlPrepareContext.INSERT_VALUES.equals(statementType);
            return supportedPlan ? SupportedPlan.INSTANCE : DmlExecutionPlan.NONE;
        }
    }

    private static final class SupportedPlan implements DmlExecutionPlan {

        private static final SupportedPlan INSTANCE = new SupportedPlan();

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public long execute(DmlExecutionContext context) {
            RecordingDmlProvider.executeCalls++;
            BoundParameterView parameters = context.getParameters();
            RecordingDmlProvider.parameterCount = parameters.size();
            insertRow(context, parameters);
            return 1;
        }

        @Override
        public long[] executeBatch(DmlExecutionContext context) {
            RecordingDmlProvider.batchExecuteCalls++;
            BoundParameterBatchView batch = context.getBatchParameters();
            RecordingDmlProvider.batchRowCount = batch.size();
            long[] counts = new long[batch.size()];
            for (int i = 0; i < batch.size(); i++) {
                insertRow(context, batch.get(i));
                counts[i] = 1;
            }
            return counts;
        }

        private void insertRow(DmlExecutionContext context, BoundParameterView parameters) {
            SessionLocal session = (SessionLocal) context.getSession();
            Table table = (Table) context.getTable();
            Row row = table.getTemplateRow();
            Column[] columns = table.getColumns();
            for (int i = 0; i < parameters.size(); i++) {
                row.setValue(columns[i].getColumnId(), parameters.getValue(i));
            }
            table.convertInsertRow(session, row, null);
            table.addRow(session, row);
        }
    }
}
