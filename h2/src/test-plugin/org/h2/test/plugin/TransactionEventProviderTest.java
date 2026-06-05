/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;

import org.h2.api.H2Plugin;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.api.TransactionContext;
import org.h2.api.TransactionEventProvider;
import org.junit.jupiter.api.Test;

/**
 * Transaction event provider contract tests.
 */
public class TransactionEventProviderTest {

    /**
     * T-PLUGIN-P11-TX-EVENT-COMMIT-01.
     */
    @Test
    public void transactionProviderReceivesCommitEvents() throws Exception {
        RecordingTransactionProvider.reset(false);
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginTxCommit", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table tx_commit(id int)");
            RecordingTransactionProvider.reset(true);

            conn.setAutoCommit(false);
            stat.execute("insert into tx_commit values(1)");
            conn.commit();
        } finally {
            RecordingTransactionProvider.disable();
        }

        assertEquals(Arrays.asList("beforeCommit", "afterCommit"), RecordingTransactionProvider.events);
        assertTrue(RecordingTransactionProvider.sessionId > 0);
        assertTrue(RecordingTransactionProvider.hadTransaction);
        assertTrue(!RecordingTransactionProvider.ddl);
    }

    /**
     * T-PLUGIN-P11-TX-EVENT-ROLLBACK-01.
     */
    @Test
    public void transactionProviderReceivesRollbackEvents() throws Exception {
        RecordingTransactionProvider.reset(false);
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginTxRollback", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table tx_rollback(id int)");
            RecordingTransactionProvider.reset(true);

            conn.setAutoCommit(false);
            stat.execute("insert into tx_rollback values(1)");
            conn.rollback();
        } finally {
            RecordingTransactionProvider.disable();
        }

        assertEquals(Arrays.asList("beforeRollback", "afterRollback"), RecordingTransactionProvider.events);
        assertTrue(RecordingTransactionProvider.sessionId > 0);
        assertTrue(RecordingTransactionProvider.hadTransaction);
    }

    /**
     * T-PLUGIN-P11-TX-EVENT-DIAGNOSTIC-01.
     */
    @Test
    public void transactionProviderIsVisibleInDiagnostics() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginTxDiagnostics", "sa", "");
                Statement stat = conn.createStatement()) {
            try (ResultSet rs = stat.executeQuery("select provider_type, provider_id from "
                    + "information_schema.plugin_providers where plugin_id = 'test.transaction.events'")) {
                assertTrue(rs.next());
                assertEquals(TransactionEventProvider.TYPE, rs.getString(1));
                assertEquals(RecordingTransactionProvider.ID, rs.getString(2));
            }
            try (ResultSet rs = stat.executeQuery("select capability_name from "
                    + "information_schema.plugin_capabilities where provider_type = '"
                    + TransactionEventProvider.TYPE + "' and provider_id = '" + RecordingTransactionProvider.ID
                    + "'")) {
                assertTrue(rs.next());
                assertEquals(PluginCapability.TRANSACTION_EVENTS, rs.getString(1));
            }
        }
    }

    /**
     * T-PLUGIN-P11-TX-EVENT-FAILURE-01.
     */
    @Test
    public void transactionProviderFailureIncludesDiagnostics() throws Exception {
        RecordingTransactionProvider.reset(false);
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginTxFailure", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table tx_failure(id int)");
            RecordingTransactionProvider.reset(true);
            RecordingTransactionProvider.failBeforeCommit = true;

            conn.setAutoCommit(false);
            stat.execute("insert into tx_failure values(1)");
            try {
                conn.commit();
                fail("commit should fail");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("Transaction event provider failed"));
                assertTrue(e.getMessage().contains("provider=" + RecordingTransactionProvider.ID));
                assertTrue(e.getMessage().contains("event=BEFORE_COMMIT"));
            }
        } finally {
            RecordingTransactionProvider.disable();
        }
    }

    public static final class TransactionPlugin implements H2Plugin {
        @Override
        public String getId() {
            return "test.transaction.events";
        }

        @Override
        public String getVersion() {
            return "1";
        }

        @Override
        public String getDisplayName() {
            return "Transaction Event Plugin";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new RecordingTransactionProvider());
        }
    }

    public static final class RecordingTransactionProvider implements TransactionEventProvider {
        static final String ID = "recording_transaction";
        static final ArrayList<String> events = new ArrayList<>();
        static boolean enabled;
        static boolean failBeforeCommit;
        static int sessionId;
        static boolean hadTransaction;
        static boolean ddl;

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
            return PluginCapability.TRANSACTION_EVENTS.equals(capability);
        }

        @Override
        public void beforeCommit(TransactionContext context) {
            if (!enabled) {
                return;
            }
            record("beforeCommit", context);
            if (failBeforeCommit) {
                throw new IllegalStateException("simulated transaction provider failure");
            }
        }

        @Override
        public void afterCommit(TransactionContext context) {
            if (enabled) {
                record("afterCommit", context);
            }
        }

        @Override
        public void beforeRollback(TransactionContext context) {
            if (enabled) {
                record("beforeRollback", context);
            }
        }

        @Override
        public void afterRollback(TransactionContext context) {
            if (enabled) {
                record("afterRollback", context);
            }
        }

        static void reset(boolean newEnabled) {
            events.clear();
            enabled = newEnabled;
            failBeforeCommit = false;
            sessionId = 0;
            hadTransaction = false;
            ddl = false;
        }

        static void disable() {
            enabled = false;
            failBeforeCommit = false;
        }

        private static void record(String event, TransactionContext context) {
            events.add(event);
            sessionId = context.getSessionId();
            hadTransaction = context.hasTransaction();
            ddl = context.isDdl();
        }
    }
}
