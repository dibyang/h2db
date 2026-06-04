/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for longrun checkpoint state persistence.
 */
public final class LongRunStateTest {

    @TempDir
    public File tempDir;

    @Test
    public void checkpointRoundTripsReliabilityCounters() throws Exception {
        LongRunConfig config = new LongRunConfig.Builder()
                .runName("state-test")
                .workDir(tempDir)
                .build();
        LongRunState state = new LongRunState();
        state.nextSequence();
        state.read();
        state.write();
        state.remove();
        state.commit();
        state.reopenCheck();
        state.recoveryCheck();
        File file = new File(tempDir, "longrun-state.properties");

        state.checkpoint(file, config);
        LongRunState loaded = LongRunState.load(file);

        assertEquals(1L, loaded.getOperationSequence());
        assertEquals(1L, loaded.getReads());
        assertEquals(1L, loaded.getWrites());
        assertEquals(1L, loaded.getRemoves());
        assertEquals(1L, loaded.getCommits());
        assertEquals(1L, loaded.getReopenChecks());
        assertEquals(1L, loaded.getRecoveryChecks());
    }

    @Test
    public void operationSequenceDoesNotMoveBackwardsAfterRecoverySync() {
        LongRunState state = new LongRunState();
        state.nextSequence();

        state.ensureOperationSequenceAtLeast(10L);
        state.ensureOperationSequenceAtLeast(5L);

        assertEquals(10L, state.getOperationSequence());
        assertEquals(11L, state.nextSequence());
    }
}
