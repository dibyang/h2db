/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Analyzes longrun output files and writes a compact human-readable report.
 */
public final class ReportAnalyzer {

    private static final int DEFAULT_MAX_ERROR_LINES = 0;
    private static final double DEFAULT_MAX_THROUGHPUT_DROP_RATIO = 0.90D;
    private static final double DEFAULT_MAX_FINAL_SIZE_GB = 64D;
    private static final double DEFAULT_MAX_SIZE_PER_MILLION_OPS_GB = 4D;
    private static final double DEFAULT_MAX_SIZE_AMPLIFICATION = 5D;
    private static final double DEFAULT_MAX_SMOKE_FINAL_SIZE_GB = 5D;
    private static final double DEFAULT_MAX_RECLAMATION_BACKOFF_RATIO = 0.60D;
    private static final int DEFAULT_MIN_RECLAMATION_EVENTS = 1;
    private static final int MIN_SAMPLES_FOR_RECLAMATION_CHECK = 10;
    private static final int MIN_SAMPLES_FOR_PERCENTILE_THROUGHPUT = 20;

    private final File workDir;
    private final File logFile;
    private final boolean requireLogFile;
    private final double minOpsPerSecond;
    private final double maxThroughputDropRatio;
    private final double maxFinalSizeGb;
    private final double maxSizePerMillionOpsGb;
    private final double maxSizeAmplification;
    private final int minReclamationEvents;
    private final int maxErrorLines;

    ReportAnalyzer(CommandLineOptions options) {
        this(options.getWorkDir() == null ? new File("work/smoke") : options.getWorkDir(),
                options.getLogFile() == null ? new File("logs/longrun.out") : options.getLogFile(), true,
                options.getMinOpsPerSecond(), options.getMaxThroughputDropRatio(), options.getMaxFinalSizeGb(),
                options.getMaxSizePerMillionOpsGb(), options.getMaxSizeAmplification(),
                options.getMinReclamationEvents(), options.getMaxErrorLines());
    }

    ReportAnalyzer(File workDir, File logFile) {
        this(workDir, logFile, logFile != null, null, null, null, null, null, null, null);
    }

    private ReportAnalyzer(File workDir, File logFile, boolean requireLogFile, Double minOpsPerSecond,
            Double maxThroughputDropRatio, Double maxFinalSizeGb, Double maxSizePerMillionOpsGb,
            Double maxSizeAmplification, Integer minReclamationEvents, Integer maxErrorLines) {
        this.workDir = workDir;
        this.logFile = logFile;
        this.requireLogFile = requireLogFile;
        this.minOpsPerSecond = minOpsPerSecond == null ? 0D : minOpsPerSecond.doubleValue();
        this.maxThroughputDropRatio = maxThroughputDropRatio == null ? DEFAULT_MAX_THROUGHPUT_DROP_RATIO
                : maxThroughputDropRatio.doubleValue();
        this.maxFinalSizeGb = maxFinalSizeGb == null ? DEFAULT_MAX_FINAL_SIZE_GB : maxFinalSizeGb.doubleValue();
        this.maxSizePerMillionOpsGb = maxSizePerMillionOpsGb == null ? DEFAULT_MAX_SIZE_PER_MILLION_OPS_GB
                : maxSizePerMillionOpsGb.doubleValue();
        this.maxSizeAmplification = maxSizeAmplification == null ? DEFAULT_MAX_SIZE_AMPLIFICATION
                : maxSizeAmplification.doubleValue();
        this.minReclamationEvents = minReclamationEvents == null ? DEFAULT_MIN_RECLAMATION_EVENTS
                : minReclamationEvents.intValue();
        this.maxErrorLines = maxErrorLines == null ? DEFAULT_MAX_ERROR_LINES : maxErrorLines.intValue();
    }

    public int run() throws IOException {
        Analysis analysis = analyze();
        writeReports(analysis);
        File markdownReport = new File(workDir, "report/summary.md");
        System.out.println("Longrun report status: " + analysis.status);
        System.out.println("Report: " + markdownReport.getPath());
        printMarkdownReport(markdownReport);
        return "FAIL".equals(analysis.status) ? 10 : 0;
    }

