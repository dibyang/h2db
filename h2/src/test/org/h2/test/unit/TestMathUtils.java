/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.unit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.h2.test.TestBase;
import org.h2.util.MathUtils;

/**
 * Tests math utility methods.
 */
public class TestMathUtils extends TestBase {

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().testFromMain();
    }

    @Override
    public void test() throws Exception {
        testSeedInterruption();
        testRandom();
        testNextPowerOf2Int();
    }

    private void testSeedInterruption() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread seedGenerator = new Thread(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "H2-test-seed-generator");
        seedGenerator.setDaemon(true);
        seedGenerator.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        Method method = MathUtils.class.getDeclaredMethod("joinSeedGenerator", Thread.class);
        method.setAccessible(true);
        ByteArrayOutputStream warning = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(warning, false, "UTF-8");
        PrintStream systemOut = System.out;
        PrintStream systemErr = System.err;
        try {
            System.setOut(capture);
            System.setErr(capture);
            Thread.currentThread().interrupt();
            try {
                method.invoke(null, seedGenerator);
                assertTrue(Thread.interrupted());
            } finally {
                Thread.interrupted();
                System.setOut(systemOut);
                System.setErr(systemErr);
                capture.close();
            }
            assertContains(warning.toString("UTF-8"), "Warning: InterruptedException");
        } finally {
            Thread.interrupted();
            System.setOut(systemOut);
            System.setErr(systemErr);
            release.countDown();
            seedGenerator.interrupt();
            seedGenerator.join(5_000);
        }
        assertFalse(seedGenerator.isAlive());
    }

    private void testRandom() {
        int bits = 0;
        for (int i = 0; i < 1000; i++) {
            bits |= 1 << MathUtils.randomInt(8);
        }
        assertEquals(255, bits);
        bits = 0;
        for (int i = 0; i < 1000; i++) {
            bits |= 1 << MathUtils.secureRandomInt(8);
        }
        assertEquals(255, bits);
        bits = 0;
        for (int i = 0; i < 1000; i++) {
            bits |= 1 << (MathUtils.secureRandomLong() & 7);
        }
        assertEquals(255, bits);
        // just verify the method doesn't throw an exception
        byte[] data = MathUtils.generateAlternativeSeed();
        assertTrue(data.length > 10);
    }

    private void testNextPowerOf2Int() {
        // the largest power of two that fits into an integer
        final int largestPower2 = 0x40000000;
        int[] testValues = { 0, 1, 2, 3, 4, 12, 17, 500, 1023,
                largestPower2 - 500, largestPower2 };
        int[] resultValues = { 1, 1, 2, 4, 4, 16, 32, 512, 1024,
                largestPower2, largestPower2 };

        for (int i = 0; i < testValues.length; i++) {
            assertEquals(resultValues[i], MathUtils.nextPowerOf2(testValues[i]));
        }
        testValues = new int[] { Integer.MIN_VALUE, -1, largestPower2 + 1, Integer.MAX_VALUE };
        for (int v : testValues) {
            assertThrows(IllegalArgumentException.class, () -> MathUtils.nextPowerOf2(v));
        }
    }

}
