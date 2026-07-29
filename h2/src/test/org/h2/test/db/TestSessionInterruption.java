/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.api.ErrorCode;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVTable;
import org.h2.table.Table;
import org.h2.test.TestBase;
import org.h2.test.TestDb;

/**
 * 测试前台会话等待被中断时的取消、状态恢复与锁清理行为。
 */
public class TestSessionInterruption extends TestDb {

    /**
     * 单独运行此测试。
     *
     * @param args 忽略
     */
    public static void main(String... args) throws Exception {
        TestBase.createCaller().init().testFromMain();
    }

    @Override
    public void test() throws Exception {
        if (config.networked) {
            return;
        }
        testThrottle();
        testExclusiveModeWait();
        testTableLockWait();
    }

    private void testThrottle() throws Exception {
        deleteDb("sessionInterruptThrottle");
        try (Connection connection = getConnection("sessionInterruptThrottle")) {
            SessionLocal session = getSession(connection);
            SessionLocal.State previousState = session.getState();
            session.setThrottle(5_000);
            assertInterruptedCancellation(session::throttle);
            assertEquals(previousState, session.getState());
        } finally {
            deleteDb("sessionInterruptThrottle");
        }
    }

    private void testExclusiveModeWait() throws Exception {
        deleteDb("sessionInterruptExclusive");
        try (Connection exclusiveConnection = getConnection("sessionInterruptExclusive");
                Connection waitingConnection = getConnection("sessionInterruptExclusive");
                Statement statement = exclusiveConnection.createStatement()) {
            SessionLocal waitingSession = getSession(waitingConnection);
            SessionLocal.State previousState = waitingSession.getState();
            statement.execute("SET EXCLUSIVE TRUE");
            try {
                assertInterruptedCancellation(waitingSession::waitIfExclusiveModeEnabled);
                assertEquals(previousState, waitingSession.getState());
            } finally {
                statement.execute("SET EXCLUSIVE FALSE");
            }
        } finally {
            deleteDb("sessionInterruptExclusive");
        }
    }

    private void testTableLockWait() throws Exception {
        deleteDb("sessionInterruptTableLock");
        try (Connection lockingConnection = getConnection("sessionInterruptTableLock");
                Connection waitingConnection = getConnection("sessionInterruptTableLock");
                Statement statement = lockingConnection.createStatement()) {
            statement.execute("CREATE TABLE TEST(ID INT PRIMARY KEY)");
            SessionLocal lockingSession = getSession(lockingConnection);
            SessionLocal waitingSession = getSession(waitingConnection);
            MVTable table = (MVTable) lockingSession.getDatabase().getMainSchema()
                    .getTableOrView(lockingSession, "TEST");
            table.lock(lockingSession, Table.EXCLUSIVE_LOCK);
            try {
                assertInterruptedCancellation(() -> table.lock(waitingSession, Table.EXCLUSIVE_LOCK));
                assertNull(waitingSession.getWaitForLock());
            } finally {
                lockingSession.rollback();
            }
        } finally {
            deleteDb("sessionInterruptTableLock");
        }
    }

    private void assertInterruptedCancellation(InterruptedOperation operation) throws InterruptedException {
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                operation.run();
                failure.set(new AssertionError("interrupted operation should be canceled"));
            } catch (DbException e) {
                if (e.getErrorCode() != ErrorCode.STATEMENT_WAS_CANCELED) {
                    failure.set(e);
                }
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "H2-test-session-interruption");
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
        assertTrue(interruptRestored.get());
    }

    private static SessionLocal getSession(Connection connection) {
        return (SessionLocal) ((JdbcConnection) connection).getSession();
    }

    private interface InterruptedOperation {

        void run();
    }
}
