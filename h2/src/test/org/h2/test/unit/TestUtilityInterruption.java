/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.message.DbException;
import org.h2.test.TestBase;
import org.h2.util.AbbaLockingDetector;
import org.h2.util.Profiler;
import org.h2.util.SourceCompiler;
import org.h2.util.Task;

/**
 * 测试公共任务与采样器完成屏障的中断行为。
 */
public class TestUtilityInterruption extends TestBase {

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
        testTaskJoin();
        testSourceCompilerProcessOutput();
        testSourceCompilerProcessInterruption();
        testProfilerStop();
        testProfilerLifecycle();
        testAbbaDetectorStop();
        testAbbaDetectorLifecycle();
    }

    private void testTaskJoin() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        Task task = new Task() {
            @Override
            public void call() {
                workerStarted.countDown();
                awaitUninterruptibly(releaseWorker);
            }
        };
        task.execute();
        assertCompletionBarrier(workerStarted, releaseWorker, task::join);
    }

    private void testSourceCompilerProcessOutput() throws Exception {
        try {
            invokeSourceCompilerExec(javaCommand("output"));
            fail("Expected compilation failure");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue(cause instanceof DbException);
            assertContains(cause.getMessage(), "STDOUT-END");
            assertContains(cause.getMessage(), "STDERR-END");
        }
    }

    private void testSourceCompilerProcessInterruption() throws Exception {
        Path directory = Paths.get("data",
                "h2-source-compiler-" + System.nanoTime()).toAbsolutePath();
        Files.createDirectories(directory);
        Path marker = directory.resolve("ready");
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                invokeSourceCompilerExec(javaCommand("sleep", marker.toString()));
                failure.set(new AssertionError("Expected interrupted process wait"));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                boolean interrupted = Thread.currentThread().isInterrupted();
                if (!(cause instanceof DbException) || !interrupted) {
                    failure.set(cause);
                }
                interruptRestored.set(interrupted);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "H2-test-source-compiler-process");
        try {
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(marker) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertNull(failure.get());
            assertTrue(Files.exists(marker));
            worker.interrupt();
            worker.join(5_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertTrue(interruptRestored.get());
        } finally {
            worker.interrupt();
            worker.join(5_000);
            Files.deleteIfExists(marker);
            Files.deleteIfExists(directory);
        }
    }

    private void testProfilerStop() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        Profiler profiler = new Profiler() {
            @Override
            public void run() {
                workerStarted.countDown();
                awaitUninterruptibly(releaseWorker);
            }
        };
        profiler.startCollecting();
        assertCompletionBarrier(workerStarted, releaseWorker, () -> profiler.stopCollecting());
    }

    private void testProfilerLifecycle() throws Exception {
        Profiler profiler = new Profiler();
        profiler.interval = 10_000;
        try {
            profiler.startCollecting();
            Thread first = getProfilerThread(profiler);
            assertTrue(first.isAlive());

            profiler.startCollecting();
            assertSame(first, getProfilerThread(profiler));

            profiler.stopCollecting();
            assertFalse(first.isAlive());
            assertNull(getProfilerThread(profiler));

            profiler.startCollecting();
            Thread second = getProfilerThread(profiler);
            assertFalse(first == second);
            assertTrue(second.isAlive());
        } finally {
            profiler.stopCollecting();
        }
    }

    private void testAbbaDetectorStop() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AbbaLockingDetector detector = new AbbaLockingDetector() {
            @Override
            public void run() {
                workerStarted.countDown();
                awaitUninterruptibly(releaseWorker);
            }
        };
        detector.startCollecting();
        assertCompletionBarrier(workerStarted, releaseWorker, () -> detector.stopCollecting());
    }

    private void testAbbaDetectorLifecycle() throws Exception {
        AbbaLockingDetector detector = new AbbaLockingDetector();
        try {
            detector.startCollecting();
            Thread first = getAbbaThread(detector);
            assertTrue(first.isAlive());

            detector.startCollecting();
            assertSame(first, getAbbaThread(detector));

            detector.stopCollecting();
            assertFalse(first.isAlive());
            assertNull(getAbbaThread(detector));

            detector.startCollecting();
            Thread second = getAbbaThread(detector);
            assertFalse(first == second);
            Thread.sleep(100);
            assertTrue(second.isAlive());
        } finally {
            detector.stopCollecting();
        }
    }

    private static Thread getAbbaThread(AbbaLockingDetector detector) throws Exception {
        Field field = AbbaLockingDetector.class.getDeclaredField("thread");
        field.setAccessible(true);
        return (Thread) field.get(detector);
    }

    private static Thread getProfilerThread(Profiler profiler) throws Exception {
        Field field = Profiler.class.getDeclaredField("thread");
        field.setAccessible(true);
        return (Thread) field.get(profiler);
    }

    private static Object invokeSourceCompilerExec(String... command) throws Exception {
        Method method = SourceCompiler.class.getDeclaredMethod("exec", String[].class);
        method.setAccessible(true);
        return method.invoke(null, (Object) command);
    }

    private static String[] javaCommand(String... args) {
        String[] command = new String[4 + args.length];
        command[0] = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        command[1] = "-cp";
        command[2] = System.getProperty("java.class.path");
        command[3] = SourceCompilerProcessOutput.class.getName();
        System.arraycopy(args, 0, command, 4, args.length);
        return command;
    }

    private void assertCompletionBarrier(CountDownLatch workerStarted, CountDownLatch releaseWorker,
            Runnable barrier) throws Exception {
        CountDownLatch barrierStarted = new CountDownLatch(1);
        CountDownLatch barrierReturned = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = null;
        try {
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            waiter = new Thread(() -> {
                Thread.currentThread().interrupt();
                barrierStarted.countDown();
                try {
                    barrier.run();
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    barrierReturned.countDown();
                }
            }, "H2-test-utility-completion");
            waiter.start();
            assertTrue(barrierStarted.await(5, TimeUnit.SECONDS));
            assertFalse(barrierReturned.await(100, TimeUnit.MILLISECONDS));
            releaseWorker.countDown();
            assertTrue(barrierReturned.await(5, TimeUnit.SECONDS));
            waiter.join(5_000);
            assertFalse(waiter.isAlive());
            assertNull(failure.get());
            assertTrue(interruptRestored.get());
        } finally {
            releaseWorker.countDown();
            if (waiter != null) {
                waiter.interrupt();
                waiter.join(5_000);
            }
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
}
