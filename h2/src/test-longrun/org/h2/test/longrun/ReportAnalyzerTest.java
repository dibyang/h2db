/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JUnit checks for longrun report generation.
 */
public final class ReportAnalyzerTest {

    @TempDir
    public File tempDir;

    @Test
    public void reportWritesSummaryFiles() throws Exception {
        File workDir = new File(tempDir, "work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        writeMetrics(new File(metricsDir, "metrics-20260601.csv"));
        File logFile = new File(tempDir, "longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        File summary = new File(workDir, "report/summary.properties");
        assertTrue(summary.isFile());
        Properties properties = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(summary)) {
            properties.load(in);
        }
        assertEquals("PASS", properties.getProperty("status"));
        assertEquals("2", properties.getProperty("metricSamples"));
        assertEquals("1", properties.getProperty("reclamationEvents"));
    }

    @Test
    public void reportWarnsOnLargeThroughputDrop() throws Exception {
        File workDir = new File(tempDir, "drop-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,50,1.0,10,30,10,1\n");
            writer.write("2,100,100.0,20,60,20,2\n");
        }
        File logFile = new File(tempDir, "drop-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(
                new File(workDir, "report/summary.properties"))) {
            properties.load(in);
        }
        assertEquals("WARN", properties.getProperty("status"));
    }

    @Test
    public void reportIgnoresTailThroughputDrop() throws Exception {
        File workDir = new File(tempDir, "tail-drop-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,50,1.0,10,30,10,1\n");
            writer.write("2,100,100.0,20,60,20,2\n");
            writer.write("3,150,100.0,30,90,30,3\n");
            writer.write("4,200,100.0,40,120,40,4\n");
            writer.write("5,250,1.0,50,150,50,5\n");
        }
        File logFile = new File(tempDir, "tail-drop-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(
                new File(workDir, "report/summary.properties"))) {
            properties.load(in);
        }
        assertEquals("PASS", properties.getProperty("status"));
        assertEquals("3", properties.getProperty("steadyMetricSamples"));
        assertEquals("100.0", properties.getProperty("steadyMinOpsPerSecond"));
    }

    @Test
    public void reportIgnoresIsolatedThroughputDipInLongRuns() throws Exception {
        File workDir = new File(tempDir, "isolated-dip-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,100,100.0,10,80,10,1\n");
            for (int i = 2; i <= 101; i++) {
                double ops = i == 50 ? 1.0D : 100.0D;
                writer.write(i + "," + (i * 100) + "," + ops + ",10,80,10," + i + "\n");
            }
            writer.write("102,10200,100.0,10,80,10,102\n");
        }
        File logFile = new File(tempDir, "isolated-dip-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath(), "--min-reclamation-events", "0");

        assertEquals(0, exitCode);
        Properties properties = loadSummary(workDir);
        assertEquals("PASS", properties.getProperty("status"));
        assertEquals("100", properties.getProperty("steadyMetricSamples"));
        assertEquals("1.0", properties.getProperty("steadyMinOpsPerSecond"));
        assertEquals("100.0", properties.getProperty("steadySustainedOpsPerSecond"));
    }

    @Test
    public void reportIgnoresIsolatedThroughputDipInShortPerformanceRuns() throws Exception {
        File workDir = new File(tempDir, "short-performance-dip-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,100,100.0,10,80,10,1\n");
            for (int i = 2; i <= 31; i++) {
                double ops = i == 15 ? 1.0D : 100.0D;
                writer.write(i + "," + (i * 100) + "," + ops + ",10,80,10," + i + "\n");
            }
            writer.write("32,3200,100.0,10,80,10,32\n");
        }
        File logFile = new File(tempDir, "short-performance-dip-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath(), "--min-reclamation-events", "0");

        assertEquals(0, exitCode);
        Properties properties = loadSummary(workDir);
        assertEquals("PASS", properties.getProperty("status"));
        assertEquals("30", properties.getProperty("steadyMetricSamples"));
        assertEquals("1.0", properties.getProperty("steadyMinOpsPerSecond"));
        assertEquals("100.0", properties.getProperty("steadySustainedOpsPerSecond"));
        assertEquals("0.0", properties.getProperty("throughputWarningDropRatio"));
    }

    @Test
    public void reportWarnsOnIneffectiveReclamation() throws Exception {
        File workDir = new File(tempDir, "reclaim-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,100,100.0,10,80,10,1\n");
            writer.write("reclamation,2,SKIPPED,RECLAMATION_SCHEDULER_BACKOFF,100,100,100,100,0,0,0\n");
            writer.write("reclamation,3,SKIPPED,RECLAMATION_SCHEDULER_BACKOFF,200,200,100,100,0,0,0\n");
            writer.write("reclamation,4,SUCCESS,RECLAMATION_PAUSED_BY_TIME_BUDGET,300,300,90,80,0,0,0\n");
        }
        File logFile = new File(tempDir, "reclaim-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(
                new File(workDir, "report/summary.properties"))) {
            properties.load(in);
        }
        assertEquals("WARN", properties.getProperty("status"));
        assertEquals("0", properties.getProperty("reclamationShrinkBytes"));
        assertEquals("1", properties.getProperty("reclamationIneffectiveSuccessEvents"));
        assertEquals("SKIPPED=2,SUCCESS=1", properties.getProperty("reclamationStatusCounts"));
        assertEquals("RECLAMATION_SCHEDULER_BACKOFF=2,RECLAMATION_PAUSED_BY_TIME_BUDGET=1",
                properties.getProperty("reclamationMessageCounts"));
        assertEquals("100", properties.getProperty("reclamationFirstFileSizeBytes"));
        assertEquals("300", properties.getProperty("reclamationLastFileSizeBytes"));
        assertEquals("90", properties.getProperty("reclamationMinBeforeFillRate"));
        assertEquals("100", properties.getProperty("reclamationMaxBeforeFillRate"));
        assertEquals("80", properties.getProperty("reclamationMinAfterFillRate"));
        assertEquals("100", properties.getProperty("reclamationMaxAfterFillRate"));
        assertEquals("0", properties.getProperty("reclamationMinBeforeChunksFillRate"));
        assertEquals("0", properties.getProperty("reclamationMaxBeforeChunksFillRate"));
        assertEquals("0", properties.getProperty("reclamationMinAfterChunksFillRate"));
        assertEquals("0", properties.getProperty("reclamationMaxAfterChunksFillRate"));
        String summary = read(new File(workDir, "report/summary.md"));
        assertTrue(summary.contains("## Reclamation Message Counts"));
        assertTrue(summary.contains("RECLAMATION_SCHEDULER_BACKOFF: 2"));
        assertTrue(summary.contains("SUCCESS RECLAMATION_PAUSED_BY_TIME_BUDGET"));
        assertTrue(summary.contains("beforeChunksFillRate=0"));
    }

    @Test
    public void reportDoesNotRequireReclamationEventsWhenTestedJarDoesNotSupportOnlineReclamation()
            throws Exception {
        File workDir = new File(tempDir, "baseline-no-reclaim-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        Properties finalReport = new Properties();
        finalReport.setProperty("operations", "2000");
        finalReport.setProperty("commits", "2");
        finalReport.setProperty("finalSizeBytes", "4096");
        finalReport.setProperty("mvstore.onlineReclamationBuilderOptionsApplied", "false");
        try (FileOutputStream out = new FileOutputStream(new File(workDir, "final-report.properties"))) {
            finalReport.store(out, "test");
        }
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            for (int i = 1; i <= 12; i++) {
                writer.write(i + "," + (i * 100) + ",100.0,10,80,10," + i + "\n");
            }
        }
        File logFile = new File(tempDir, "baseline-no-reclaim-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = loadSummary(workDir);
        assertEquals("PASS", properties.getProperty("status"));
        assertEquals("false", properties.getProperty("mvstore.onlineReclamationBuilderOptionsApplied"));
        assertEquals("0", properties.getProperty("reclamationEvents"));
    }

    @Test
    public void reportParsesExtendedReclamationDiagnostics() throws Exception {
        File workDir = new File(tempDir, "extended-reclaim-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,100,100.0,10,80,10,1\n");
            writer.write("reclamation,2,NO_PROGRESS,NO_OPEN_MAP_RELOCATION_PROGRESS,"
                    + "1000,900,80,70,60,50,100,500,300,200,2,1,true,true,false,true,true,true,false,3,1|2|3\n");
        }
        File logFile = new File(tempDir, "extended-reclaim-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(
                new File(workDir, "report/summary.properties"))) {
            properties.load(in);
        }
        assertEquals("100", properties.getProperty("reclamationShrinkBytes"));
        assertEquals("200", properties.getProperty("reclamationEstimatedReclaimedBytes"));
        assertEquals("500", properties.getProperty("reclamationMaxBeforeEstimatedReclaimableBytes"));
        assertEquals("300", properties.getProperty("reclamationMaxAfterEstimatedReclaimableBytes"));
        assertEquals("2", properties.getProperty("reclamationMaxUnknownMapChunks"));
        assertEquals("0", properties.getProperty("reclamationRewrittenEvents"));
        assertEquals("0", properties.getProperty("reclamationRelocationMapUsedEvents"));
        assertEquals("1", properties.getProperty("reclamationTailCompactionPlannedEvents"));
        assertEquals("1", properties.getProperty("reclamationTailCompactionAttemptedEvents"));
        assertEquals("3", properties.getProperty("reclamationMaxCandidateChunks"));
        String summary = read(new File(workDir, "report/summary.md"));
        assertTrue(summary.contains("tailCompactionAttempted=true"));
        assertTrue(summary.contains("candidateChunks=3"));
    }

    @Test
    public void reportDoesNotWarnWhenSuccessfulReclamationRewritesWithoutImmediateShrink() throws Exception {
        File workDir = new File(tempDir, "rewrite-no-shrink-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,100,100.0,10,80,10,1\n");
            writer.write("reclamation,2,SUCCESS,RECLAMATION_PAUSED_BY_TIME_BUDGET,"
                    + "1000,1200,90,10,10,90,0,500,100,400,0,0,true,true,false,true,true,true,true,8,1|2\n");
        }
        File logFile = new File(tempDir, "rewrite-no-shrink-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(
                new File(workDir, "report/summary.properties"))) {
            properties.load(in);
        }
        assertEquals("PASS", properties.getProperty("status"));
        assertEquals("0", properties.getProperty("reclamationIneffectiveSuccessEvents"));
        assertEquals("1", properties.getProperty("reclamationRewrittenEvents"));
        assertEquals("400", properties.getProperty("reclamationEstimatedReclaimedBytes"));
    }

    @Test
    public void reportWarnsOnHighSizeAmplification() throws Exception {
        File workDir = new File(tempDir, "amplification-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"), "18.0");
        writeMetrics(new File(metricsDir, "metrics-20260601.csv"));
        File logFile = new File(tempDir, "amplification-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = loadSummary(workDir);
        assertEquals("WARN", properties.getProperty("status"));
        assertEquals("5.0", properties.getProperty("maxSizeAmplificationThreshold"));
        assertEquals("18.0", properties.getProperty("mvstore.sizeAmplification"));
    }

    @Test
    public void performanceProfileUsesDefaultSizeAmplificationLimit() throws Exception {
        File workDir = new File(tempDir, "performance-amplification-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"), "5.8", "mvstore-performance");
        writeMetrics(new File(metricsDir, "metrics-20260601.csv"));
        File logFile = new File(tempDir, "performance-amplification-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = loadSummary(workDir);
        assertEquals("WARN", properties.getProperty("status"));
        assertEquals("5.0", properties.getProperty("maxSizeAmplificationThreshold"));
        assertEquals("5.8", properties.getProperty("mvstore.sizeAmplification"));
    }

    @Test
    public void reportAcceptsConfiguredSizeAmplificationLimit() throws Exception {
        File workDir = new File(tempDir, "amplification-override-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"), "18.0", "mvstore-performance");
        writeMetrics(new File(metricsDir, "metrics-20260601.csv"));
        File logFile = new File(tempDir, "amplification-override-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath(), "--max-size-amplification", "20");

        assertEquals(0, exitCode);
        Properties properties = loadSummary(workDir);
        assertEquals("PASS", properties.getProperty("status"));
        assertEquals("20.0", properties.getProperty("maxSizeAmplificationThreshold"));
    }

    @Test
    public void reportParsesFaultInjectionEvents() throws Exception {
        File workDir = new File(tempDir, "fault-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        writeFinalReport(new File(workDir, "final-report.properties"));
        File metrics = new File(metricsDir, "metrics-20260601.csv");
        try (FileWriter writer = new FileWriter(metrics)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,100,100.0,10,80,10,1\n");
            writer.write("fault,2,1,TRUNCATE,DETECTED,MVStoreException:bad,0,128,4096,3968,"
                    + "work/fault/fault-1.mv.db\n");
            writer.write("fault,3,2,BIT_FLIP,RECOVERED,copy opened and verified,12,1,4096,4096,"
                    + "work/fault/fault-2.mv.db\n");
            writer.write("fault,4,3,ZERO_RANGE,DETECTED_BY_VERIFY,Checksum mismatch,64,32,4096,4096,"
                    + "work/fault/fault-3.mv.db\n");
        }
        File logFile = new File(tempDir, "fault-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath(), "--min-reclamation-events", "0");

        assertEquals(0, exitCode);
        Properties properties = loadSummary(workDir);
        assertEquals("PASS", properties.getProperty("status"));
        assertEquals("3", properties.getProperty("faultInjectionEvents"));
        assertEquals("2", properties.getProperty("faultInjectionDetectedEvents"));
        assertEquals("1", properties.getProperty("faultInjectionRecoveredEvents"));
        assertEquals("DETECTED=1,RECOVERED=1,DETECTED_BY_VERIFY=1",
                properties.getProperty("faultInjectionStatusCounts"));
        assertEquals("TRUNCATE=1,BIT_FLIP=1,ZERO_RANGE=1",
                properties.getProperty("faultInjectionKindCounts"));
        String summary = read(new File(workDir, "report/summary.md"));
        assertTrue(summary.contains("## Recent Fault Injection Events"));
        assertTrue(summary.contains("TRUNCATE DETECTED"));
        assertTrue(summary.contains("ZERO_RANGE DETECTED_BY_VERIFY"));
    }

    @Test
    public void reportWarnsWhenFaultInjectionIsEnabledButNoEventsRan() throws Exception {
        File workDir = new File(tempDir, "fault-empty-work");
        File metricsDir = new File(workDir, "metrics");
        assertTrue(metricsDir.mkdirs());
        Properties finalReport = new Properties();
        finalReport.setProperty("operations", "100");
        finalReport.setProperty("commits", "2");
        finalReport.setProperty("finalSizeBytes", "4096");
        finalReport.setProperty("faultEnabled", "true");
        try (FileOutputStream out = new FileOutputStream(new File(workDir, "final-report.properties"))) {
            finalReport.store(out, "test");
        }
        writeMetrics(new File(metricsDir, "metrics-20260601.csv"));
        File logFile = new File(tempDir, "fault-empty-longrun.out");
        try (FileWriter writer = new FileWriter(logFile)) {
            writer.write("Longrun finished\n");
        }

        int exitCode = LongRunTestApp.run("report", "--work-dir", workDir.getPath(), "--log-file",
                logFile.getPath());

        assertEquals(0, exitCode);
        Properties properties = loadSummary(workDir);
        assertEquals("WARN", properties.getProperty("status"));
        assertEquals("0", properties.getProperty("faultInjectionEvents"));
        String summary = read(new File(workDir, "report/summary.md"));
        assertTrue(summary.contains("Fault injection is enabled but no fault events were recorded."));
    }

    private static void writeFinalReport(File file) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("operations", "100");
        properties.setProperty("commits", "2");
        properties.setProperty("finalSizeBytes", "4096");
        try (FileOutputStream out = new FileOutputStream(file)) {
            properties.store(out, "test");
        }
    }

    private static void writeFinalReport(File file, String sizeAmplification) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("operations", "100");
        properties.setProperty("commits", "2");
        properties.setProperty("finalSizeBytes", "4096");
        properties.setProperty("mode", "MVSTORE");
        properties.setProperty("keySpace", "10000");
        properties.setProperty("mvstore.sizeAmplification", sizeAmplification);
        try (FileOutputStream out = new FileOutputStream(file)) {
            properties.store(out, "test");
        }
    }

    private static void writeFinalReport(File file, String sizeAmplification, String runName) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("operations", "100");
        properties.setProperty("commits", "2");
        properties.setProperty("finalSizeBytes", "4096");
        properties.setProperty("mode", "MVSTORE");
        properties.setProperty("keySpace", "10000");
        properties.setProperty("runName", runName);
        properties.setProperty("mvstore.sizeAmplification", sizeAmplification);
        try (FileOutputStream out = new FileOutputStream(file)) {
            properties.store(out, "test");
        }
    }

    private static Properties loadSummary(File workDir) throws Exception {
        Properties properties = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(
                new File(workDir, "report/summary.properties"))) {
            properties.load(in);
        }
        return properties;
    }

    private static void writeMetrics(File file) throws Exception {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("timeMillis,operations,opsPerSecond,reads,writes,removes,commits\n");
            writer.write("1,50,10.0,10,30,10,1\n");
            writer.write("2,100,20.0,20,60,20,2\n");
            writer.write("reclamation,3,SUCCESS,ok,100,80,50,70,0,0,20\n");
        }
    }

    private static String read(File file) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }
}
