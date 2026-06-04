/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for longrun metrics sampling.
 */
public final class MetricsReporterTest {

    @TempDir
    public File tempDir;

    @Test
    public void resetBaselinePreventsRecoveredStateFromInflatingThroughput() throws Exception {
        LongRunState state = new LongRunState();
        state.ensureOperationSequenceAtLeast(1_000L);
        File metricsDir = new File(tempDir, "metrics");

        try (MetricsReporter metrics = new MetricsReporter(metricsDir)) {
            metrics.resetBaseline(state);
            Thread.sleep(5L);
            for (int i = 0; i < 10; i++) {
                state.nextSequence();
            }
            metrics.report(state, MetricPhase.RUNNING);
        }

        double opsPerSecond = firstOpsPerSecond(metricsDir);
        assertTrue(opsPerSecond < 10_000D, "ops/s was inflated by recovered history: " + opsPerSecond);
    }

    private static double firstOpsPerSecond(File metricsDir) throws Exception {
        File[] files = metricsDir.listFiles((dir, name) -> name.startsWith("metrics-") && name.endsWith(".csv"));
        assertTrue(files != null && files.length == 1);
        try (BufferedReader reader = new BufferedReader(new FileReader(files[0]))) {
            reader.readLine();
            String[] parts = reader.readLine().split(",", -1);
            return Double.parseDouble(parts[2]);
        }
    }
}
