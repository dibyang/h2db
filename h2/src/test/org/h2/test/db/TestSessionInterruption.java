/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.api.ErrorCode;
import org.h2.engine.Database;
import org.h2.engine.Engine;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVTable;
import org.h2.table.Table;
import org.h2.test.TestBase;
import org.h2.test.TestDb;

/**
 * 测试前台会话与引擎等待被中断时的取消、完成屏障及状态恢复行为。
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
        testDatabaseCloseWait();
        testEngineCloseWait();
        testWrongPasswordDelay();
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

    private void testDatabaseCloseWait() throws Exception {
        deleteDb("sessionInterruptDatabaseClose");
        try (Connection retainedConnection = getConnection("sessionInterruptDatabaseClose");
                Connection closingConnection = getConnection("sessionInterruptDatabaseClose")) {
            SessionLocal retainedSession = getSession(retainedConnection);
            SessionLocal closingSession = getSession(closingConnection);
            closingSession.waitIfExclusiveModeEnabled();
            Database database = retainedSession.getDatabase();
            Method closeAllSessionsExcept = Database.class
                    .getDeclaredMethod("closeAllSessionsExcept", SessionLocal.class);
            closeAllSessionsExcept.setAccessible(true);
            CountDownLatch closeStarted = new CountDownLatch(1);
            CountDownLatch closeReturned = new CountDownLatch(1);
            AtomicBoolean interruptRestored = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread closer = new Thread(() -> {
                Thread.currentThread().interrupt();
                closeStarted.countDown();
                try {
                    invoke(closeAllSessionsExcept, database, retainedSession);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    closeReturned.countDown();
                }
            }, "H2-test-database-close-interruption");
            closer.setDaemon(true);
            try {
                closer.start();
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
                waitForState(closingSession, SessionLocal.State.SUSPENDED);
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
                closingSession.close();
                assertTrue(closeReturned.await(5, TimeUnit.SECONDS));
                closer.join(5_000);
                assertFalse(closer.isAlive());
                assertNull(failure.get());
                assertTrue(interruptRestored.get());
            } finally {
                closingSession.close();
                closer.interrupt();
                closer.join(5_000);
            }
        } finally {
            deleteDb("sessionInterruptDatabaseClose");
        }
    }

    private void testEngineCloseWait() throws Exception {
        Method waitForDatabaseClose = Engine.class.getDeclaredMethod("waitForDatabaseClose");
        waitForDatabaseClose.setAccessible(true);
        assertInterruptedFailure(() -> invoke(waitForDatabaseClose, null),
                ErrorCode.DATABASE_CALLED_AT_SHUTDOWN);
    }

    private void testWrongPasswordDelay() throws Exception {
        Method delayWrongPassword = Engine.class.getDeclaredMethod("delayWrongPassword", long.class);
        delayWrongPassword.setAccessible(true);
        long delayMillis = 50;
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicLong elapsedNanos = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            long start = System.nanoTime();
            try {
                invoke(delayWrongPassword, null, delayMillis);
                elapsedNanos.set(System.nanoTime() - start);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "H2-test-wrong-password-delay-interruption");
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
        assertTrue(elapsedNanos.get() >= TimeUnit.MILLISECONDS.toNanos(delayMillis));
        assertTrue(interruptRestored.get());
    }

    private void assertInterruptedCancellation(InterruptedOperation operation) throws InterruptedException {
        assertInterruptedFailure(operation, ErrorCode.STATEMENT_WAS_CANCELED);
    }

    private void assertInterruptedFailure(InterruptedOperation operation, int errorCode) throws InterruptedException {
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                operation.run();
                failure.set(new AssertionError("interrupted operation should be canceled"));
            } catch (DbException e) {
                if (e.getErrorCode() != errorCode) {
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

    private void waitForState(SessionLocal session, SessionLocal.State state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (session.getState() != state && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(state, session.getState());
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static SessionLocal getSession(Connection connection) {
        return (SessionLocal) ((JdbcConnection) connection).getSession();
    }

    private interface InterruptedOperation {

        void run();
    }
}
