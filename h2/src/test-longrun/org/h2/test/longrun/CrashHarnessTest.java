/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * JUnit checks for crash harness cycle decisions.
 */
public final class CrashHarnessTest {

    @Test
    public void onlyFirstCrashWorkerStartsFromCleanWorkDir() {
        assertFalse(CrashHarness.shouldResumeWorkloadWorker(1));
        assertTrue(CrashHarness.shouldResumeWorkloadWorker(2));
        assertTrue(CrashHarness.shouldResumeWorkloadWorker(15));
    }

    @Test
    public void workerProgressLinesAreRecognizedForMainLogForwarding() {
        assertTrue(CrashHarness.isWorkerProgressLine("[worker pid=123] PROGRESS percent=42 operations=10"));
        assertTrue(CrashHarness.isWorkerProgressLine("PROGRESS percent=42 operations=10"));
        assertFalse(CrashHarness.isWorkerProgressLine("[worker pid=123] H2 LongRun Test App role=worker"));
    }

    @Test
    public void workerLogFilesAreSeparatedByCycleAndPhase() {
        File workDir = new File("work/crash");

        assertEquals(new File(new File(workDir, "worker-logs"), "cycle-003-run.log"),
                CrashHarness.workerLogFile(workDir, 3, false));
        assertEquals(new File(new File(workDir, "worker-logs"), "cycle-003-recovery.log"),
                CrashHarness.workerLogFile(workDir, 3, true));
    }
}
