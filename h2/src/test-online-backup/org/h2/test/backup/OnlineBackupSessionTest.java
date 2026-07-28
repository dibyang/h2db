/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.h2.api.MaterializedParticipantArtifact;
import org.h2.api.OnlineBackupContext;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.OnlineBackupParticipantProvider;
import org.h2.api.ParticipantArtifactTarget;
import org.h2.api.PluginCapability;
import org.h2.api.PreparedBackupParticipant;
import org.h2.api.PreparedParticipantMetadata;
import org.h2.engine.Database;
import org.h2.engine.PluginSource;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.OnlineBackupSession;
import org.h2.engine.backup.OnlineBackupSession.State;
import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P4 participant ordering, deadline, cleanup, and session-state tests.
 */
public class OnlineBackupSessionTest {

    @TempDir
    Path directory;

    /**
     * Explicit selection is stable-sorted and unselected providers are not
     * called.
     */
    @Test
    public void explicitParticipantsPrepareInStableOrder() throws Exception {
        List<String> events = new ArrayList<>();
        List<OnlineBackupContext> contexts = new ArrayList<>();
        try (Connection connection = connect("order")) {
            Database database = database(connection);
            register(database, new FakeProvider("c", events, contexts));
            register(database, new FakeProvider("a", events, contexts));
            register(database, new FakeProvider("b", events, contexts));

            OnlineBackupOptions options = new OnlineBackupOptions(
                    Arrays.asList("b", "a"), 5_000L, 30_000L);
            OnlineBackupSession session =
                    OnlineBackupSession.prepare(database, options);
            assertEquals(State.PREPARED, session.getState());
            assertEquals(Arrays.asList("prepare:a", "prepare:b"), events);
            assertEquals(Arrays.asList("a", "b"), Arrays.asList(
                    session.getParticipantMetadata().get(0)
                            .getParticipantId(),
                    session.getParticipantMetadata().get(1)
                            .getParticipantId()));
            assertEquals("test.a",
                    session.getParticipants().get(0).getPluginId());
            assertEquals("1",
                    session.getParticipants().get(0).getPluginVersion());
            assertSame(contexts.get(0), contexts.get(1));
            assertEquals(database.getOnlineBackupMetadata().requireSnapshot()
                    .getDatabaseId(), session.getContext().getDatabaseId());

            session.close();
            session.close();
            assertEquals(State.CLOSED, session.getState());
            assertEquals(Arrays.asList("prepare:a", "prepare:b",
                    "abort:b", "abort:a"), events);
        }
    }

    /**
     * Failure aborts completed participants in reverse order and preserves all
     * cleanup failures as suppressed exceptions.
     */
    @Test
    public void prepareFailureUsesReverseCleanupWithSuppressedFailures()
            throws Exception {
        List<String> events = new ArrayList<>();
        try (Connection connection = connect("failure")) {
            Database database = database(connection);
            register(database, new FakeProvider("a", events, null)
                    .failAbort("cleanup-a"));
            register(database, new FakeProvider("b", events, null)
                    .failAbort("cleanup-b"));
            register(database, new FakeProvider("c", events, null)
                    .failPrepare("prepare-c"));

            Exception failure = assertThrows(Exception.class,
                    () -> OnlineBackupSession.prepare(database,
                            new OnlineBackupOptions(
                                    Arrays.asList("c", "b", "a"),
                                    5_000L, 30_000L)));
            assertEquals("prepare-c", failure.getMessage());
            assertEquals(2, failure.getSuppressed().length);
            assertEquals("cleanup-b",
                    failure.getSuppressed()[0].getMessage());
            assertEquals("cleanup-a",
                    failure.getSuppressed()[1].getMessage());
            assertEquals(Arrays.asList("prepare:a", "prepare:b",
                    "prepare:c", "abort:b", "abort:a"), events);
            assertTrue(database.getStore().getMvStore().isSpaceReused());
        }
    }

    /**
     * Duplicate, unknown, and incapable providers fail before the backup
     * barrier is entered.
     */
    @Test
    public void invalidSelectionFailsBeforeBarrier() throws Exception {
        List<String> events = new ArrayList<>();
        try (Connection connection = connect("validation")) {
            Database database = database(connection);
            register(database, new FakeProvider("a", events, null));
            register(database, new FakeProvider("incapable", events, null)
                    .incapable());
            long before = database.getOperationGate().getMetrics()
                    .getBackupBarrierCount();

            assertThrows(IllegalArgumentException.class,
                    () -> OnlineBackupSession.prepare(database,
                            options("a", "a")));
            assertThrows(IllegalArgumentException.class,
                    () -> OnlineBackupSession.prepare(database,
                            options("missing")));
            assertThrows(IllegalArgumentException.class,
                    () -> OnlineBackupSession.prepare(database,
                            options("incapable")));
            assertEquals(before, database.getOperationGate().getMetrics()
                    .getBackupBarrierCount());
            assertTrue(events.isEmpty());
        }
    }

