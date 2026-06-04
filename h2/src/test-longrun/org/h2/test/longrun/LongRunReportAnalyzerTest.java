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
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for longrun report analysis.
 */
public final class LongRunReportAnalyzerTest {

    @TempDir
    public File tempDir;

    @Test
    public void throughputDropIgnoresStartupAndRecoveryMetricPhases() throws Exception {
        File workDir = new File(tempDir, "work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(workDir);
        try (FileWriter writer = new FileWriter(new File(metricsDir, "metrics-20260602.csv"))) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits,phase\n");
            writeMetric(writer, 1, 1D, MetricPhase.STARTUP);
            writeMetric(writer, 2, 1D, MetricPhase.RECOVERY);
            for (int i = 3; i <= 9; i++) {
                writeMetric(writer, i, 1_000D, MetricPhase.RUNNING);
            }
        }

        int exitCode = new ReportAnalyzer(workDir, null).run();

        assertEquals(0, exitCode);
        Properties summary = loadSummary(workDir);
        assertEquals("PASS", summary.getProperty("status"));
        assertEquals("9", summary.getProperty("metricSamples"));
        assertEquals("7", summary.getProperty("throughputMetricSamples"));
        assertEquals("STARTUP=1,RECOVERY=1,RUNNING=7", summary.getProperty("metricPhaseCounts"));
        assertEquals("0.0", summary.getProperty("steadySustainedThroughputDropRatio"));
    }

    @Test
    public void reportRunPrintsMarkdownSummary() throws Exception {
        File workDir = new File(tempDir, "printed-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(workDir);
        try (FileWriter writer = new FileWriter(new File(metricsDir, "metrics-20260602.csv"))) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits,phase\n");
            writeMetric(writer, 1, 100D, MetricPhase.RUNNING);
            writeMetric(writer, 2, 100D, MetricPhase.RUNNING);
        }

        String output = runReportAndCaptureOutput(workDir);

        assertTrue(output.contains("Longrun report status: PASS"));
        assertTrue(output.contains("# H2 LongRun Report"));
        assertTrue(output.contains("| Status | PASS |"));
        assertTrue(output.contains("## Warnings"));
    }

    private static void writeFinalReport(File workDir) throws Exception {
        assertTrue(workDir.isDirectory() || workDir.mkdirs());
        try (FileWriter writer = new FileWriter(new File(workDir, "final-report.properties"))) {
            writer.write("operations=9\n");
            writer.write("commits=0\n");
            writer.write("reopenChecks=0\n");
            writer.write("recoveryChecks=1\n");
            writer.write("finalSizeBytes=4096\n");
            writer.write("mode=MVSTORE\n");
            writer.write("keySpace=100000\n");
            writer.write("faultEnabled=false\n");
            writer.write("mvstore.sizeAmplification=1.0\n");
        }
    }

    private static void writeMetric(FileWriter writer, int index, double opsPerSecond, MetricPhase phase)
            throws Exception {
        writer.write(Long.toString(index));
        writer.write(',');
        writer.write(Integer.toString(index));
        writer.write(',');
        writer.write(Double.toString(opsPerSecond));
        writer.write(",0,0,0,0,");
        writer.write(phase.name());
        writer.write('\n');
    }

    private static Properties loadSummary(File workDir) throws Exception {
        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream(new File(workDir, "report/summary.properties"))) {
            properties.load(in);
        }
        return properties;
    }

    private static String runReportAndCaptureOutput(File workDir) throws Exception {
        PrintStream oldOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, "UTF-8"));
            new ReportAnalyzer(workDir, null).run();
        } finally {
            System.setOut(oldOut);
        }
        return out.toString("UTF-8");
    }
}
