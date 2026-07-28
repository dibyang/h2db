/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.h2.api.ErrorCode;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.DatabaseOperationGate;
import org.h2.engine.backup.DatabaseOperationGate.BackupBarrier;
import org.h2.engine.backup.DatabaseOperationGate.FailureReason;
import org.h2.engine.backup.DatabaseOperationGate.Metrics;
import org.h2.engine.backup.DatabaseOperationGate.State;
import org.h2.engine.backup.DatabaseOperationGate.TransactionDrain;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.junit.jupiter.api.Test;

/**
 * P1 database operation gate contract tests.
 */
public class DatabaseOperationGateTest {

    /**
     * T-H2BR-FEATURE-DISABLED-01 /
     * T-H2BR-FEATURE-ENABLED-OPEN-01 /
     * T-H2BR-FEATURE-CONFLICT-01 /
     * T-H2BR-FEATURE-HOT-SWITCH-REJECT-01。
     */
    @Test
    public void coordinationIsStartupOnlyAndDisabledByDefault() throws Exception {
        String defaultName = databaseName("feature-default");
        try (Connection connection = connect(defaultName, null)) {
            assertFalse(localSession(connection).getDatabase().getSettings().onlineBackupCoordination);
            assertNull(gate(connection));
            assertThrows(SQLException.class,
                    () -> connection.createStatement().execute("SET ONLINE_BACKUP_COORDINATION TRUE"));
        }

        String enabledName = databaseName("feature-enabled");
        try (Connection keeper = connect(enabledName, true)) {
            assertTrue(localSession(keeper).getDatabase().getSettings().onlineBackupCoordination);
            assertNotNull(gate(keeper));
            assertThrows(SQLException.class, () -> connect(enabledName, false));
        }
    }

    /**
     * T-H2BR-GATE-COMMIT-01。
     */
    @Test
    public void backupBarrierBlocksCommitUntilReleased() throws Exception {
        String name = databaseName("commit");
        try (Connection connection = connect(name, true);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR)");
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO TEST VALUES(1, 'one')");
            DatabaseOperationGate gate = gate(connection);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try (BackupBarrier barrier = gate.beginBackupBarrier(5_000)) {
                Future<Void> commit = executor.submit(() -> {
                    connection.commit();
                    return null;
                });

                waitUntil(() -> gate.getMetrics().getWaitingCommits() == 1);
                assertFalse(commit.isDone());
                synchronized (localSession(connection)) {
                    assertEquals(1, gate.getMetrics().getWaitingCommits());
                }

                barrier.close();
                commit.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertEquals(0, gate.getMetrics().getActiveTransactions());
            assertEquals(0, gate.getMetrics().getActiveCommits());
            assertTrue(gate.getMetrics().getMaxAdmissionWaitNanos() > 0L);
        }
    }

    /**
     * T-H2BR-GATE-DDL-01。
     */
    @Test
    public void backupBarrierBlocksDdlBeforeSessionMonitor() throws Exception {
        String name = databaseName("ddl");
        try (Connection keeper = connect(name, true);
                Connection ddlConnection = connect(name, true)) {
            DatabaseOperationGate gate = gate(keeper);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try (BackupBarrier barrier = gate.beginBackupBarrier(5_000)) {
                Future<Void> ddl = executor.submit(() -> {
                    try (Statement statement = ddlConnection.createStatement()) {
                        statement.execute("CREATE TABLE BLOCKED_DDL(ID INT)");
                    }
                    return null;
                });

                waitUntil(() -> gate.getMetrics().getWaitingDdl() == 1);
                assertFalse(ddl.isDone());
                synchronized (localSession(ddlConnection)) {
                    assertEquals(1, gate.getMetrics().getWaitingDdl());
                }

                barrier.close();
                ddl.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertEquals(0, gate.getMetrics().getActiveDdl());
            assertTrue(gate.getMetrics().getMaxAdmissionWaitNanos() > 0L);
        }
    }

    /**
     * T-H2BR-GATE-TIMEOUT-01。
     */
    @Test
    public void barrierTimeoutRestoresOpenState() {
        DatabaseOperationGate gate = new DatabaseOperationGate();
        gate.enterCommit();
        try {
            DbException timeout = assertThrows(DbException.class,
                    () -> gate.beginBackupBarrier(1));
            assertEquals(ErrorCode.LOCK_TIMEOUT_1, timeout.getErrorCode());
            Metrics metrics = gate.getMetrics();
            assertEquals(State.OPEN, metrics.getState());
            assertEquals(1, metrics.getBackupBarrierTimeoutCount());
            assertTrue(metrics.getLastBackupBarrierWaitNanos() > 0L);
            assertTrue(metrics.getMaxBackupBarrierWaitNanos()
                    >= metrics.getLastBackupBarrierWaitNanos());
            assertEquals(FailureReason.BACKUP_BARRIER_TIMEOUT,
                    metrics.getLastFailureReason());
        } finally {
            gate.exitCommit();
        }
    }

    /**
     * T-H2BR-GATE-INTERRUPT-01。
     */
    @Test
    public void interruptedWaitPreservesInterruptStatus() throws Exception {
        DatabaseOperationGate gate = new DatabaseOperationGate();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);

        try (BackupBarrier barrier = gate.beginBackupBarrier(5_000)) {
            Thread waiter = new Thread(() -> {
                try {
                    gate.enterCommit();
                } catch (Throwable e) {
                    failure.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                } finally {
                    finished.countDown();
                }
            }, "online-backup-gate-interrupt");
            waiter.start();
            waitUntil(() -> gate.getMetrics().getWaitingCommits() == 1);

            waiter.interrupt();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertTrue(failure.get() instanceof DbException);
            assertEquals(ErrorCode.STATEMENT_WAS_CANCELED,
                    ((DbException) failure.get()).getErrorCode());
            assertTrue(interrupted.get());
            assertEquals(FailureReason.INTERRUPTED,
                    gate.getMetrics().getLastFailureReason());
        }
    }

