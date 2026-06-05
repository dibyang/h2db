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

import org.h2.api.DatabaseLifecycleContext;
import org.h2.api.DatabaseLifecycleProvider;
import org.h2.api.H2Plugin;
import org.h2.api.PluginCapability;
import org.h2.api.PluginProvider;
import org.h2.mvstore.db.MVStoreStorageEngineProvider;
import org.junit.jupiter.api.Test;

/**
 * Database lifecycle provider contract tests.
 */
public class DatabaseLifecycleProviderTest {

    /**
     * T-PLUGIN-P13-DB-LIFECYCLE-CLOSE-01.
     */
    @Test
    public void lifecycleProviderReceivesCloseEvents() throws Exception {
        RecordingLifecycleProvider.reset(true);
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginLifecycleClose", "sa", "");
                Statement stat = conn.createStatement()) {
            stat.execute("create table lifecycle_close(id int)");
        } finally {
            RecordingLifecycleProvider.disable();
        }

        assertEquals(Arrays.asList("beforeClose", "afterClose"), RecordingLifecycleProvider.events);
        assertTrue(RecordingLifecycleProvider.databaseName.contains("pluginLifecycleClose"));
        assertTrue(RecordingLifecycleProvider.databaseUrl.contains("pluginLifecycleClose"));
        assertEquals(MVStoreStorageEngineProvider.ID, RecordingLifecycleProvider.storageEngineId);
        assertTrue(!RecordingLifecycleProvider.persistent);
        assertTrue(!RecordingLifecycleProvider.fromShutdownHook);
    }

    /**
     * T-PLUGIN-P13-DB-LIFECYCLE-DIAGNOSTIC-01.
     */
    @Test
    public void lifecycleProviderIsVisibleInDiagnostics() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginLifecycleDiagnostics", "sa", "");
                Statement stat = conn.createStatement()) {
            try (ResultSet rs = stat.executeQuery("select provider_type, provider_id from "
                    + "information_schema.plugin_providers where plugin_id = 'test.database.lifecycle'")) {
                assertTrue(rs.next());
                assertEquals(DatabaseLifecycleProvider.TYPE, rs.getString(1));
                assertEquals(RecordingLifecycleProvider.ID, rs.getString(2));
            }
            try (ResultSet rs = stat.executeQuery("select capability_name from "
                    + "information_schema.plugin_capabilities where provider_type = '"
                    + DatabaseLifecycleProvider.TYPE + "' and provider_id = '" + RecordingLifecycleProvider.ID
                    + "'")) {
                assertTrue(rs.next());
                assertEquals(PluginCapability.DATABASE_LIFECYCLE, rs.getString(1));
            }
        }
    }

    /**
     * T-PLUGIN-P13-DB-LIFECYCLE-FAILURE-01.
     */
    @Test
    public void lifecycleProviderFailureIncludesDiagnosticsAfterCloseCompletes() throws Exception {
        RecordingLifecycleProvider.reset(true);
        RecordingLifecycleProvider.failAfterClose = true;
        Connection conn = DriverManager.getConnection("jdbc:h2:mem:pluginLifecycleFailure", "sa", "");
        try {
            conn.close();
            fail("close should report lifecycle provider failure");
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("Database lifecycle provider failed"));
            assertTrue(e.getMessage().contains("provider=" + RecordingLifecycleProvider.ID));
            assertTrue(e.getMessage().contains("event=AFTER_CLOSE"));
            assertTrue(e.getMessage().contains("storage=" + MVStoreStorageEngineProvider.ID));
        } finally {
            RecordingLifecycleProvider.disable();
        }

        try (Connection reopened = DriverManager.getConnection("jdbc:h2:mem:pluginLifecycleFailure", "sa", "")) {
            assertTrue(!reopened.isClosed());
        }
    }

    public static final class LifecyclePlugin implements H2Plugin {
        @Override
        public String getId() {
            return "test.database.lifecycle";
        }

        @Override
        public String getVersion() {
            return "1";
        }

        @Override
        public String getDisplayName() {
            return "Database Lifecycle Plugin";
        }

        @Override
        public Iterable<? extends PluginProvider> getProviders() {
            return Arrays.asList(new RecordingLifecycleProvider());
        }
    }

    public static final class RecordingLifecycleProvider implements DatabaseLifecycleProvider {
        static final String ID = "recording_database_lifecycle";
        static final ArrayList<String> events = new ArrayList<>();
        static boolean enabled;
        static boolean failAfterClose;
        static String databaseName;
        static String databaseUrl;
        static String storageEngineId;
        static boolean persistent;
        static boolean fromShutdownHook;

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
            return PluginCapability.DATABASE_LIFECYCLE.equals(capability);
        }

        @Override
        public void beforeClose(DatabaseLifecycleContext context) {
            if (enabled) {
                record("beforeClose", context);
            }
        }

        @Override
        public void afterClose(DatabaseLifecycleContext context) {
            if (!enabled) {
                return;
            }
            record("afterClose", context);
            if (failAfterClose) {
                throw new IllegalStateException("simulated lifecycle provider failure");
            }
        }

        static void reset(boolean newEnabled) {
            events.clear();
            enabled = newEnabled;
            failAfterClose = false;
            databaseName = null;
            databaseUrl = null;
            storageEngineId = null;
            persistent = false;
            fromShutdownHook = false;
        }

        static void disable() {
            enabled = false;
            failAfterClose = false;
        }

        private static void record(String event, DatabaseLifecycleContext context) {
            events.add(event);
            databaseName = context.getDatabaseName();
            databaseUrl = context.getDatabaseUrl();
            storageEngineId = context.getStorageEngineId();
            persistent = context.isPersistent();
            fromShutdownHook = context.isFromShutdownHook();
        }
    }
}