    private Analysis analyze() throws IOException {
        Analysis analysis = new Analysis();
        File finalReport = new File(workDir, "final-report.properties");
        if (!finalReport.isFile()) {
            analysis.failures.add("Missing final-report.properties in " + workDir.getPath());
        } else {
            try (FileInputStream in = new FileInputStream(finalReport)) {
                analysis.finalReport.load(in);
            }
        }
        analyzeMetrics(analysis);
        analyzeLog(analysis);
        if (analysis.metricSamples == 0) {
            analysis.warnings.add("No regular metric samples found.");
        }
        if (minOpsPerSecond > 0D && analysis.throughputSamples > 0
                && analysis.throughputMinOpsPerSecond < minOpsPerSecond) {
            analysis.warnings.add("Minimum sampled ops/s " + analysis.throughputMinOpsPerSecond
                    + " is lower than threshold " + minOpsPerSecond);
        }
        if (analysis.throughputSamples > 0 && analysis.throughputAvgOpsPerSecond > 0D
                && maxThroughputDropRatio >= 0D) {
            double dropRatio = (analysis.throughputAvgOpsPerSecond - analysis.throughputMinOpsPerSecond)
                    / analysis.throughputAvgOpsPerSecond;
            analysis.throughputDropRatio = Math.max(0D, dropRatio);
            calculateSteadyThroughput(analysis);
            double warningDropRatio = analysis.steadyMetricSamples > 0
                    ? analysis.steadySustainedThroughputDropRatio : analysis.throughputDropRatio;
            analysis.throughputWarningDropRatio = warningDropRatio;
            if (warningDropRatio > maxThroughputDropRatio) {
                analysis.warnings.add("Throughput drop ratio " + warningDropRatio
                        + " exceeded threshold " + maxThroughputDropRatio);
            }
        }
        long finalSizeBytes = parseLong(analysis.finalReport.getProperty("finalSizeBytes"));
        long operations = parseLong(analysis.finalReport.getProperty("operations"));
        analysis.finalSizeGb = toGb(finalSizeBytes);
        analysis.maxSizeAmplificationThreshold = maxSizeAmplification;
        if (maxFinalSizeGb > 0D && analysis.finalSizeGb > maxFinalSizeGb) {
            analysis.warnings.add("Final size " + analysis.finalSizeGb + " GB exceeded threshold "
                    + maxFinalSizeGb + " GB");
        }
        if (maxSizePerMillionOpsGb > 0D && operations > 0L) {
            analysis.sizePerMillionOpsGb = analysis.finalSizeGb / (operations / 1_000_000D);
            if (analysis.sizePerMillionOpsGb > maxSizePerMillionOpsGb) {
                analysis.warnings.add("Size per million operations " + analysis.sizePerMillionOpsGb
                        + " GB exceeded threshold " + maxSizePerMillionOpsGb + " GB");
            }
        }
        double sizeAmplification = parseDouble(analysis.finalReport.getProperty("mvstore.sizeAmplification"));
        if (analysis.maxSizeAmplificationThreshold > 0D && sizeAmplification > analysis.maxSizeAmplificationThreshold) {
            analysis.warnings.add("MVStore size amplification " + sizeAmplification
                    + " exceeded threshold " + analysis.maxSizeAmplificationThreshold);
        }
        if ("MVSTORE".equals(analysis.finalReport.getProperty("mode"))
                && parseLong(analysis.finalReport.getProperty("keySpace")) <= 10_000L
                && analysis.finalSizeGb > DEFAULT_MAX_SMOKE_FINAL_SIZE_GB) {
            analysis.warnings.add("MVStore smoke final size " + analysis.finalSizeGb
                    + " GB is too large for keySpace <= 10000");
        }
        if (onlineReclamationExpected(analysis) && minReclamationEvents > 0
                && analysis.metricSamples >= MIN_SAMPLES_FOR_RECLAMATION_CHECK
                && analysis.reclamationEvents < minReclamationEvents) {
            analysis.warnings.add("Reclamation events " + analysis.reclamationEvents
                    + " is lower than threshold " + minReclamationEvents);
        }
        if (analysis.reclamationEvents > 0 && analysis.reclamationShrinkBytes <= 0L
                && analysis.reclamationEstimatedReclaimedBytes <= 0L) {
            analysis.warnings.add("Reclamation ran " + analysis.reclamationEvents
                    + " times but total shrink and estimated reclaimed bytes are zero");
        }
        if (analysis.reclamationEvents > 0) {
            analysis.reclamationBackoffRatio = analysis.reclamationBackoffEvents / (double) analysis.reclamationEvents;
            if (analysis.reclamationBackoffRatio > DEFAULT_MAX_RECLAMATION_BACKOFF_RATIO) {
                analysis.warnings.add("Reclamation backoff ratio " + analysis.reclamationBackoffRatio
                        + " exceeded threshold " + DEFAULT_MAX_RECLAMATION_BACKOFF_RATIO);
            }
        }
        if (analysis.reclamationIneffectiveSuccessEvents > 0) {
            analysis.warnings.add("Reclamation success events without file shrink: "
                    + analysis.reclamationIneffectiveSuccessEvents);
        }
        if (Boolean.parseBoolean(analysis.finalReport.getProperty("faultEnabled"))
                && analysis.faultInjectionEvents == 0) {
            analysis.warnings.add("Fault injection is enabled but no fault events were recorded.");
        }
        if (analysis.errorLines > maxErrorLines) {
            analysis.failures.add("Suspicious log lines " + analysis.errorLines
                    + " exceeded threshold " + maxErrorLines);
        }
        analysis.status = analysis.failures.isEmpty() ? analysis.warnings.isEmpty() ? "PASS" : "WARN" : "FAIL";
        return analysis;
    }

