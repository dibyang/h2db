/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
import org.h2.engine.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for the standalone longrun application entrypoint.
 */
public final class LongRunTestAppTest {

    @TempDir
    public File tempDir;

    @Test
    public void completedRunGeneratesReportAutomatically() throws Exception {
        File workDir = new File(tempDir, "work");
        File logFile = new File(tempDir, "longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("H2 LongRun Test App\n");
        }

        int exitCode = LongRunTestApp.run("--work-dir", workDir.getPath(), "--duration", "0", "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        assertTrue(new File(workDir, "final-report.properties").isFile());
        assertTrue(new File(workDir, "report/summary.md").isFile());
        assertTrue(new File(workDir, "report/summary.properties").isFile());
    }

    @Test
    public void startupLogPrintsTestedComponentVersion() throws Exception {
        File workDir = new File(tempDir, "version-work");
        File logFile = new File(tempDir, "version-longrun.out");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, "UTF-8"));
            int exitCode = LongRunTestApp.run("--work-dir", workDir.getPath(), "--duration", "0", "--log-file",
                    logFile.getPath());
            assertEquals(0, exitCode);
        } finally {
            System.setOut(originalOut);
        }
        String text = output.toString("UTF-8");
        assertTrue(text.contains("testedComponent=h2db"));
        assertTrue(text.contains("testedComponentVersion=" + Constants.FULL_VERSION));
        assertTrue(text.contains("testedComponentSource=classpath"));
    }
}
