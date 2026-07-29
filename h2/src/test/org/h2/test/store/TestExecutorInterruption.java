/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.store;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.mvstore.FileStore;
import org.h2.test.TestBase;
import org.h2.util.Utils;

/**
 * 测试等待线程被中断时执行器完成屏障的行为。
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
