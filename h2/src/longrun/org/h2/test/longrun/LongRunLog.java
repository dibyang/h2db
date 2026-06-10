/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

/**
 * Small stdout log helper that prefixes parent and worker process messages.
 */
public final class LongRunLog {

    private LongRunLog() {
    }

    public static void info(LongRunConfig config, String message) {
        System.out.println(prefix(config) + " " + message);
    }

    public static void warn(LongRunConfig config, String message) {
        System.out.println(prefix(config) + " WARN " + message);
    }

    public static void parent(String message) {
        System.out.println("[parent pid=" + processId() + "] " + message);
    }

    public static String prefix(LongRunConfig config) {
        return "[" + (config.isWorker() ? "worker" : "parent") + " pid=" + processId() + "]";
    }

    public static String processId() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at < 0 ? name : name.substring(0, at);
    }
}
