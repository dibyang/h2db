/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/**
 * JUnit checks for terminal progress log lines.
 */
public final class ProgressReporterTest {

    @Test
    public void progressReporterEmitsRecognizableProgressLine() {
        LongRunConfig config = new LongRunConfig.Builder()
                .durationMillis(60_000L)
                .build();
        LongRunState state = new LongRunState();
        state.nextSequence();
        state.nextSequence();
        state.commit();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            new ProgressReporter(config, state, state.getStartedMillis() + 60_000L)
                    .report(state, state.getStartedMillis() + 30_000L);
        } finally {
            System.setOut(oldOut);
        }

        String text = out.toString();
        assertTrue(text.contains("PROGRESS [##########----------]  50% ops=2 rate="), text);
        assertTrue(text.contains("eta=30s"), text);
        assertTrue(text.contains("checks=0/0"), text);
        assertTrue(text.contains("percent=50"), text);
    }
}