    /**
     * T-H2BR-BEGIN-DRAIN-RACE-01。
     */
    @Test
    public void transactionDrainWaitsForExistingTransactionAndRejectsNewOne()
            throws Exception {
        String name = databaseName("drain");
        try (Connection active = connect(name, true);
                Connection rejected = connect(name, true);
                Statement activeStatement = active.createStatement()) {
            activeStatement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            active.setAutoCommit(false);
            activeStatement.executeUpdate("INSERT INTO TEST VALUES(1)");
            DatabaseOperationGate gate = gate(active);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<TransactionDrain> drainFuture =
                        executor.submit(() -> gate.beginTransactionDrain(5_000));
                waitUntil(() -> gate.getMetrics().getState() == State.TRANSACTION_DRAIN);
                assertFalse(drainFuture.isDone());

                active.commit();
                TransactionDrain drain = drainFuture.get(5, TimeUnit.SECONDS);
                try {
                    assertThrows(SQLException.class, () -> {
                        try (Statement statement = rejected.createStatement()) {
                            statement.executeUpdate("INSERT INTO TEST VALUES(2)");
                        }
                    });
                } finally {
                    drain.close();
                }

                try (Statement statement = rejected.createStatement()) {
                    assertEquals(1, statement.executeUpdate("INSERT INTO TEST VALUES(2)"));
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * T-H2BR-GATE-DEADLOCK-01。
     */
    @Test
    public void nestedCommitAndDdlAdmissionDoesNotDeadlockBarrier() throws Exception {
        DatabaseOperationGate gate = new DatabaseOperationGate();
        gate.enterCommit();
        gate.enterDdl();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BackupBarrier> barrier =
                    executor.submit(() -> gate.beginBackupBarrier(5_000));
            waitUntil(() -> gate.getMetrics().getState() == State.BACKUP_BARRIER);
            assertFalse(barrier.isDone());

            gate.exitDdl();
            gate.exitCommit();
            try (BackupBarrier acquired = barrier.get(5, TimeUnit.SECONDS)) {
                assertEquals(State.BACKUP_BARRIER, gate.getMetrics().getState());
            }
        } finally {
            Metrics metrics = gate.getMetrics();
            if (metrics.getActiveDdl() != 0) {
                gate.exitDdl();
            }
            if (metrics.getActiveCommits() != 0) {
                gate.exitCommit();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /**
     * T-H2BR-GATE-CLOSE-01。
     */
    @Test
    public void databaseCloseShutsDownGate() throws Exception {
        Connection connection = connect(databaseName("close"), true);
        DatabaseOperationGate gate = gate(connection);
        connection.close();

        assertEquals(State.CLOSED, gate.getMetrics().getState());
        DbException closed = assertThrows(DbException.class, gate::enterCommit);
        assertEquals(ErrorCode.DATABASE_IS_CLOSED, closed.getErrorCode());
        assertEquals(FailureReason.CLOSED,
                gate.getMetrics().getLastFailureReason());
    }

    private static Connection connect(String name, Boolean enabled)
            throws SQLException {
        StringBuilder url = new StringBuilder("jdbc:h2:mem:").append(name);
        if (enabled != null) {
            url.append(";ONLINE_BACKUP_COORDINATION=").append(enabled);
            if (enabled) {
                url.append(";ONLINE_BACKUP_GENERATION_ID=")
                        .append(UUID.nameUUIDFromBytes(
                                name.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return DriverManager.getConnection(url.toString(), "sa", "");
    }

    private static SessionLocal localSession(Connection connection) {
        return (SessionLocal) ((JdbcConnection) connection).getSession();
    }

    private static DatabaseOperationGate gate(Connection connection) {
        return localSession(connection).getDatabase().getOperationGate();
    }

    private static String databaseName(String prefix) {
        return prefix + '-' + UUID.randomUUID();
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not satisfied before timeout");
            }
            Thread.sleep(5L);
        }
    }
}
