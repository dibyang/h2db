/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test;

/**
 * Runs an explicit list of legacy {@link TestBase} tests from Gradle.
 *
 * <p>This runner is intentionally smaller than {@link TestAll}: it keeps the
 * original TestBase lifecycle, but lets Gradle expose focused, trackable groups
 * while the full TestAll suite is being brought back to a clean baseline.</p>
 */
public final class LegacyTestGroupRunner {

    private LegacyTestGroupRunner() {
    }

    /**
     * Runs the test classes named in {@code args}.
     *
     * @param args fully qualified TestBase subclass names
     * @throws Exception if a test class cannot be loaded or initialized
     */
    public static void main(String... args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("At least one legacy test class is required");
        }
        setTestSystemProperties();
        TestAll.atLeastOneTestFailed = false;

        TestAll config = new TestAll();
        config.ci = true;
        config.memory = true;
        config.beforeTest();
        try {
            for (String className : args) {
                TestBase test = createTest(className);
                test.runTest(config);
            }
        } finally {
            config.afterTest();
        }

        if (TestAll.atLeastOneTestFailed) {
            System.exit(1);
        }
    }

    private static void setTestSystemProperties() {
        System.setProperty("h2.maxMemoryRows", "100");
        System.setProperty("h2.delayWrongPasswordMin", "0");
        System.setProperty("h2.delayWrongPasswordMax", "0");
        System.setProperty("h2.useThreadContextClassLoader", "true");
    }

    private static TestBase createTest(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        if (!TestBase.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException(className + " is not a TestBase subclass");
        }
        return (TestBase) clazz.getDeclaredConstructor().newInstance();
    }
}
