/*
 * Copyright 2004-2026 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.util.StringUtils;

/** 大写缓存的发布约束与独立连接并发读取回归。 */
public final class TestUpperCaseCache {
    /** 独立运行；model 参数仅用于模拟旧实现允许的部分初始化状态。 */
    public static void main(String... args) throws Exception {
        Field field = StringUtils.class.getDeclaredField("TO_UPPER_CACHE");
        field.setAccessible(true);
        if (args.length > 0 && "model".equals(args[0])) {
            if (field.getType() != String[][].class) {
                throw new IllegalArgumentException("model mode requires the pre-fix String[][] cache");
            }
            String[][] cache = (String[][]) field.get(null);
            int index = "node_id".hashCode() & (cache.length - 1);
            String[] previous = cache[index];
            try {
                cache[index] = new String[2];
                StringUtils.toUpperEnglish("node_id");
                throw new AssertionError("Expected failure of partially initialized entry");
            } catch (NullPointerException expected) {
                expected.printStackTrace(System.out);
            } finally {
                cache[index] = previous;
            }
            return;
        }
        // 普通数组的发布必须依赖不可变对象的 final 字段语义，压力通过不能替代此约束。
        Class<?> entry = field.getType().getComponentType();
        if (entry.isArray() || !Modifier.isFinal(entry.getModifiers())) {
            throw new AssertionError("Unsafe publication: mutable cache entry");
        }
        for (Field member : entry.getDeclaredFields()) {
            if (!Modifier.isStatic(member.getModifiers())
                    && (!Modifier.isFinal(member.getModifiers()) || member.getType() != String.class)) {
                throw new AssertionError("Unsafe cache field: " + member);
            }
        }
        verifyConcurrentReads();
        System.out.println("Upper-case cache checks passed");
    }

    /** 独立连接共享全局缓存，碰撞字符串验证错误值和空值，三列查询触发列名索引。 */
    private static void verifyConcurrentReads() throws Exception {
        Class.forName("org.h2.Driver");
        AtomicInteger workerIds = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8, task -> {
            // JDBC 阻塞可能不响应中断；超时失败后不能由测试线程阻止 JVM 退出。
            Thread worker = new Thread(task, "upper-cache-test-" + workerIds.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        });
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                final int worker = i;
                tasks.add(pool.submit(() -> {
                    if (!Thread.currentThread().isDaemon()) {
                        throw new AssertionError("Test worker must not prevent JVM exit");
                    }
                    start.await();
                    String[] keys = { "AaAa", "BBBB", "AaBB", "BBAa", "node_id", "i\u0131\u00df" };
                    try (Connection c = DriverManager.getConnection("jdbc:h2:mem:upper" + worker);
                            Statement s = c.createStatement()) {
                        for (int round = 0; round < 2000; round++) {
                            for (String key : keys) {
                                if (!key.toUpperCase(Locale.ENGLISH).equals(StringUtils.toUpperEnglish(key))) {
                                    throw new AssertionError("Incorrect uppercase: " + key);
                                }
                            }
                            try (ResultSet r = s.executeQuery("SELECT 'n' AS node_id, 'g' AS group_id, FALSE AS disabled")) {
                                if (!r.next() || !"n".equals(r.getString("node_id"))
                                        || !"g".equals(r.getString("GROUP_ID")) || r.getBoolean("disabled")) {
                                    throw new AssertionError("Incorrect JDBC result");
                                }
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Workers did not terminate");
            }
        }
    }
}