    /**
     * All providers observe one absolute deadline and later providers only see
     * the remaining budget.
     */
    @Test
    public void participantsShareOneDeadline() throws Exception {
        List<String> events = new ArrayList<>();
        List<OnlineBackupContext> contexts = new ArrayList<>();
        List<Long> budgets = new ArrayList<>();
        try (Connection connection = connect("deadline")) {
            Database database = database(connection);
            register(database, new FakeProvider("a", events, contexts)
                    .recordBudgets(budgets).sleep(75L));
            register(database, new FakeProvider("b", events, contexts)
                    .recordBudgets(budgets));
            try (OnlineBackupSession ignored = OnlineBackupSession.prepare(
                    database, new OnlineBackupOptions(
                            Arrays.asList("b", "a"), 1_000L, 30_000L))) {
                assertSame(contexts.get(0), contexts.get(1));
                assertTrue(budgets.get(0) > budgets.get(1));
            }

            events.clear();
            register(database, new FakeProvider("slow", events, null)
                    .sleep(75L));
            Exception timeout = assertThrows(Exception.class,
                    () -> OnlineBackupSession.prepare(database,
                            new OnlineBackupOptions(
                                    Collections.singletonList("slow"),
                                    25L, 30_000L)));
            assertFalse(timeout.getMessage().isEmpty());
            assertEquals(Arrays.asList("prepare:slow", "abort:slow"),
                    events);
        }
    }

    /**
     * The active H2 snapshot limits a database to one prepared session.
     */
    @Test
    public void onePreparedSessionPerDatabase() throws Exception {
        try (Connection connection = connect("single-session")) {
            Database database = database(connection);
            OnlineBackupOptions options = new OnlineBackupOptions(
                    Collections.<String>emptyList(), 5_000L, 30_000L);
            OnlineBackupSession first =
                    OnlineBackupSession.prepare(database, options);
            assertThrows(IllegalStateException.class,
                    () -> OnlineBackupSession.prepare(database, options));
            first.close();
            OnlineBackupSession second =
                    OnlineBackupSession.prepare(database, options);
            assertNotSame(first, second);
            second.abort();
            second.abort();
            assertEquals(State.ABORTED, second.getState());
        }
    }

    /**
     * Database shutdown closes the active participant session and releases all
     * prepared resources.
     */
    @Test
    public void databaseCloseAbortsActiveSession() throws Exception {
        List<String> events = new ArrayList<>();
        Connection connection = connect("database-close");
        Database database = database(connection);
        register(database, new FakeProvider("a", events, null));
        OnlineBackupSession session = OnlineBackupSession.prepare(database,
                options("a"));
        connection.close();
        assertEquals(State.CLOSED, session.getState());
        assertEquals(Arrays.asList("prepare:a", "abort:a"), events);
    }

    private OnlineBackupOptions options(String... participantIds) {
        return new OnlineBackupOptions(Arrays.asList(participantIds),
                5_000L, 30_000L);
    }

    private static void register(Database database, FakeProvider provider) {
        database.getPluginRegistry().registerProvider(
                "test." + provider.getId(), "1", provider,
                PluginSource.CONFIGURED_CLASS);
    }

    private Connection connect(String name) throws Exception {
        String url = "jdbc:h2:"
                + directory.resolve(name).toAbsolutePath().toString()
                        .replace(File.separatorChar, '/')
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + UUID.randomUUID();
        return DriverManager.getConnection(url, "sa", "");
    }

    private static Database database(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection).getSession())
                .getDatabase();
    }

    private static final class FakeProvider
            implements OnlineBackupParticipantProvider {

        private final String id;
        private final List<String> events;
        private final List<OnlineBackupContext> contexts;
        private boolean capable = true;
        private long sleepMillis;
        private String prepareFailure;
        private String abortFailure;
        private List<Long> budgets;

        FakeProvider(String id, List<String> events,
                List<OnlineBackupContext> contexts) {
            this.id = id;
            this.events = events;
            this.contexts = contexts;
        }

        FakeProvider incapable() {
            capable = false;
            return this;
        }

        FakeProvider sleep(long millis) {
            sleepMillis = millis;
            return this;
        }

        FakeProvider recordBudgets(List<Long> values) {
            budgets = values;
            return this;
        }

        FakeProvider failPrepare(String message) {
            prepareFailure = message;
            return this;
        }

        FakeProvider failAbort(String message) {
            abortFailure = message;
            return this;
        }

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public boolean supports(String capability) {
            return capable
                    && PluginCapability.ONLINE_BACKUP_PREPARE
                            .equals(capability);
        }

        @Override
        public PreparedBackupParticipant prepare(OnlineBackupContext context)
                throws Exception {
            events.add("prepare:" + id);
            if (contexts != null) {
                contexts.add(context);
            }
            if (budgets != null) {
                budgets.add(context.getRemainingMillis());
            }
            if (sleepMillis > 0L) {
                Thread.sleep(sleepMillis);
            }
            if (prepareFailure != null) {
                throw new Exception(prepareFailure);
            }
            return new FakePrepared(id, events, abortFailure);
        }
    }

    private static final class FakePrepared
            implements PreparedBackupParticipant {

        private final String id;
        private final List<String> events;
        private final String abortFailure;
        private boolean aborted;

        FakePrepared(String id, List<String> events, String abortFailure) {
            this.id = id;
            this.events = events;
            this.abortFailure = abortFailure;
        }

        @Override
        public PreparedParticipantMetadata getPreparedMetadata() {
            return new PreparedParticipantMetadata(id, "snapshot-" + id,
                    Collections.<String, String>emptyMap());
        }

        @Override
        public MaterializedParticipantArtifact materialize(
                ParticipantArtifactTarget target) {
            return new MaterializedParticipantArtifact(id,
                    Collections.<String>emptyList());
        }

        @Override
        public void abort() throws Exception {
            if (!aborted) {
                aborted = true;
                events.add("abort:" + id);
                if (abortFailure != null) {
                    throw new Exception(abortFailure);
                }
            }
        }
    }
}
