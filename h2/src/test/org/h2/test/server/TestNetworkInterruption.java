/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.server.Service;
import org.h2.test.TestBase;
import org.h2.tools.Server;
import org.h2.util.NetUtils;

/**
 * 测试网络连接重试和服务器启动轮询期间的中断恢复行为。
 */
public class TestNetworkInterruption extends TestBase {

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
        testSocketRetryFailure();
        testServerStartWait();
    }

    private void testSocketRetryFailure() throws InterruptedException {
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread connector = new Thread(() -> {
            Thread.currentThread().interrupt();
            try (Socket socket = NetUtils.createSocket(InetAddress.getLoopbackAddress(), 0, false, 0)) {
                failure.set(new AssertionError("connection to port zero should fail"));
            } catch (IOException expected) {
                // expected
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "H2-test-network-retry-interruption");
        connector.setDaemon(true);
        connector.start();
        connector.join(10_000);
        assertFalse(connector.isAlive());
        assertNull(failure.get());
        assertTrue(interruptRestored.get());
    }

    private void testServerStartWait() throws Exception {
        DelayedService service = new DelayedService();
        Server server = new Server(service);
        CountDownLatch startReturned = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                server.start();
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                startReturned.countDown();
            }
        }, "H2-test-server-start-interruption");
        starter.setDaemon(true);
        try {
            starter.start();
            assertTrue(service.listenerStarted.await(5, TimeUnit.SECONDS));
            assertFalse(startReturned.await(100, TimeUnit.MILLISECONDS));
            service.allowRunning.countDown();
            assertTrue(startReturned.await(5, TimeUnit.SECONDS));
            starter.join(5_000);
            assertFalse(starter.isAlive());
            assertNull(failure.get());
            assertTrue(interruptRestored.get());
            assertTrue(server.isRunning(false));
        } finally {
            service.allowRunning.countDown();
            server.stop();
            starter.interrupt();
            starter.join(5_000);
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

    private static final class DelayedService implements Service {

        private final CountDownLatch listenerStarted = new CountDownLatch(1);
        private final CountDownLatch allowRunning = new CountDownLatch(1);
        private volatile boolean running;
        private volatile boolean stopped;

        @Override
        public void init(String... args) {
        }

        @Override
        public String getURL() {
            return "test://localhost";
        }

        @Override
        public void start() throws SQLException {
        }

        @Override
        public void listen() {
            listenerStarted.countDown();
            awaitUninterruptibly(allowRunning);
            if (!stopped) {
                running = true;
            }
        }

        @Override
        public void stop() {
            stopped = true;
            allowRunning.countDown();
            running = false;
        }

        @Override
        public boolean isRunning(boolean traceError) {
            return running;
        }

        @Override
        public boolean getAllowOthers() {
            return false;
        }

        @Override
        public String getName() {
            return "Test Server";
        }

        @Override
        public String getType() {
            return "Test";
        }

        @Override
        public int getPort() {
            return 0;
        }

        @Override
        public boolean isDaemon() {
            return true;
        }
    }
}
