/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import org.h2.test.longrun.mvstore.MVStoreWorkload;
import org.h2.test.longrun.sql.SqlWorkload;

/**
 * Runs the selected long-running workload until the configured duration expires.
 */
public final class WorkloadRunner {

    private final LongRunConfig config;
    private final ExternalH2JarMetadata h2JarMetadata;

    WorkloadRunner(LongRunConfig config, ExternalH2JarMetadata h2JarMetadata) {
        this.config = config;
        this.h2JarMetadata = h2JarMetadata;
    }

    public int run() throws LongRunFailure {
        File workDir = config.getWorkDir();
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new LongRunFailure("Could not create work dir: " + workDir);
        }
        long deadline = System.currentTimeMillis() + config.getDurationMillis();
        long nextMetrics = System.currentTimeMillis();
        long nextProgress = nextProgressTime();
        long nextState = System.currentTimeMillis();
        long nextReclamation = System.currentTimeMillis() + config.getReclamationIntervalMillis();
        long nextReopen = config.getReopenIntervalMillis() > 0L
                ? System.currentTimeMillis() + config.getReopenIntervalMillis() : Long.MAX_VALUE;
        long nextFault = config.isFaultEnabled()
                ? System.currentTimeMillis() + config.getFaultIntervalMillis() : Long.MAX_VALUE;
        long faultEventId = 0L;
        try (WorkDirLock ignored = WorkDirLock.acquire(workDir)) {
            if (!config.isResume()) {
                cleanPreviousRunArtifacts(workDir);
            }
            File stateFile = new File(workDir, "longrun-state.properties");
            LongRunState state = loadState(stateFile);
            File metricsDir = new File(workDir, "metrics");
            return runLocked(stateFile, state, metricsDir, deadline, nextMetrics, nextProgress, nextState,
                    nextReclamation, nextReopen, nextFault, faultEventId);
        } catch (Exception e) {
            throw new LongRunFailure("Workload failed in " + workDir, e);
        }
    }

    private int runLocked(File stateFile, LongRunState state, File metricsDir, long deadline, long nextMetrics,
            long nextProgress, long nextState, long nextReclamation, long nextReopen, long nextFault, long faultEventId)
            throws Exception {
        File workDir = config.getWorkDir();
        try (MetricsReporter metrics = new MetricsReporter(metricsDir);
                LongRunWorkload workload = openWorkload(state)) {
            JdbcBackupCoordinator backupCoordinator = new JdbcBackupCoordinator(config, workload);
            if (config.isBackupEnabled()) {
                backupCoordinator.start(deadline);
            }
            try {
                metrics.resetBaseline(state);
                ProgressReporter progress = new ProgressReporter(config, state, deadline);
                boolean firstMetricSample = true;
                if (config.isResume()) {
                    workload.verify();
                    state.recoveryCheck();
                    state.checkpoint(stateFile, config);
                    LongRunLog.info(config, "Longrun recovery verification passed, recoveryChecks="
                            + state.getRecoveryChecks());
                }
                while (System.currentTimeMillis() < deadline) {
                    workload.step();
                    long now = System.currentTimeMillis();
                    if (now >= nextMetrics) {
                        metrics.report(state, metricPhase(firstMetricSample));
                        firstMetricSample = false;
                        nextMetrics = now + config.getMetricsIntervalMillis();
                    }
                    if (now >= nextProgress) {
                        progress.report(state, now);
                        nextProgress = now + config.getProgressIntervalMillis();
                    }
                    if (now >= nextState) {
                        state.checkpoint(stateFile, config);
                        enforceSizeLimit(workDir);
                        nextState = now + config.getStateIntervalMillis();
                    }
                    if (config.isReclamationEnabled() && now >= nextReclamation) {
                        org.h2.mvstore.MVStoreOnlineReclamationResult result = workload.runReclamation();
                        if (result != null) {
                            metrics.reportReclamation(result);
                        }
                        nextReclamation = now + config.getReclamationIntervalMillis();
                    }
                    if (now >= nextReopen) {
                        workload.reopenAndVerify();
                        state.reopenCheck();
                        state.checkpoint(stateFile, config);
                        nextReopen = now + config.getReopenIntervalMillis();
                    }
                    if (now >= nextFault) {
                        FaultInjectionResult result = workload.runFaultInjection(++faultEventId);
                        if (result != null) {
                            metrics.reportFaultInjection(result);
                            if (result.isUnexpected()) {
                                throw new LongRunFailure("Unexpected fault injection result: "
                                        + result.getKind() + " " + result.getMessage());
                            }
                        }
                        nextFault = now + config.getFaultIntervalMillis();
                    }
                    if (backupCoordinator.hasFailure()) {
                        LongRunFailure failure = backupCoordinator.getAndClearFailure();
                        if (failure != null) {
                            if (config.isBackupFailOnError()) {
                                throw failure;
                            }
                            LongRunLog.warn(config, "Backup encountered an issue: " + failure.getMessage());
                        }
                    }
                }
                workload.commit();
                if (config.isReclamationEnabled()) {
                    org.h2.mvstore.MVStoreOnlineReclamationResult result = workload.runReclamation();
                    if (result != null) {
                        metrics.reportReclamation(result);
                    }
                }
                workload.verify();
                state.checkpoint(stateFile, config);
                metrics.report(state, MetricPhase.RUNNING);
                long finalSize = directorySize(workDir);
                Properties workloadProperties = new Properties();
                workload.collectReportProperties(workloadProperties);
                File finalReport = new File(workDir, "final-report.properties");
                new FinalReportWriter().write(finalReport, config, state, finalSize, h2JarMetadata, workloadProperties);
                LongRunLog.info(config, LongRunSummaryFormatter.finished(config, state, finalSize, finalReport,
                        System.currentTimeMillis() - state.getStartedMillis()));
                return 0;
            } finally {
                backupCoordinator.close();
            }
        }
    }

    private long nextProgressTime() {
        long interval = config.getProgressIntervalMillis();
        return interval > 0L ? System.currentTimeMillis() + interval : Long.MAX_VALUE;
    }

    private LongRunState loadState(File stateFile) throws LongRunFailure {
        if (!config.isResume()) {
            return new LongRunState();
        }
        try {
            return LongRunState.load(stateFile);
        } catch (IOException e) {
            throw new LongRunFailure("Could not load longrun state from " + stateFile, e);
        }
    }

    private static void cleanPreviousRunArtifacts(File workDir) throws LongRunFailure {
        deleteRecursively(new File(workDir, "metrics"));
        deleteRecursively(new File(workDir, "report"));
        deleteRecursively(new File(workDir, "fault"));
        deleteRecursively(new File(workDir, "backup"));
        deleteRecursively(new File(workDir, "final-report.properties"));
        deleteRecursively(new File(workDir, "longrun-state.properties"));
    }

    private static void deleteRecursively(File file) throws LongRunFailure {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            throw new LongRunFailure("Could not delete old longrun artifact: " + file);
        }
    }

    private void enforceSizeLimit(File workDir) throws LongRunFailure {
        long max = config.getMaxDbSizeBytes();
        if (max > 0L) {
            long size = directorySize(workDir);
            if (size > max) {
                throw new LongRunFailure("Longrun work dir exceeded max size: " + size + " > " + max);
            }
        }
    }

    private static long directorySize(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long size = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                size += directorySize(child);
            }
        }
        return size;
    }

    private LongRunWorkload openWorkload(LongRunState state) throws Exception {
        if (config.getMode() == LongRunMode.MVSTORE) {
            return new MVStoreWorkload(config, state);
        } else if (config.getMode() == LongRunMode.SQL) {
            return new SqlWorkload(config, state);
        }
        throw new LongRunFailure("Mixed mode is planned after SQL mode: " + config.getMode());
    }

    private MetricPhase metricPhase(boolean firstMetricSample) {
        if (!firstMetricSample) {
            return MetricPhase.RUNNING;
        }
        return config.isResume() ? MetricPhase.RECOVERY : MetricPhase.STARTUP;
    }
}
