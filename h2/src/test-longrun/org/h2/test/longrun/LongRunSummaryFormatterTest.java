/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for visible longrun lifecycle summaries.
 */
public final class LongRunSummaryFormatterTest {

    @TempDir
    public File tempDir;

    @Test
    public void finishedSummaryIsVisibleAndContainsResult() {
        LongRunConfig config = new LongRunConfig.Builder()
                .runName("summary-test")
                .workDir(new File(tempDir, "work"))
                .durationMillis(10L)
                .mode(LongRunMode.MVSTORE)
                .build();
        LongRunState state = new LongRunState();
        state.nextSequence();
        state.read();
        state.write();
        state.remove();
        state.commit();

        String summary = LongRunSummaryFormatter.finished(config, state, 4096L,
                new File(config.getWorkDir(), "final-report.properties"), 12L);

        assertTrue(summary.contains("H2 LONGRUN TEST FINISHED"));
        assertTrue(summary.contains("Result: PASS"));
        assertTrue(summary.contains("Run Name: summary-test"));
        assertTrue(summary.contains("Operations: 1"));
        assertTrue(summary.contains("Reopen Checks: 0"));
        assertTrue(summary.contains("Recovery Checks: 0"));
        assertTrue(summary.contains("Final Size Bytes: 4096"));
        assertTrue(summary.contains("Final Report: "));
    }
}
