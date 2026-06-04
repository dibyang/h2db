/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Writes a compact final report for one long-running test run.
 */
public final class FinalReportWriter {

    public void write(File file, LongRunConfig config, LongRunState state, long finalSizeBytes,
            ExternalH2JarMetadata h2JarMetadata, Properties workloadProperties) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("instance", config.getInstanceName());
        properties.setProperty("runName", config.getRunName());
        properties.setProperty("mode", config.getMode().name());
        properties.setProperty("durationMillis", Long.toString(config.getDurationMillis()));
        properties.setProperty("seed", Long.toString(config.getSeed()));
        properties.setProperty("keySpace", Integer.toString(config.getKeySpace()));
        properties.setProperty("valueSizeMin", Integer.toString(config.getValueSizeMin()));
        properties.setProperty("valueSizeMax", Integer.toString(config.getValueSizeMax()));
        properties.setProperty("ledgerMode", config.getLedgerMode());
        properties.setProperty("ledgerMaxEntries", Long.toString(config.getLedgerMaxEntries()));
        properties.setProperty("retentionTimeMillis", Integer.toString(config.getRetentionTimeMillis()));
        properties.setProperty("versionsToKeep", Integer.toString(config.getVersionsToKeep()));
        properties.setProperty("progressIntervalMillis", Long.toString(config.getProgressIntervalMillis()));
        properties.setProperty("reclamationIntervalMillis", Long.toString(config.getReclamationIntervalMillis()));
        properties.setProperty("reclamationMaxCandidateChunks",
                Integer.toString(config.getReclamationMaxCandidateChunks()));
        properties.setProperty("reclamationMaxLiveBytesToRewrite",
                Integer.toString(config.getReclamationMaxLiveBytesToRewrite()));
        properties.setProperty("reclamationMaxRunMillis", Integer.toString(config.getReclamationMaxRunMillis()));
        properties.setProperty("reclamationMaxTailCompactionMillis",
                Integer.toString(config.getReclamationMaxTailCompactionMillis()));
        properties.setProperty("reclamationMinSchedulerIntervalMillis",
                Integer.toString(config.getReclamationMinSchedulerIntervalMillis()));
        properties.setProperty("faultEnabled", Boolean.toString(config.isFaultEnabled()));
        properties.setProperty("faultIntervalMillis", Long.toString(config.getFaultIntervalMillis()));
        properties.setProperty("faultMaxBytes", Integer.toString(config.getFaultMaxBytes()));
        properties.setProperty("faultRetainedCopies", Integer.toString(config.getFaultRetainedCopies()));
        properties.setProperty("operations", Long.toString(state.getOperationSequence()));
        properties.setProperty("reads", Long.toString(state.getReads()));
        properties.setProperty("writes", Long.toString(state.getWrites()));
        properties.setProperty("removes", Long.toString(state.getRemoves()));
        properties.setProperty("commits", Long.toString(state.getCommits()));
        properties.setProperty("reopenChecks", Long.toString(state.getReopenChecks()));
        properties.setProperty("recoveryChecks", Long.toString(state.getRecoveryChecks()));
        properties.setProperty("finalSizeBytes", Long.toString(finalSizeBytes));
        if (h2JarMetadata != null) {
            properties.setProperty("h2Jar", h2JarMetadata.getFile().getPath());
            properties.setProperty("h2JarSizeBytes", Long.toString(h2JarMetadata.getSizeBytes()));
            properties.setProperty("h2JarSha256", h2JarMetadata.getSha256());
            properties.setProperty("h2JarTitle", h2JarMetadata.getImplementationTitle());
            properties.setProperty("h2JarVersion", h2JarMetadata.getImplementationVersion());
        }
        if (workloadProperties != null) {
            properties.putAll(workloadProperties);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            properties.store(out, "H2 longrun final report");
        }
    }
}
