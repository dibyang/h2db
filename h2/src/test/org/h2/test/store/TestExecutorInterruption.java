/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.store;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.h2.mvstore.FileStore;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.RootReference;
import org.h2.mvstore.tx.Transaction;
import org.h2.mvstore.tx.TransactionStore;
import org.h2.store.fs.FileUtils;
import org.h2.test.TestBase;
import org.h2.util.Utils;

/**
 * 测试 MVStore 前台等待与执行器完成屏障的中断行为。
 */
public class TestExecutorInterruption extends TestBase {

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
        testExecutorBarrier(Utils::flushExecutor);
        testExecutorBarrier(Utils::shutdownExecutor);
        testSynchronousFileStoreWait();
        testTransactionWait();
        testFileStoreCompact();
        testMVMapContentionWait();
    }

    private void testExecutorBarrier(ExecutorBarrier barrier) throws Exception {
        ThreadPoolExecutor executor = Utils.createSingleThreadExecutor("H2-test-executor-barrier");
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        CountDownLatch barrierStarted = new CountDownLatch(1);
        CountDownLatch barrierReturned = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = null;
        try {
            executor.execute(() -> {
                taskStarted.countDown();
                awaitUninterruptibly(releaseTask);
            });
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
            waiter = new Thread(() -> {
                barrierStarted.countDown();
                try {
                    barrier.await(executor);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    barrierReturned.countDown();
                }
            }, "H2-test-executor-waiter");
            waiter.start();
            assertTrue(barrierStarted.await(5, TimeUnit.SECONDS));
            waiter.interrupt();
            assertFalse(barrierReturned.await(100, TimeUnit.MILLISECONDS));
            releaseTask.countDown();
            assertTrue(barrierReturned.await(5, TimeUnit.SECONDS));
            waiter.join(5_000);
            assertNull(failure.get());
            assertTrue(interruptRestored.get());
        } finally {
            releaseTask.countDown();
            executor.shutdownNow();
            if (waiter != null) {
                waiter.join(5_000);
            }
        }
    }

    private void testSynchronousFileStoreWait() throws Exception {
        ThreadPoolExecutor executor = Utils.createSingleThreadExecutor("H2-test-file-store-wait");
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CountDownLatch waitReturned = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Method submitOrRun = FileStore.class.getDeclaredMethod("submitOrRun", ThreadPoolExecutor.class,
                Runnable.class, boolean.class, int.class, int.class);
        submitOrRun.setAccessible(true);
        Thread waiter = new Thread(() -> {
            try {
                Runnable action = () -> {
                    actionStarted.countDown();
                    awaitUninterruptibly(releaseAction);
                };
                submitOrRun.invoke(null, executor, action, true, 1, 0);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                waitReturned.countDown();
            }
        }, "H2-test-file-store-waiter");
        try {
            waiter.start();
            assertTrue(actionStarted.await(5, TimeUnit.SECONDS));
            waiter.interrupt();
            assertFalse(waitReturned.await(100, TimeUnit.MILLISECONDS));
            releaseAction.countDown();
            assertTrue(waitReturned.await(5, TimeUnit.SECONDS));
            waiter.join(5_000);
            assertNull(failure.get());
            assertTrue(interruptRestored.get());
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
            waiter.join(5_000);
        }
    }

    private void testTransactionWait() throws Exception {
        try (MVStore store = MVStore.open(null)) {
            TransactionStore transactionStore = new TransactionStore(store);
            transactionStore.init();
            Transaction blocking = transactionStore.begin();
            Transaction waiting = transactionStore.begin();
            CountDownLatch waitStarted = new CountDownLatch(1);
            AtomicReference<Boolean> result = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                waitStarted.countDown();
                try {
                    result.set(waiting.waitFor(blocking, "test", 1, 5_000));
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable e) {
                    failure.set(e);
                }
            }, "H2-test-transaction-waiter");
            try {
                waiter.start();
                assertTrue(waitStarted.await(5, TimeUnit.SECONDS));
                waiter.interrupt();
                waiter.join(5_000);
                assertFalse(waiter.isAlive());
                assertNull(failure.get());
                assertEquals(Boolean.FALSE, result.get());
                assertTrue(interruptRestored.get());
            } finally {
                blocking.rollback();
                waiting.rollback();
                waiter.interrupt();
                waiter.join(5_000);
            }
        }
    }

    private void testFileStoreCompact() throws Exception {
        FileUtils.createDirectories(getBaseDir());
        String fileName = getBaseDir() + "/interruptCompact.mv.db";
        FileUtils.delete(fileName);
        try (MVStore store = new MVStore.Builder().fileName(fileName).open()) {
            store.<Integer, Integer>openMap("data").put(1, 1);
            store.commit();
            FileStore<?> fileStore = store.getFileStore();
            Field storeLockField = MVStore.class.getDeclaredField("storeLock");
            storeLockField.setAccessible(true);
            ReentrantLock storeLock = (ReentrantLock) storeLockField.get(store);
            AtomicBoolean interruptRestored = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread compactor = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    fileStore.compact(101, 1);
                    failure.set(new AssertionError("interrupted compaction should fail"));
                } catch (RuntimeException e) {
                    if (!(e.getCause() instanceof InterruptedException)) {
                        failure.set(e);
                    }
                } finally {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            }, "H2-test-file-store-compactor");
            storeLock.lock();
            try {
                compactor.start();
                compactor.join(5_000);
                assertFalse(compactor.isAlive());
                assertNull(failure.get());
                assertTrue(interruptRestored.get());
            } finally {
                storeLock.unlock();
            }
        } finally {
            FileUtils.delete(fileName);
        }
    }

    private void testMVMapContentionWait() throws Exception {
        try (MVStore store = MVStore.open(null)) {
            MVMap<Integer, Integer> map = store.openMap("interruptMap");
            Method lockRoot = MVMap.class.getDeclaredMethod("lockRoot", RootReference.class, int.class);
            Method unlockRoot = MVMap.class.getDeclaredMethod("unlockRoot");
            lockRoot.setAccessible(true);
            unlockRoot.setAccessible(true);
            RootReference<?, ?> lockedRoot = (RootReference<?, ?>) lockRoot.invoke(map, map.getRoot(), 1);
            try {
                assertInterruptedTryLock(map, lockedRoot, 13);
                assertInterruptedTryLock(map, lockedRoot, 100);
            } finally {
                unlockRoot.invoke(map);
            }
        }
    }

    private void assertInterruptedTryLock(MVMap<?, ?> map, RootReference<?, ?> lockedRoot, int attempt)
            throws InterruptedException, NoSuchMethodException {
        Method tryLock = MVMap.class.getDeclaredMethod("tryLock", RootReference.class, int.class);
        tryLock.setAccessible(true);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                tryLock.invoke(map, lockedRoot, attempt);
                failure.set(new AssertionError("interrupted root lock should fail"));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (!(cause instanceof RuntimeException) || !(cause.getCause() instanceof InterruptedException)) {
                    failure.set(cause);
                }
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "H2-test-map-lock-waiter");
        waiter.start();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
        assertTrue(interruptRestored.get());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private interface ExecutorBarrier {

        void await(ThreadPoolExecutor executor);
    }
}