    private void analyzeMetrics(Analysis analysis) throws IOException {
        File metricsDir = new File(workDir, "metrics");
        if (!metricsDir.isDirectory()) {
            analysis.warnings.add("Metrics directory not found: " + metricsDir.getPath());
            return;
        }
        File[] files = metricsDir.listFiles((dir, name) -> name.startsWith("metrics-") && name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            analysis.warnings.add("No metrics csv files found in " + metricsDir.getPath());
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        analysis.metricFiles = files.length;
        double sumOps = 0D;
        double sumThroughputOps = 0D;
        for (File file : files) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("timeMillis") || line.trim().isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("reclamation,")) {
                        analyzeReclamationLine(analysis, line);
                        continue;
                    }
                    if (line.startsWith("fault,")) {
                        analyzeFaultLine(analysis, line);
                        continue;
                    }
                    String[] parts = line.split(",", -1);
                    if (parts.length < 7) {
                        analysis.warnings.add("Malformed metrics line in " + file.getName() + ": " + line);
                        continue;
                    }
                    double ops = Double.parseDouble(parts[2]);
                    MetricPhase phase = metricPhase(parts);
                    analysis.metricSamples++;
                    sumOps += ops;
                    analysis.minOpsPerSecond = analysis.metricSamples == 1 ? ops
                            : Math.min(analysis.minOpsPerSecond, ops);
                    analysis.maxOpsPerSecond = Math.max(analysis.maxOpsPerSecond, ops);
                    analysis.lastOperations = Long.parseLong(parts[1]);
                    increment(analysis.metricPhaseCounts, phase.name());
                    if (phase == MetricPhase.RUNNING) {
                        analysis.throughputSamples++;
                        sumThroughputOps += ops;
                        analysis.throughputMinOpsPerSecond = analysis.throughputSamples == 1 ? ops
                                : Math.min(analysis.throughputMinOpsPerSecond, ops);
                        analysis.throughputMaxOpsPerSecond = Math.max(analysis.throughputMaxOpsPerSecond, ops);
                        analysis.opsPerSecondSamples.add(Double.valueOf(ops));
                    }
                }
            }
        }
        if (analysis.metricSamples > 0) {
            analysis.avgOpsPerSecond = sumOps / analysis.metricSamples;
        }
        if (analysis.throughputSamples > 0) {
            analysis.throughputAvgOpsPerSecond = sumThroughputOps / analysis.throughputSamples;
        }
    }

    private void analyzeLog(Analysis analysis) throws IOException {
        if (logFile == null) {
            return;
        }
        if (!logFile.isFile()) {
            if (requireLogFile) {
                analysis.warnings.add("Log file not found: " + logFile.getPath());
            }
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isSuspiciousLogLine(line)) {
                    analysis.errorLines++;
                    if (analysis.sampleErrors.size() < 5) {
                        analysis.sampleErrors.add(line);
                    }
                }
            }
        }
    }

    private void writeReports(Analysis analysis) throws IOException {
        File reportDir = new File(workDir, "report");
        if (!reportDir.isDirectory() && !reportDir.mkdirs()) {
            throw new IOException("Could not create " + reportDir);
        }
        writeMarkdown(new File(reportDir, "summary.md"), analysis);
        writeProperties(new File(reportDir, "summary.properties"), analysis);
    }

    private static void printMarkdownReport(File report) throws IOException {
        System.out.println();
        try (BufferedReader reader = new BufferedReader(new FileReader(report))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
    }

    private void writeMarkdown(File file, Analysis analysis) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("# H2 LongRun Report");
            writer.println();
            writer.println("| Item | Value |");
            writer.println("| --- | --- |");
            writer.println("| Status | " + analysis.status + " |");
            writer.println("| Work Dir | " + workDir.getPath() + " |");
            writer.println("| Log File | " + (logFile == null ? "" : logFile.getPath()) + " |");
            writer.println("| Operations | " + value(analysis.finalReport, "operations") + " |");
            writer.println("| Commits | " + value(analysis.finalReport, "commits") + " |");
            writer.println("| Reopen Checks | " + value(analysis.finalReport, "reopenChecks") + " |");
            writer.println("| Recovery Checks | " + value(analysis.finalReport, "recoveryChecks") + " |");
            writer.println("| Final Size Bytes | " + value(analysis.finalReport, "finalSizeBytes") + " |");
            writer.println("| Metric Files | " + analysis.metricFiles + " |");
            writer.println("| Metric Samples | " + analysis.metricSamples + " |");
            writer.println("| Throughput Metric Samples | " + analysis.throughputSamples + " |");
            writer.println("| Throughput Avg Ops/s | " + analysis.throughputAvgOpsPerSecond + " |");
            writer.println("| Throughput Min Ops/s | " + analysis.throughputMinOpsPerSecond + " |");
            writer.println("| Throughput Max Ops/s | " + analysis.throughputMaxOpsPerSecond + " |");
            writer.println("| Avg Ops/s | " + analysis.avgOpsPerSecond + " |");
            writer.println("| Min Ops/s | " + analysis.minOpsPerSecond + " |");
            writer.println("| Max Ops/s | " + analysis.maxOpsPerSecond + " |");
            writer.println("| Throughput Drop Ratio | " + analysis.throughputDropRatio + " |");
            writer.println("| Steady Metric Samples | " + analysis.steadyMetricSamples + " |");
            writer.println("| Steady Avg Ops/s | " + analysis.steadyAvgOpsPerSecond + " |");
            writer.println("| Steady Min Ops/s | " + analysis.steadyMinOpsPerSecond + " |");
            writer.println("| Steady Throughput Drop Ratio | " + analysis.steadyThroughputDropRatio + " |");
            writer.println("| Steady Sustained Ops/s | " + analysis.steadySustainedOpsPerSecond + " |");
            writer.println("| Steady Sustained Throughput Drop Ratio | "
                    + analysis.steadySustainedThroughputDropRatio + " |");
            writer.println("| Throughput Warning Drop Ratio | " + analysis.throughputWarningDropRatio + " |");
            writer.println("| Reclamation Events | " + analysis.reclamationEvents + " |");
            writer.println("| Reclamation Success Events | " + analysis.reclamationSuccessEvents + " |");
            writer.println("| Reclamation Backoff Events | " + analysis.reclamationBackoffEvents + " |");
            writer.println("| Reclamation Backoff Ratio | " + analysis.reclamationBackoffRatio + " |");
            writer.println("| Reclamation Shrink Bytes | " + analysis.reclamationShrinkBytes + " |");
            writer.println("| Reclamation Ineffective Success Events | "
                    + analysis.reclamationIneffectiveSuccessEvents + " |");
            writer.println("| Reclamation First File Size Bytes | " + analysis.reclamationFirstFileSize + " |");
            writer.println("| Reclamation Last File Size Bytes | " + analysis.reclamationLastFileSize + " |");
            writer.println("| Reclamation Min Before Fill Rate | " + analysis.reclamationMinBeforeFillRate + " |");
            writer.println("| Reclamation Max Before Fill Rate | " + analysis.reclamationMaxBeforeFillRate + " |");
            writer.println("| Reclamation Min After Fill Rate | " + analysis.reclamationMinAfterFillRate + " |");
            writer.println("| Reclamation Max After Fill Rate | " + analysis.reclamationMaxAfterFillRate + " |");
            writer.println("| Reclamation Min Before Chunks Fill Rate | "
                    + analysis.reclamationMinBeforeChunksFillRate + " |");
            writer.println("| Reclamation Max Before Chunks Fill Rate | "
                    + analysis.reclamationMaxBeforeChunksFillRate + " |");
            writer.println("| Reclamation Min After Chunks Fill Rate | "
                    + analysis.reclamationMinAfterChunksFillRate + " |");
            writer.println("| Reclamation Max After Chunks Fill Rate | "
                    + analysis.reclamationMaxAfterChunksFillRate + " |");
            writer.println("| Reclamation Estimated Reclaimed Bytes | "
                    + analysis.reclamationEstimatedReclaimedBytes + " |");
            writer.println("| Reclamation Max Before Reclaimable Bytes | "
                    + analysis.reclamationMaxBeforeEstimatedReclaimableBytes + " |");
            writer.println("| Reclamation Max After Reclaimable Bytes | "
                    + analysis.reclamationMaxAfterEstimatedReclaimableBytes + " |");
            writer.println("| Reclamation Max Unknown Map Chunks | " + analysis.reclamationMaxUnknownMapChunks + " |");
            writer.println("| Reclamation Rewritten Events | " + analysis.reclamationRewrittenEvents + " |");
            writer.println("| Reclamation Relocation Map Used Events | "
                    + analysis.reclamationRelocationMapUsedEvents + " |");
            writer.println("| Reclamation Tail Compaction Planned Events | "
                    + analysis.reclamationTailCompactionPlannedEvents + " |");
            writer.println("| Reclamation Tail Compaction Attempted Events | "
                    + analysis.reclamationTailCompactionAttemptedEvents + " |");
            writer.println("| Reclamation Max Candidate Chunks | " + analysis.reclamationMaxCandidateChunks + " |");
            writer.println("| Fault Injection Events | " + analysis.faultInjectionEvents + " |");
            writer.println("| Fault Injection Recovered Events | " + analysis.faultInjectionRecoveredEvents + " |");
            writer.println("| Fault Injection Detected Events | " + analysis.faultInjectionDetectedEvents + " |");
            writer.println("| Fault Injection Unexpected Events | " + analysis.faultInjectionUnexpectedEvents + " |");
            writer.println("| Final Size GB | " + analysis.finalSizeGb + " |");
            writer.println("| Size Per Million Ops GB | " + analysis.sizePerMillionOpsGb + " |");
            writer.println("| MVStore Active Keys | " + value(analysis.finalReport, "mvstore.activeKeys") + " |");
            writer.println("| MVStore Data Entries | " + value(analysis.finalReport, "mvstore.dataEntries") + " |");
            writer.println("| MVStore Ledger Entries | " + value(analysis.finalReport, "mvstore.ledgerEntries") + " |");
            writer.println("| MVStore Ledger Mode | " + value(analysis.finalReport, "mvstore.ledgerMode") + " |");
            writer.println("| MVStore Ledger Max Entries | "
                    + value(analysis.finalReport, "mvstore.ledgerMaxEntries") + " |");
            writer.println("| MVStore Retention Time Millis | "
                    + value(analysis.finalReport, "mvstore.retentionTimeMillis") + " |");
            writer.println("| MVStore Versions To Keep | " + value(analysis.finalReport, "mvstore.versionsToKeep")
                    + " |");
            writer.println("| Progress Interval Millis | "
                    + value(analysis.finalReport, "progressIntervalMillis") + " |");
            writer.println("| Reclamation Interval Millis | "
                    + value(analysis.finalReport, "reclamationIntervalMillis") + " |");
            writer.println("| Reclamation Max Candidate Chunks | "
                    + value(analysis.finalReport, "reclamationMaxCandidateChunks") + " |");
            writer.println("| Reclamation Max Live Bytes To Rewrite | "
                    + value(analysis.finalReport, "reclamationMaxLiveBytesToRewrite") + " |");
            writer.println("| Reclamation Max Run Millis | "
                    + value(analysis.finalReport, "reclamationMaxRunMillis") + " |");
            writer.println("| Reclamation Max Tail Compaction Millis | "
                    + value(analysis.finalReport, "reclamationMaxTailCompactionMillis") + " |");
            writer.println("| Reclamation Min Scheduler Interval Millis | "
                    + value(analysis.finalReport, "reclamationMinSchedulerIntervalMillis") + " |");
            writer.println("| Fault Enabled | " + value(analysis.finalReport, "faultEnabled") + " |");
            writer.println("| Fault Interval Millis | " + value(analysis.finalReport, "faultIntervalMillis") + " |");
            writer.println("| Fault Max Bytes | " + value(analysis.finalReport, "faultMaxBytes") + " |");
            writer.println("| Fault Retained Copies | " + value(analysis.finalReport, "faultRetainedCopies") + " |");
            writer.println("| MVStore Size Amplification | "
                    + value(analysis.finalReport, "mvstore.sizeAmplification") + " |");
            writer.println("| Suspicious Log Lines | " + analysis.errorLines + " |");
            writer.println("| Max Throughput Drop Ratio Threshold | " + maxThroughputDropRatio + " |");
            writer.println("| Max Final Size GB Threshold | " + maxFinalSizeGb + " |");
            writer.println("| Max Size Per Million Ops GB Threshold | " + maxSizePerMillionOpsGb + " |");
            writer.println("| Max Size Amplification Threshold | " + analysis.maxSizeAmplificationThreshold + " |");
            writer.println("| Min Reclamation Events Threshold | " + minReclamationEvents + " |");
            writeSection(writer, "Failures", analysis.failures);
            writeSection(writer, "Warnings", analysis.warnings);
            writeCounters(writer, "Metric Phase Counts", analysis.metricPhaseCounts);
            writeCounters(writer, "Reclamation Status Counts", analysis.reclamationStatusCounts);
            writeCounters(writer, "Reclamation Message Counts", analysis.reclamationMessageCounts);
            writeSection(writer, "Recent Reclamation Events", analysis.recentReclamationEvents);
            writeCounters(writer, "Fault Injection Status Counts", analysis.faultInjectionStatusCounts);
            writeCounters(writer, "Fault Injection Kind Counts", analysis.faultInjectionKindCounts);
            writeSection(writer, "Recent Fault Injection Events", analysis.recentFaultInjectionEvents);
            writeSection(writer, "Sample Suspicious Log Lines", analysis.sampleErrors);
        }
    }

    private void writeProperties(File file, Analysis analysis) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("status", analysis.status);
        properties.setProperty("workDir", workDir.getPath());
        properties.setProperty("logFile", logFile == null ? "" : logFile.getPath());
        properties.setProperty("operations", value(analysis.finalReport, "operations"));
        properties.setProperty("commits", value(analysis.finalReport, "commits"));
        properties.setProperty("reopenChecks", value(analysis.finalReport, "reopenChecks"));
        properties.setProperty("recoveryChecks", value(analysis.finalReport, "recoveryChecks"));
        properties.setProperty("finalSizeBytes", value(analysis.finalReport, "finalSizeBytes"));
        properties.setProperty("metricFiles", Integer.toString(analysis.metricFiles));
        properties.setProperty("metricSamples", Integer.toString(analysis.metricSamples));
        properties.setProperty("throughputMetricSamples", Integer.toString(analysis.throughputSamples));
        properties.setProperty("throughputAvgOpsPerSecond", Double.toString(analysis.throughputAvgOpsPerSecond));
        properties.setProperty("throughputMinOpsPerSecond", Double.toString(analysis.throughputMinOpsPerSecond));
        properties.setProperty("throughputMaxOpsPerSecond", Double.toString(analysis.throughputMaxOpsPerSecond));
        properties.setProperty("avgOpsPerSecond", Double.toString(analysis.avgOpsPerSecond));
        properties.setProperty("minOpsPerSecond", Double.toString(analysis.minOpsPerSecond));
        properties.setProperty("maxOpsPerSecond", Double.toString(analysis.maxOpsPerSecond));
        properties.setProperty("throughputDropRatio", Double.toString(analysis.throughputDropRatio));
        properties.setProperty("steadyMetricSamples", Integer.toString(analysis.steadyMetricSamples));
        properties.setProperty("steadyAvgOpsPerSecond", Double.toString(analysis.steadyAvgOpsPerSecond));
        properties.setProperty("steadyMinOpsPerSecond", Double.toString(analysis.steadyMinOpsPerSecond));
        properties.setProperty("steadyThroughputDropRatio", Double.toString(analysis.steadyThroughputDropRatio));
        properties.setProperty("steadySustainedOpsPerSecond",
                Double.toString(analysis.steadySustainedOpsPerSecond));
        properties.setProperty("steadySustainedThroughputDropRatio",
                Double.toString(analysis.steadySustainedThroughputDropRatio));
        properties.setProperty("throughputWarningDropRatio", Double.toString(analysis.throughputWarningDropRatio));
        properties.setProperty("reclamationEvents", Integer.toString(analysis.reclamationEvents));
        properties.setProperty("reclamationSuccessEvents", Integer.toString(analysis.reclamationSuccessEvents));
        properties.setProperty("reclamationSkippedEvents", Integer.toString(analysis.reclamationSkippedEvents));
        properties.setProperty("reclamationBackoffEvents", Integer.toString(analysis.reclamationBackoffEvents));
        properties.setProperty("reclamationBackoffRatio", Double.toString(analysis.reclamationBackoffRatio));
        properties.setProperty("reclamationIneffectiveSuccessEvents",
                Integer.toString(analysis.reclamationIneffectiveSuccessEvents));
        properties.setProperty("reclamationShrinkBytes", Long.toString(analysis.reclamationShrinkBytes));
        properties.setProperty("reclamationFirstFileSizeBytes", Long.toString(analysis.reclamationFirstFileSize));
        properties.setProperty("reclamationLastFileSizeBytes", Long.toString(analysis.reclamationLastFileSize));
        properties.setProperty("reclamationMinBeforeFillRate", Long.toString(analysis.reclamationMinBeforeFillRate));
        properties.setProperty("reclamationMaxBeforeFillRate", Long.toString(analysis.reclamationMaxBeforeFillRate));
        properties.setProperty("reclamationMinAfterFillRate", Long.toString(analysis.reclamationMinAfterFillRate));
        properties.setProperty("reclamationMaxAfterFillRate", Long.toString(analysis.reclamationMaxAfterFillRate));
        properties.setProperty("reclamationMinBeforeChunksFillRate",
                Long.toString(analysis.reclamationMinBeforeChunksFillRate));
        properties.setProperty("reclamationMaxBeforeChunksFillRate",
                Long.toString(analysis.reclamationMaxBeforeChunksFillRate));
        properties.setProperty("reclamationMinAfterChunksFillRate",
                Long.toString(analysis.reclamationMinAfterChunksFillRate));
        properties.setProperty("reclamationMaxAfterChunksFillRate",
                Long.toString(analysis.reclamationMaxAfterChunksFillRate));
        properties.setProperty("reclamationEstimatedReclaimedBytes",
                Long.toString(analysis.reclamationEstimatedReclaimedBytes));
        properties.setProperty("reclamationMaxBeforeEstimatedReclaimableBytes",
                Long.toString(analysis.reclamationMaxBeforeEstimatedReclaimableBytes));
        properties.setProperty("reclamationMaxAfterEstimatedReclaimableBytes",
                Long.toString(analysis.reclamationMaxAfterEstimatedReclaimableBytes));
        properties.setProperty("reclamationMaxUnknownMapChunks",
                Long.toString(analysis.reclamationMaxUnknownMapChunks));
        properties.setProperty("reclamationRewrittenEvents", Integer.toString(analysis.reclamationRewrittenEvents));
        properties.setProperty("reclamationRelocationMapUsedEvents",
                Integer.toString(analysis.reclamationRelocationMapUsedEvents));
        properties.setProperty("reclamationTailCompactionPlannedEvents",
                Integer.toString(analysis.reclamationTailCompactionPlannedEvents));
        properties.setProperty("reclamationTailCompactionAttemptedEvents",
                Integer.toString(analysis.reclamationTailCompactionAttemptedEvents));
        properties.setProperty("reclamationMaxCandidateChunks",
                Integer.toString(analysis.reclamationMaxCandidateChunks));
        properties.setProperty("reclamationStatusCounts", countersAsString(analysis.reclamationStatusCounts));
        properties.setProperty("reclamationMessageCounts", countersAsString(analysis.reclamationMessageCounts));
        properties.setProperty("faultInjectionEvents", Integer.toString(analysis.faultInjectionEvents));
        properties.setProperty("faultInjectionRecoveredEvents",
                Integer.toString(analysis.faultInjectionRecoveredEvents));
        properties.setProperty("faultInjectionDetectedEvents", Integer.toString(analysis.faultInjectionDetectedEvents));
        properties.setProperty("faultInjectionUnexpectedEvents",
                Integer.toString(analysis.faultInjectionUnexpectedEvents));
        properties.setProperty("faultInjectionStatusCounts",
                countersAsString(analysis.faultInjectionStatusCounts));
        properties.setProperty("faultInjectionKindCounts", countersAsString(analysis.faultInjectionKindCounts));
        properties.setProperty("metricPhaseCounts", countersAsString(analysis.metricPhaseCounts));
        properties.setProperty("finalSizeGb", Double.toString(analysis.finalSizeGb));
        properties.setProperty("sizePerMillionOpsGb", Double.toString(analysis.sizePerMillionOpsGb));
        properties.setProperty("suspiciousLogLines", Integer.toString(analysis.errorLines));
        properties.setProperty("maxThroughputDropRatioThreshold", Double.toString(maxThroughputDropRatio));
        properties.setProperty("maxFinalSizeGbThreshold", Double.toString(maxFinalSizeGb));
        properties.setProperty("maxSizePerMillionOpsGbThreshold", Double.toString(maxSizePerMillionOpsGb));
        properties.setProperty("maxSizeAmplificationThreshold", Double.toString(analysis.maxSizeAmplificationThreshold));
        properties.setProperty("minReclamationEventsThreshold", Integer.toString(minReclamationEvents));
        copyIfPresent(properties, analysis.finalReport, "mvstore.activeKeys");
        copyIfPresent(properties, analysis.finalReport, "mvstore.dataEntries");
        copyIfPresent(properties, analysis.finalReport, "mvstore.ledgerEntries");
        copyIfPresent(properties, analysis.finalReport, "mvstore.ledgerMode");
        copyIfPresent(properties, analysis.finalReport, "mvstore.ledgerMaxEntries");
        copyIfPresent(properties, analysis.finalReport, "mvstore.retentionTimeMillis");
        copyIfPresent(properties, analysis.finalReport, "mvstore.versionsToKeep");
        copyIfPresent(properties, analysis.finalReport, "reclamationIntervalMillis");
        copyIfPresent(properties, analysis.finalReport, "reclamationMaxCandidateChunks");
        copyIfPresent(properties, analysis.finalReport, "reclamationMaxLiveBytesToRewrite");
        copyIfPresent(properties, analysis.finalReport, "reclamationMaxRunMillis");
        copyIfPresent(properties, analysis.finalReport, "reclamationMaxTailCompactionMillis");
        copyIfPresent(properties, analysis.finalReport, "reclamationMinSchedulerIntervalMillis");
        copyIfPresent(properties, analysis.finalReport, "faultEnabled");
        copyIfPresent(properties, analysis.finalReport, "faultIntervalMillis");
        copyIfPresent(properties, analysis.finalReport, "faultMaxBytes");
        copyIfPresent(properties, analysis.finalReport, "faultRetainedCopies");
        copyIfPresent(properties, analysis.finalReport, "mvstore.fileSizeBytes");
        copyIfPresent(properties, analysis.finalReport, "mvstore.estimatedLiveDataBytes");
        copyIfPresent(properties, analysis.finalReport, "mvstore.sizeAmplification");
        copyIfPresent(properties, analysis.finalReport, "mvstore.onlineReclamationBuilderOptionsApplied");
        try (FileOutputStream out = new FileOutputStream(file)) {
            properties.store(out, "H2 longrun analysis summary");
        }
    }

    private static boolean isSuspiciousLogLine(String line) {
        String lower = line.toLowerCase();
        return lower.contains("exception") || lower.contains("error") || lower.contains("longrun failed")
                || lower.contains(" unable to ") || lower.contains("failed:");
    }

    private static void analyzeReclamationLine(Analysis analysis, String line) {
        analysis.reclamationEvents++;
        String[] parts = line.split(",", -1);
        if (parts.length < 11) {
            analysis.warnings.add("Malformed reclamation metrics line: " + line);
            return;
        }
        String status = parts[2];
        String message = parts[3];
        long before = parseLong(parts[4]);
        long after = parseLong(parts[5]);
        long beforeFillRate = parseLong(parts[6]);
        long afterFillRate = parseLong(parts[7]);
        long beforeChunksFillRate = parseLong(parts[8]);
        long afterChunksFillRate = parseLong(parts[9]);
        long shrinkBytes = parseLong(parts[10]);
        long beforeEstimatedReclaimableBytes = partLong(parts, 11);
        long afterEstimatedReclaimableBytes = partLong(parts, 12);
        long estimatedReclaimedBytes = partLong(parts, 13);
        long beforeUnknownMapChunks = partLong(parts, 14);
        long afterUnknownMapChunks = partLong(parts, 15);
        boolean relocationMapUsed = partBoolean(parts, 18);
        boolean tailCompactionPlanned = partBoolean(parts, 20);
        boolean tailCompactionAttempted = partBoolean(parts, 21);
        boolean rewritten = partBoolean(parts, 22);
        int candidateChunkCount = (int) partLong(parts, 23);
        increment(analysis.reclamationStatusCounts, status);
        increment(analysis.reclamationMessageCounts, message);
        if (analysis.reclamationEvents == 1) {
            analysis.reclamationFirstFileSize = before;
            analysis.reclamationMinBeforeFillRate = beforeFillRate;
            analysis.reclamationMaxBeforeFillRate = beforeFillRate;
            analysis.reclamationMinAfterFillRate = afterFillRate;
            analysis.reclamationMaxAfterFillRate = afterFillRate;
            analysis.reclamationMinBeforeChunksFillRate = beforeChunksFillRate;
            analysis.reclamationMaxBeforeChunksFillRate = beforeChunksFillRate;
            analysis.reclamationMinAfterChunksFillRate = afterChunksFillRate;
            analysis.reclamationMaxAfterChunksFillRate = afterChunksFillRate;
        } else {
            analysis.reclamationMinBeforeFillRate = Math.min(analysis.reclamationMinBeforeFillRate, beforeFillRate);
            analysis.reclamationMaxBeforeFillRate = Math.max(analysis.reclamationMaxBeforeFillRate, beforeFillRate);
            analysis.reclamationMinAfterFillRate = Math.min(analysis.reclamationMinAfterFillRate, afterFillRate);
            analysis.reclamationMaxAfterFillRate = Math.max(analysis.reclamationMaxAfterFillRate, afterFillRate);
            analysis.reclamationMinBeforeChunksFillRate = Math.min(analysis.reclamationMinBeforeChunksFillRate,
                    beforeChunksFillRate);
            analysis.reclamationMaxBeforeChunksFillRate = Math.max(analysis.reclamationMaxBeforeChunksFillRate,
                    beforeChunksFillRate);
            analysis.reclamationMinAfterChunksFillRate = Math.min(analysis.reclamationMinAfterChunksFillRate,
                    afterChunksFillRate);
            analysis.reclamationMaxAfterChunksFillRate = Math.max(analysis.reclamationMaxAfterChunksFillRate,
                    afterChunksFillRate);
        }
        analysis.reclamationLastFileSize = after;
        analysis.reclamationEstimatedReclaimedBytes += Math.max(0L, estimatedReclaimedBytes);
        analysis.reclamationMaxBeforeEstimatedReclaimableBytes = Math.max(
                analysis.reclamationMaxBeforeEstimatedReclaimableBytes, beforeEstimatedReclaimableBytes);
        analysis.reclamationMaxAfterEstimatedReclaimableBytes = Math.max(
                analysis.reclamationMaxAfterEstimatedReclaimableBytes, afterEstimatedReclaimableBytes);
        analysis.reclamationMaxUnknownMapChunks = Math.max(analysis.reclamationMaxUnknownMapChunks,
                Math.max(beforeUnknownMapChunks, afterUnknownMapChunks));
        if (rewritten) {
            analysis.reclamationRewrittenEvents++;
        }
        if (relocationMapUsed) {
            analysis.reclamationRelocationMapUsedEvents++;
        }
        if (tailCompactionPlanned) {
            analysis.reclamationTailCompactionPlannedEvents++;
        }
        if (tailCompactionAttempted) {
            analysis.reclamationTailCompactionAttemptedEvents++;
        }
        analysis.reclamationMaxCandidateChunks = Math.max(analysis.reclamationMaxCandidateChunks,
                candidateChunkCount);
        addRecentReclamationEvent(analysis, status, message, before, after, beforeFillRate, afterFillRate,
                beforeChunksFillRate, afterChunksFillRate, shrinkBytes, estimatedReclaimedBytes, rewritten,
                tailCompactionAttempted, candidateChunkCount);
        analysis.reclamationShrinkBytes += Math.max(0L, shrinkBytes);
        if ("SUCCESS".equals(status)) {
            analysis.reclamationSuccessEvents++;
            if (shrinkBytes <= 0L && after >= before && estimatedReclaimedBytes <= 0L && !rewritten) {
                analysis.reclamationIneffectiveSuccessEvents++;
            }
        } else if ("SKIPPED".equals(status)) {
            analysis.reclamationSkippedEvents++;
        }
        if (message.indexOf("BACKOFF") >= 0) {
            analysis.reclamationBackoffEvents++;
        }
    }

    private static void analyzeFaultLine(Analysis analysis, String line) {
        analysis.faultInjectionEvents++;
        String[] parts = line.split(",", -1);
        if (parts.length < 10) {
            analysis.warnings.add("Malformed fault metrics line: " + line);
            return;
        }
        String kind = parts[3];
        String status = parts[4];
        String message = parts[5];
        long offset = partLong(parts, 6);
        long length = partLong(parts, 7);
        long beforeSize = partLong(parts, 8);
        long afterSize = partLong(parts, 9);
        increment(analysis.faultInjectionKindCounts, kind);
        increment(analysis.faultInjectionStatusCounts, status);
        if ("RECOVERED".equals(status)) {
            analysis.faultInjectionRecoveredEvents++;
        } else if (status.startsWith("DETECTED")) {
            analysis.faultInjectionDetectedEvents++;
        } else if (status.startsWith("UNEXPECTED")) {
            analysis.faultInjectionUnexpectedEvents++;
            analysis.failures.add("Unexpected fault injection result: " + kind + " " + message);
        }
        addRecentFaultInjectionEvent(analysis, kind, status, message, offset, length, beforeSize, afterSize);
    }

    private static void addRecentFaultInjectionEvent(Analysis analysis, String kind, String status, String message,
            long offset, long length, long beforeSize, long afterSize) {
        if (analysis.recentFaultInjectionEvents.size() >= 8) {
            analysis.recentFaultInjectionEvents.remove(0);
        }
        analysis.recentFaultInjectionEvents.add(kind + " " + status + " offset=" + offset + " length=" + length
                + " beforeSize=" + beforeSize + " afterSize=" + afterSize + " message=" + message);
    }

    private static String value(Properties properties, String key) {
        return properties.getProperty(key, "");
    }

    private static void copyIfPresent(Properties target, Properties source, String key) {
        String value = source.getProperty(key);
        if (value != null) {
            target.setProperty(key, value);
        }
    }

    private static long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        return Long.parseLong(value.trim());
    }

    private static double parseDouble(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0D;
        }
        return Double.parseDouble(value.trim());
    }

    private static long partLong(String[] parts, int index) {
        return index < parts.length ? parseLong(parts[index]) : 0L;
    }

    private static boolean partBoolean(String[] parts, int index) {
        return index < parts.length && Boolean.parseBoolean(parts[index]);
    }

    private static double toGb(long bytes) {
        return bytes / 1024D / 1024D / 1024D;
    }

    private static boolean onlineReclamationExpected(Analysis analysis) {
        return !"false".equals(analysis.finalReport.getProperty("mvstore.onlineReclamationBuilderOptionsApplied"));
    }

    private static void calculateSteadyThroughput(Analysis analysis) {
        if (analysis.opsPerSecondSamples.size() < 5) {
            return;
        }
        int from = 1;
        int to = analysis.opsPerSecondSamples.size() - 1;
        double sum = 0D;
        double min = Double.MAX_VALUE;
        ArrayList<Double> steadySamples = new ArrayList<>();
        for (int i = from; i < to; i++) {
            double ops = analysis.opsPerSecondSamples.get(i).doubleValue();
            sum += ops;
            min = Math.min(min, ops);
            steadySamples.add(Double.valueOf(ops));
        }
        analysis.steadyMetricSamples = to - from;
        if (analysis.steadyMetricSamples > 0) {
            analysis.steadyAvgOpsPerSecond = sum / analysis.steadyMetricSamples;
            analysis.steadyMinOpsPerSecond = min;
            analysis.steadySustainedOpsPerSecond = sustainedOpsPerSecond(steadySamples);
            if (analysis.steadyAvgOpsPerSecond > 0D) {
                analysis.steadyThroughputDropRatio = Math.max(0D,
                        (analysis.steadyAvgOpsPerSecond - min) / analysis.steadyAvgOpsPerSecond);
                analysis.steadySustainedThroughputDropRatio = Math.max(0D,
                        (analysis.steadyAvgOpsPerSecond - analysis.steadySustainedOpsPerSecond)
                                / analysis.steadyAvgOpsPerSecond);
            }
        }
    }

    private static double sustainedOpsPerSecond(ArrayList<Double> samples) {
        if (samples.isEmpty()) {
            return 0D;
        }
        Collections.sort(samples);
        if (samples.size() < MIN_SAMPLES_FOR_PERCENTILE_THROUGHPUT) {
            return samples.get(0).doubleValue();
        }
        int index = Math.max(0, (int) Math.ceil(samples.size() * 0.05D) - 1);
        return samples.get(index).doubleValue();
    }

    private static MetricPhase metricPhase(String[] parts) {
        if (parts.length < 8 || parts[7].trim().isEmpty()) {
            return MetricPhase.RUNNING;
        }
        try {
            return MetricPhase.valueOf(parts[7].trim());
        } catch (IllegalArgumentException e) {
            return MetricPhase.RUNNING;
        }
    }

    private static void writeSection(PrintWriter writer, String title, List<String> lines) {
        writer.println();
        writer.println("## " + title);
        writer.println();
        if (lines.isEmpty()) {
            writer.println("- None");
        } else {
            for (String line : lines) {
                writer.println("- " + line);
            }
        }
    }

    private static void writeCounters(PrintWriter writer, String title, Map<String, Integer> counters) {
        writer.println();
        writer.println("## " + title);
        writer.println();
        if (counters.isEmpty()) {
            writer.println("- None");
            return;
        }
        for (Map.Entry<String, Integer> entry : counters.entrySet()) {
            writer.println("- " + entry.getKey() + ": " + entry.getValue());
        }
    }

    private static void increment(Map<String, Integer> counters, String key) {
        Integer value = counters.get(key);
        counters.put(key, value == null ? 1 : value.intValue() + 1);
    }

    private static String countersAsString(Map<String, Integer> counters) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counters.entrySet()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static void addRecentReclamationEvent(Analysis analysis, String status, String message, long before,
            long after, long beforeFillRate, long afterFillRate, long beforeChunksFillRate,
            long afterChunksFillRate, long shrinkBytes, long estimatedReclaimedBytes, boolean rewritten,
            boolean tailCompactionAttempted, int candidateChunkCount) {
        if (analysis.recentReclamationEvents.size() >= 8) {
            analysis.recentReclamationEvents.remove(0);
        }
        analysis.recentReclamationEvents.add(status + " " + message + " before=" + before + " after=" + after
                + " beforeFillRate=" + beforeFillRate + " afterFillRate=" + afterFillRate
                + " beforeChunksFillRate=" + beforeChunksFillRate + " afterChunksFillRate=" + afterChunksFillRate
                + " shrinkBytes=" + shrinkBytes + " estimatedReclaimedBytes=" + estimatedReclaimedBytes
                + " rewritten=" + rewritten + " tailCompactionAttempted=" + tailCompactionAttempted
                + " candidateChunks=" + candidateChunkCount);
    }

    private static final class Analysis {
        private final Properties finalReport = new Properties();
        private final ArrayList<String> failures = new ArrayList<>();
        private final ArrayList<String> warnings = new ArrayList<>();
        private final ArrayList<String> sampleErrors = new ArrayList<>();
        private String status;
        private int metricFiles;
        private int metricSamples;
        private int throughputSamples;
        private final ArrayList<Double> opsPerSecondSamples = new ArrayList<>();
        private final LinkedHashMap<String, Integer> metricPhaseCounts = new LinkedHashMap<>();
        private double throughputMinOpsPerSecond;
        private double throughputMaxOpsPerSecond;
        private double throughputAvgOpsPerSecond;
        private long lastOperations;
        private double minOpsPerSecond;
        private double maxOpsPerSecond;
        private double avgOpsPerSecond;
        private double throughputDropRatio;
        private int steadyMetricSamples;
        private double steadyMinOpsPerSecond;
        private double steadyAvgOpsPerSecond;
        private double steadyThroughputDropRatio;
        private double steadySustainedOpsPerSecond;
        private double steadySustainedThroughputDropRatio;
        private double throughputWarningDropRatio;
        private double finalSizeGb;
        private double sizePerMillionOpsGb;
        private double maxSizeAmplificationThreshold;
        private int reclamationEvents;
        private int reclamationSuccessEvents;
        private int reclamationSkippedEvents;
        private int reclamationBackoffEvents;
        private double reclamationBackoffRatio;
        private int reclamationIneffectiveSuccessEvents;
        private long reclamationShrinkBytes;
        private long reclamationFirstFileSize;
        private long reclamationLastFileSize;
        private long reclamationMinBeforeFillRate;
        private long reclamationMaxBeforeFillRate;
        private long reclamationMinAfterFillRate;
        private long reclamationMaxAfterFillRate;
        private long reclamationMinBeforeChunksFillRate;
        private long reclamationMaxBeforeChunksFillRate;
        private long reclamationMinAfterChunksFillRate;
        private long reclamationMaxAfterChunksFillRate;
        private long reclamationEstimatedReclaimedBytes;
        private long reclamationMaxBeforeEstimatedReclaimableBytes;
        private long reclamationMaxAfterEstimatedReclaimableBytes;
        private long reclamationMaxUnknownMapChunks;
        private int reclamationRewrittenEvents;
        private int reclamationRelocationMapUsedEvents;
        private int reclamationTailCompactionPlannedEvents;
        private int reclamationTailCompactionAttemptedEvents;
        private int reclamationMaxCandidateChunks;
        private final LinkedHashMap<String, Integer> reclamationStatusCounts = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> reclamationMessageCounts = new LinkedHashMap<>();
        private final ArrayList<String> recentReclamationEvents = new ArrayList<>();
        private int faultInjectionEvents;
        private int faultInjectionRecoveredEvents;
        private int faultInjectionDetectedEvents;
        private int faultInjectionUnexpectedEvents;
        private final LinkedHashMap<String, Integer> faultInjectionStatusCounts = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> faultInjectionKindCounts = new LinkedHashMap<>();
        private final ArrayList<String> recentFaultInjectionEvents = new ArrayList<>();
        private int errorLines;
    }
}
