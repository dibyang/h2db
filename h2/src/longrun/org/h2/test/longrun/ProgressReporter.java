/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

/**
 * Emits compact progress lines for log followers such as the shell watch mode.
 */
final class ProgressReporter {

    private static final int PROGRESS_BAR_WIDTH = 20;

    private final LongRunConfig config;
    private final long deadlineMillis;
    private long lastMillis;
    private long lastOperations;

    ProgressReporter(LongRunConfig config, LongRunState state, long deadlineMillis) {
        this.config = config;
        this.deadlineMillis = deadlineMillis;
        this.lastMillis = System.currentTimeMillis();
        this.lastOperations = state.getOperationSequence();
    }

    void report(LongRunState state, long now) {
        long operations = state.getOperationSequence();
        long elapsedMillis = Math.max(0L, now - state.getStartedMillis());
        long remainingMillis = Math.max(0L, deadlineMillis - now);
        long rateMillis = Math.max(1L, now - lastMillis);
        long deltaOperations = Math.max(0L, operations - lastOperations);
        long durationMillis = config.getDurationMillis();
        long percent = durationMillis <= 0L ? 100L : Math.min(100L, elapsedMillis * 100L / durationMillis);
        double opsPerSecond = deltaOperations * 1000D / rateMillis;
        LongRunLog.info(config, "PROGRESS " + progressBar(percent) + " " + formatPercent(percent) +
                " ops=" + humanCount(operations) +
                " rate=" + Math.round(opsPerSecond) + "/s" +
                " eta=" + Math.max(0L, (remainingMillis + 999L) / 1000L) + "s" +
                " checks=" + state.getReopenChecks() + "/" + state.getRecoveryChecks() +
                " percent=" + percent);
        lastMillis = now;
        lastOperations = operations;
    }

    private static String progressBar(long percent) {
        int filled = (int) Math.min(PROGRESS_BAR_WIDTH, Math.max(0L, percent) * PROGRESS_BAR_WIDTH / 100L);
        StringBuilder builder = new StringBuilder(PROGRESS_BAR_WIDTH + 2);
        builder.append('[');
        for (int i = 0; i < PROGRESS_BAR_WIDTH; i++) {
            builder.append(i < filled ? '#' : '-');
        }
        builder.append(']');
        return builder.toString();
    }

    private static String formatPercent(long percent) {
        return String.format(java.util.Locale.ROOT, "%3d%%", Long.valueOf(percent));
    }

    private static String humanCount(long value) {
        if (value >= 1_000_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fB", Double.valueOf(value / 1_000_000_000D));
        }
        if (value >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fM", Double.valueOf(value / 1_000_000D));
        }
        if (value >= 1_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fK", Double.valueOf(value / 1_000D));
        }
        return Long.toString(value);
    }
}
