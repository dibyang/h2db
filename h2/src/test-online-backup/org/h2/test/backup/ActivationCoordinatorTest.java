/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.h2.api.ErrorCode;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.engine.backup.ActivationCoordinator;
import org.h2.engine.backup.ActivationReason;
import org.h2.engine.backup.ActivationReport;
import org.h2.engine.backup.ActivationStatus;
import org.h2.engine.backup.ActivationToken;
import org.h2.engine.backup.DatabaseIdentityMetadata.Snapshot;
import org.h2.engine.backup.DatabaseOperationGate;
import org.h2.engine.backup.DatabaseOperationGate.FailureReason;
import org.h2.engine.backup.DatabaseOperationGate.State;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P7 activation、drain、token 和 permanent fence 契约测试。
 */
public class ActivationCoordinatorTest {

    @TempDir
    Path directory;

    /**
     * 既有事务完成前 drain 不返回；quiesce 拒绝可重试且 abort 后连接恢复。
     * <p>
     * T-H2BR-DRAIN-SUCCESS-01、T-H2BR-BEGIN-DRAIN-RACE-01、
     * T-H2BR-QUIESCE-ERROR-CONTRACT-01、
     * T-H2BR-ACTIVATION-CONNECTION-LIFECYCLE-01。
     */
    @Test
    public void drainWaitsAndQuiescingIsRetryable() throws Exception {
        UUID oldGeneration = UUID.randomUUID();
        UUID newGeneration = UUID.randomUUID();
        Connection active = connect("drain", oldGeneration);
        Connection management = connect("drain", oldGeneration);
        Connection rejected = connect("drain", oldGeneration);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            execute(active, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            active.setAutoCommit(false);
            execute(active, "INSERT INTO TEST VALUES(1)");
            Database database = database(management);
            DatabaseOperationGate gate = database.getOperationGate();

            Future<ActivationToken> future = executor.submit(
                    () -> ActivationCoordinator.prepare(database,
                            oldGeneration, newGeneration, 5_000L));
            waitUntil(() -> gate.getMetrics().getState()
                    == State.TRANSACTION_DRAIN);
            assertFalse(future.isDone());

            SQLException quiescing = assertThrows(SQLException.class,
                    () -> scalar(rejected, "SELECT COUNT(*) FROM TEST"));
            assertEquals(ErrorCode.ONLINE_BACKUP_QUIESCING_1,
                    quiescing.getErrorCode());
            assertEquals("40001", quiescing.getSQLState());
            assertTrue(quiescing
                    instanceof SQLTransactionRollbackException);
            assertFalse(rejected.isClosed());
            SQLException ddlQuiescing = assertThrows(SQLException.class,
                    () -> execute(rejected,
                            "CREATE TABLE BLOCKED_DDL(ID INT)"));
            assertEquals(ErrorCode.ONLINE_BACKUP_QUIESCING_1,
                    ddlQuiescing.getErrorCode());
            assertEquals("40001", ddlQuiescing.getSQLState());
            assertEquals(FailureReason.QUIESCING,
                    gate.getMetrics().getLastFailureReason());

            active.commit();
            try (ActivationToken token = future.get(5, TimeUnit.SECONDS)) {
                ActivationReport report = token.getReport();
                Snapshot identity = database.getOnlineBackupMetadata()
                        .requireSnapshot();
                assertEquals(ActivationStatus.PREPARED,
                        report.getStatus());
                assertEquals(ActivationReason.PREPARED,
                        report.getReason());
                assertEquals(identity.getDatabaseId(),
                        report.getDatabaseId());
                assertEquals(oldGeneration,
                        report.getOldGenerationId());
                assertEquals(newGeneration,
                        report.getNewGenerationId());
                assertEquals(identity.getSchemaEpoch(),
                        report.getSchemaEpoch());
                assertNotEquals(report.getActivationId(),
                        UUID.fromString(
                                "00000000-0000-0000-0000-000000000000"));
                assertEquals(State.TRANSACTION_DRAIN,
                        gate.getMetrics().getState());
                token.abortActivation();
            }

            assertEquals(State.OPEN, gate.getMetrics().getState());
            execute(rejected, "CREATE TABLE AFTER_ABORT(ID INT)");
            execute(rejected, "INSERT INTO TEST VALUES(2)");
            assertEquals(2, scalar(rejected,
                    "SELECT COUNT(*) FROM TEST"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            closeQuietly(rejected);
            closeQuietly(management);
            closeQuietly(active);
        }
    }

    /**
     * Drain timeout 使用独立错误码和 SQLTimeoutException，并恢复 admission。
     * <p>
     * T-H2BR-DRAIN-TIMEOUT-01、
     * T-H2BR-ACTIVATION-TIMEOUT-CONTRACT-01。
     */
    @Test
    public void timeoutRestoresAdmissionAndHasStableContract()
            throws Exception {
        UUID generation = UUID.randomUUID();
        Connection active = connect("timeout", generation);
        Connection management = connect("timeout", generation);
        try {
            execute(active, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            active.setAutoCommit(false);
            execute(active, "INSERT INTO TEST VALUES(1)");
            DatabaseOperationGate gate =
                    database(management).getOperationGate();

            DbException failure = assertThrows(DbException.class,
                    () -> ActivationCoordinator.prepare(
                            database(management), generation,
                            UUID.randomUUID(), 1L));
            SQLException sql = failure.getSQLException();
            assertEquals(
                    ErrorCode.ONLINE_BACKUP_ACTIVATION_TIMEOUT_1,
                    sql.getErrorCode());
            assertEquals("HYT00", sql.getSQLState());
            assertTrue(sql instanceof SQLTimeoutException);
            assertTrue(sql.getMessage().contains(
                    generation.toString()));
            assertEquals(State.OPEN, gate.getMetrics().getState());
            assertEquals(FailureReason.ACTIVATION_TIMEOUT,
                    gate.getMetrics().getLastFailureReason());

            active.rollback();
            execute(management, "INSERT INTO TEST VALUES(2)");
            assertFalse(management.isClosed());
        } finally {
            closeQuietly(management);
            closeQuietly(active);
        }
    }

    /**
     * Abort、重复 abort 和未消费 token 的 close 都恢复事务准入。
     * <p>
     * T-H2BR-ACTIVATION-ABORT-01、
     * T-H2BR-ACTIVATION-CONSUME-ONCE-01。
     */
    @Test
    public void abortAndCloseAreIdempotent() throws Exception {
        UUID generation = UUID.randomUUID();
        Connection connection = connect("abort", generation);
        try {
            execute(connection, "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            Database database = database(connection);

            ActivationToken token = ActivationCoordinator.prepare(database,
                    generation, UUID.randomUUID(), 5_000L);
            ActivationReport first = token.abortActivation();
            assertSame(first, token.abortActivation());
            assertEquals(ActivationStatus.ABORTED, first.getStatus());
            assertEquals(ActivationReason.ACTIVATION_ABORTED,
                    first.getReason());
            assertThrows(IllegalStateException.class,
                    token::commitActivation);
            token.close();
            execute(connection, "INSERT INTO TEST VALUES(1)");

            ActivationToken closed = ActivationCoordinator.prepare(database,
                    generation, UUID.randomUUID(), 5_000L);
            closed.close();
            assertEquals(ActivationStatus.ABORTED,
                    closed.getReport().getStatus());
            execute(connection, "INSERT INTO TEST VALUES(2)");
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * Commit 只能产生一次 fence；所有旧连接和后续连接都永久不可用。
     * <p>
     * T-H2BR-OLD-GENERATION-FENCE-01、
     * T-H2BR-ACTIVATION-CONSUME-ONCE-01、
     * T-H2BR-FENCE-ERROR-CONTRACT-01、
     * T-H2BR-ACTIVATION-CONNECTION-LIFECYCLE-01。
     */
    @Test
    public void commitPermanentlyFencesOldConnections() throws Exception {
        UUID generation = UUID.randomUUID();
        Connection management = connect("fence", generation);
        Connection peer = connect("fence", generation);
        PreparedStatement prepared = null;
        try {
            execute(management,
                    "CREATE TABLE TEST(ID INT PRIMARY KEY)");
            prepared = peer.prepareStatement("SELECT COUNT(*) FROM TEST");
            Database database = database(management);
            DatabaseOperationGate gate = database.getOperationGate();
            ActivationToken token = ActivationCoordinator.prepare(database,
                    generation, UUID.randomUUID(), 5_000L);

            ActivationReport committed = token.commitActivation();
            assertSame(committed, token.commitActivation());
            assertEquals(ActivationStatus.COMMITTED,
                    committed.getStatus());
            assertEquals(ActivationReason.GENERATION_FENCED,
                    committed.getReason());
            assertEquals(State.FENCED, gate.getMetrics().getState());
            assertThrows(IllegalStateException.class,
                    token::abortActivation);
            token.close();

            assertTrue(management.isClosed());
            assertTrue(peer.isClosed());
            assertFalse(peer.isValid(1));
            SQLException fenced = assertThrows(SQLException.class,
                    management::getAutoCommit);
            assertFenced(fenced);
            SQLException preparedFenced = assertThrows(SQLException.class,
                    prepared::executeQuery);
            assertFenced(preparedFenced);

            SQLException reconnect = assertThrows(SQLException.class,
                    () -> connect("fence", generation));
            assertFenced(reconnect);
            DbException secondActivation = assertThrows(DbException.class,
                    () -> ActivationCoordinator.prepare(database,
                            generation, UUID.randomUUID(), 5_000L));
            assertEquals(ErrorCode.GENERATION_FENCED_1,
                    secondActivation.getErrorCode());
            assertEquals(FailureReason.GENERATION_FENCED,
                    gate.getMetrics().getLastFailureReason());
            prepared.close();
            peer.close();
            management.close();
        } finally {
            closeQuietly(prepared);
            closeQuietly(peer);
            closeQuietly(management);
        }
    }

    /**
     * Generation mismatch 和 old=new 在进入 drain 前失败。
     * <p>
     * T-H2BR-ACTIVATION-GENERATION-MISMATCH-01。
     */
    @Test
    public void generationIdentityIsCheckedBeforeDrain() throws Exception {
        UUID generation = UUID.randomUUID();
        try (Connection connection = connect("identity", generation)) {
            Database database = database(connection);
            DbException mismatch = assertThrows(DbException.class,
                    () -> ActivationCoordinator.prepare(database,
                            UUID.randomUUID(), UUID.randomUUID(), 5_000L));
            assertEquals(
                    ErrorCode.ONLINE_BACKUP_GENERATION_MISMATCH_2,
                    mismatch.getErrorCode());
            assertEquals(State.OPEN,
                    database.getOperationGate().getMetrics().getState());

            assertThrows(DbException.class,
                    () -> ActivationCoordinator.prepare(database,
                            generation, generation, 5_000L));
            assertEquals(State.OPEN,
                    database.getOperationGate().getMetrics().getState());
        }
    }

    /**
     * Database shutdown 会唤醒 drain waiter，不会签发失效 token。
     */
    @Test
    public void gateShutdownInterruptsDrainWaiter() throws Exception {
        DatabaseOperationGate gate = new DatabaseOperationGate();
        gate.enterTransaction();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DatabaseOperationGate.TransactionDrain> future =
                    executor.submit(() -> gate.beginTransactionDrain(
                            5_000L, UUID.randomUUID().toString()));
            waitUntil(() -> gate.getMetrics().getState()
                    == State.TRANSACTION_DRAIN);
            gate.shutdown();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof DbException);
            assertEquals(ErrorCode.DATABASE_IS_CLOSED,
                    ((DbException) failure.getCause()).getErrorCode());
            assertEquals(State.CLOSED, gate.getMetrics().getState());
        } finally {
            gate.exitTransaction();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Connection connect(String name, UUID generation)
            throws SQLException {
        String path = directory.resolve(name).toAbsolutePath().toString()
                .replace('\\', '/');
        String url = "jdbc:h2:" + path
                + ";ONLINE_BACKUP_COORDINATION=TRUE"
                + ";ONLINE_BACKUP_GENERATION_ID=" + generation;
        return DriverManager.getConnection(url, "sa", "");
    }

    private static Database database(Connection connection) {
        return ((SessionLocal) ((JdbcConnection) connection)
                .getSession()).getDatabase();
    }

    private static void execute(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int scalar(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void assertFenced(SQLException failure) {
        assertEquals(ErrorCode.GENERATION_FENCED_1,
                failure.getErrorCode());
        assertEquals("08006", failure.getSQLState());
        assertTrue(failure
                instanceof SQLNonTransientConnectionException);
    }

    private static void waitUntil(BooleanSupplier condition)
            throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "Condition was not satisfied before timeout");
            }
            Thread.sleep(5L);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 测试清理；断言已经覆盖 fence 的公开错误契约。
            }
        }
    }
}
