/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.api.ErrorCode;
import org.h2.engine.Constants;
import org.h2.message.DbException;
import org.h2.message.TraceSystem;
import org.h2.store.FileLock;
import org.h2.store.FileLockMethod;
import org.h2.test.TestBase;
import org.h2.test.TestDb;

/**
 * Tests the database file locking facility. Both lock files and sockets locking
 * is tested.
 */
public class TestFileLock extends TestDb implements Runnable {

    private static volatile int locks;
    private static volatile boolean stop;
    private TestBase base;
    private int wait;
    private boolean allowSockets;

    public TestFileLock() {
        // nothing to do
    }

    TestFileLock(TestBase base, boolean allowSockets) {
        this.base = base;
        this.allowSockets = allowSockets;
    }

    private String getFile() {
        return getBaseDir() + "/test.lock";
    }

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().testFromMain();
    }

    @Override
    public boolean isEnabled() {
        if (!getFile().startsWith(TestBase.BASE_TEST_DIR)) {
            return false;
        }
        return true;
    }

    @Override
    public void test() throws Exception {
        testFsFileLock();
        testInterruptedSleep();
        testInterruptedWatchdogJoin();
        testFutureModificationDate();
        testSimple();
        test(false);
        test(true);
    }

    private void testFsFileLock() throws Exception {
        deleteDb("fileLock");
        String url = "jdbc:h2:" + getBaseDir() +
                "/fileLock;FILE_LOCK=FS;OPEN_NEW=TRUE";
        Connection conn = getConnection(url);
        assertThrows(ErrorCode.DATABASE_ALREADY_OPEN_1, () -> getConnection(url));
        conn.close();
    }

    private void testInterruptedSleep() throws Exception {
        Method sleep = FileLock.class.getDeclaredMethod("sleep", long.class);
        sleep.setAccessible(true);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                invoke(sleep, null, 1_000L);
                failure.set(new AssertionError("interrupted lock wait should fail"));
            } catch (DbException e) {
                if (e.getErrorCode() != ErrorCode.ERROR_OPENING_DATABASE_1) {
                    failure.set(e);
                }
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "H2-test-file-lock-sleep-interruption");
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
        assertTrue(interruptRestored.get());
    }

    private void testInterruptedWatchdogJoin() throws Exception {
        Method joinThread = FileLock.class.getDeclaredMethod("joinThread", Thread.class);
        joinThread.setAccessible(true);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "H2-test-file-lock-watchdog");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            assertCompletionBarrier(joinThread, worker, releaseWorker);
            worker.join(5_000);
            assertFalse(worker.isAlive());
        } finally {
            releaseWorker.countDown();
            worker.interrupt();
            worker.join(5_000);
        }
    }

    private void assertCompletionBarrier(Method joinThread, Thread worker, CountDownLatch releaseWorker)
            throws Exception {
        CountDownLatch waiterReturned = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                invoke(joinThread, null, worker);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                waiterReturned.countDown();
            }
        }, "H2-test-file-lock-join-interruption");
        waiter.setDaemon(true);
        try {
            waiter.start();
            assertFalse(waiterReturned.await(100, TimeUnit.MILLISECONDS));
            releaseWorker.countDown();
            assertTrue(waiterReturned.await(5, TimeUnit.SECONDS));
            waiter.join(5_000);
            assertFalse(waiter.isAlive());
            assertNull(failure.get());
            assertTrue(interruptRestored.get());
        } finally {
            releaseWorker.countDown();
            waiter.interrupt();
            waiter.join(5_000);
        }
    }

    private void testFutureModificationDate() throws Exception {
        File f = new File(getFile());
        f.delete();
        assertTrue(f.createNewFile());
        f.setLastModified(System.currentTimeMillis() + 10000);
        FileLock lock = new FileLock(new TraceSystem(null), getFile(),
                Constants.LOCK_SLEEP);
        lock.lock(FileLockMethod.FILE);
        lock.unlock();
    }

    private void testSimple() {
        String fileName = getFile();
        testSimple(fileName);
        testSimple("async:" + fileName);
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

    private void testSimple(String fileName) {
        FileLock lock1 = new FileLock(new TraceSystem(null), fileName, Constants.LOCK_SLEEP);
        FileLock lock2 = new FileLock(new TraceSystem(null), fileName, Constants.LOCK_SLEEP);
        lock1.lock(FileLockMethod.FILE);
        assertThrows(ErrorCode.DATABASE_ALREADY_OPEN_1, () -> lock2.lock(FileLockMethod.FILE));
        lock1.unlock();
        FileLock lock3 = new FileLock(new TraceSystem(null), fileName, Constants.LOCK_SLEEP);
        lock3.lock(FileLockMethod.FILE);
        lock3.unlock();
    }

    private void test(boolean allowSocketsLock) throws Exception {
        int threadCount = getSize(3, 5);
        wait = getSize(20, 200);
        Thread[] threads = new Thread[threadCount];
        new File(getFile()).delete();
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(new TestFileLock(this, allowSocketsLock));
            threads[i].start();
            Thread.sleep(wait + (int) (Math.random() * wait));
        }
        trace("wait");
        Thread.sleep(500);
        stop = true;
        trace("STOP file");
        for (int i = 0; i < threadCount; i++) {
            threads[i].join();
        }
        assertEquals(0, locks);
    }

    @Override
    public void run() {
        FileLock lock = null;
        while (!stop) {
            lock = new FileLock(new TraceSystem(null), getFile(), 100);
            try {
                lock.lock(allowSockets ? FileLockMethod.SOCKET
                        : FileLockMethod.FILE);
                base.trace(lock + " locked");
                locks++;
                if (locks > 1) {
                    System.err.println("ERROR! LOCKS=" + locks + " sockets=" +
                            allowSockets);
                    stop = true;
                }
                Thread.sleep(wait + (int) (Math.random() * wait));
                locks--;
                base.trace(lock + " unlock");
                lock.unlock();
                if (locks < 0) {
                    System.err.println("ERROR! LOCKS=" + locks);
                    stop = true;
                }
            } catch (Exception e) {
                // log(id+" cannot lock: " + e);
            }
            try {
                Thread.sleep(wait + (int) (Math.random() * wait));
            } catch (InterruptedException e1) {
                // ignore
            }
        }
        if (lock != null) {
            lock.unlock();
        }
    }

}
