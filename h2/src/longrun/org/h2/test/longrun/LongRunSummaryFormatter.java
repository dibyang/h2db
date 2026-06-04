/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;

/**
 * Formats visible console summaries for long-running test lifecycle events.
 */
final class LongRunSummaryFormatter {

    private LongRunSummaryFormatter() {
    }

    static String finished(LongRunConfig config, LongRunState state, long finalSizeBytes, File finalReport,
            long elapsedMillis) {
        String line = "========== H2 LONGRUN TEST FINISHED ==========";
        return line + System.lineSeparator()
                + "Result: PASS" + System.lineSeparator()
                + "Run Name: " + config.getRunName() + System.lineSeparator()
                + "Mode: " + config.getMode() + System.lineSeparator()
                + "Elapsed Millis: " + elapsedMillis + System.lineSeparator()
                + "Operations: " + state.getOperationSequence() + System.lineSeparator()
                + "Reads: " + state.getReads() + System.lineSeparator()
                + "Writes: " + state.getWrites() + System.lineSeparator()
                + "Removes: " + state.getRemoves() + System.lineSeparator()
                + "Commits: " + state.getCommits() + System.lineSeparator()
                + "Reopen Checks: " + state.getReopenChecks() + System.lineSeparator()
                + "Recovery Checks: " + state.getRecoveryChecks() + System.lineSeparator()
                + "Final Size Bytes: " + finalSizeBytes + System.lineSeparator()
                + "Work Dir: " + config.getWorkDir().getPath() + System.lineSeparator()
                + "Final Report: " + finalReport.getPath() + System.lineSeparator()
                + line;
    }
}
