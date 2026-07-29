/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.jdbcx;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.test.TestBase;
import org.h2.test.TestDb;

/**
 * 测试连接池容量等待被中断时的完成屏障与中断恢复行为。
 */
public class TestConnectionPoolInterruption extends TestDb {

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
        deleteDb("connectionPoolInterruption");
        JdbcConnectionPool pool = createPool();
        Connection occupied = pool.getConnection();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterReturned = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            waiterStarted.countDown();
            try (Connection connection = pool.getConnection()) {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                waiterReturned.countDown();
            }
        }, "H2-test-connection-pool-interruption");
        waiter.setDaemon(true);
        try {
            waiter.start();
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS));
            waitForInterruptToBeConsumed(waiter);
            assertFalse(waiterReturned.await(100, TimeUnit.MILLISECONDS));
            assertFalse(waiter.isInterrupted());
            occupied.close();
            assertTrue(waiterReturned.await(5, TimeUnit.SECONDS));
            waiter.join(5_000);
            assertFalse(waiter.isAlive());
            assertNull(failure.get());
            assertTrue(interruptRestored.get());
        } finally {
            occupied.close();
            waiter.interrupt();
            waiter.join(5_000);
            pool.dispose();
            deleteDb("connectionPoolInterruption");
        }
    }

    private JdbcConnectionPool createPool() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(getURL("connectionPoolInterruption", true));
        dataSource.setUser(getUser());
        dataSource.setPassword(getPassword());
        JdbcConnectionPool pool = JdbcConnectionPool.create(dataSource);
        pool.setMaxConnections(1);
        pool.setLoginTimeout(5);
        return pool;
    }

    private void waitForInterruptToBeConsumed(Thread waiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (waiter.isInterrupted() && waiter.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertFalse(waiter.isInterrupted());
    }
}
