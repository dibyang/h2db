/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.server;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.server.TcpServer;
import org.h2.server.web.WebServer;
import org.h2.test.TestBase;

/**
 * 测试服务器停止与翻译线程替换时的中断等待行为。
 */
public class TestServerInterruption extends TestBase {

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
        testTcpServerStop();
        testWebServerStop();
        testTranslateThreadStop();
    }

    private void testTcpServerStop() throws Exception {
        TcpServer server = new TcpServer();
        assertListenerStopWaits(server, TcpServer.class.getDeclaredField("listenerThread"), server::stop);
    }

    private void testWebServerStop() throws Exception {
        WebServer server = new WebServer();
        assertListenerStopWaits(server, WebServer.class.getDeclaredField("listenerThread"), server::stop);
    }

    private void assertListenerStopWaits(Object server, Field listenerThread, Runnable stop) throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        Thread listener = new Thread(() -> {
            workerStarted.countDown();
            awaitUninterruptibly(releaseWorker);
        }, "H2-test-server-listener");
        listener.setDaemon(true);
        listenerThread.setAccessible(true);
        listenerThread.set(server, listener);
        listener.start();
        try {
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            assertCompletionBarrier(releaseWorker, stop);
            listener.join(5_000);
            assertFalse(listener.isAlive());
        } finally {
            releaseWorker.countDown();
            listener.interrupt();
            listener.join(5_000);
        }
    }

    private void testTranslateThreadStop() throws Exception {
        Path translationFile = Paths.get("translation.properties");
        boolean fileExisted = Files.exists(translationFile);
        WebServer server = new WebServer();
        BlockingMap translation = new BlockingMap();
        Method startTranslate = WebServer.class.getDeclaredMethod("startTranslate", Map.class);
        Field translateThread = WebServer.class.getDeclaredField("translateThread");
        startTranslate.setAccessible(true);
        translateThread.setAccessible(true);
        Thread firstThread = null;
        try {
            invoke(startTranslate, server, translation);
            assertTrue(translation.workerStarted.await(5, TimeUnit.SECONDS));
            firstThread = (Thread) translateThread.get(server);
            Runnable replace = () -> invoke(startTranslate, server, new HashMap<>());
            assertCompletionBarrier(translation.releaseWorker, replace);
            firstThread.join(5_000);
            assertFalse(firstThread.isAlive());
        } finally {
            translation.releaseWorker.countDown();
            try {
                stopTranslateThread(translateThread.get(server));
            } finally {
                try {
                    if (firstThread != null) {
                        firstThread.interrupt();
                        firstThread.join(5_000);
                    }
                } finally {
                    if (!fileExisted) {
                        Files.deleteIfExists(translationFile);
                    }
                }
            }
        }
    }

    private void assertCompletionBarrier(CountDownLatch releaseWorker, Runnable barrier) throws Exception {
        CountDownLatch barrierStarted = new CountDownLatch(1);
        CountDownLatch barrierReturned = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
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
        }, "H2-test-server-stop");
        waiter.setDaemon(true);
        try {
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
            waiter.interrupt();
            waiter.join(5_000);
        }
    }

    private static void stopTranslateThread(Object thread) {
        if (thread != null) {
            try {
                Method stopNow = thread.getClass().getDeclaredMethod("stopNow");
                stopNow.setAccessible(true);
                stopNow.invoke(thread);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static Object invoke(Method method, Object target, Object argument) {
        try {
            return method.invoke(target, argument);
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

    private static final class BlockingMap extends HashMap<Object, Object> {

        private static final long serialVersionUID = 1L;

        private final CountDownLatch workerStarted = new CountDownLatch(1);
        private final CountDownLatch releaseWorker = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();

        BlockingMap() {
            super.put("test", "value");
        }

        @Override
        public void putAll(Map<? extends Object, ? extends Object> map) {
            blockOnce();
            super.putAll(map);
        }

        @Override
        public Set<Map.Entry<Object, Object>> entrySet() {
            blockOnce();
            return super.entrySet();
        }

        private void blockOnce() {
            if (blocked.compareAndSet(false, true)) {
                workerStarted.countDown();
                awaitUninterruptibly(releaseWorker);
            }
        }
    }
}
