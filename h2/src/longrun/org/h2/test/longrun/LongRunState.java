/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Mutable counters and checkpoint state for one long-running test run.
 */
public final class LongRunState {

    private long startedMillis = System.currentTimeMillis();
    private long operationSequence;
    private long reads;
    private long writes;
    private long removes;
    private long commits;
    private long reopenChecks;
    private long recoveryChecks;

    public long nextSequence() {
        return ++operationSequence;
    }

    public void ensureOperationSequenceAtLeast(long sequence) {
        operationSequence = Math.max(operationSequence, sequence);
    }

    public long getOperationSequence() {
        return operationSequence;
    }

    public long getReads() {
        return reads;
    }

    public long getWrites() {
        return writes;
    }

    public long getRemoves() {
        return removes;
    }

    public long getCommits() {
        return commits;
    }

    public long getStartedMillis() {
        return startedMillis;
    }

    public long getReopenChecks() {
        return reopenChecks;
    }

    public long getRecoveryChecks() {
        return recoveryChecks;
    }

    public void read() {
        reads++;
    }

    public void write() {
        writes++;
    }

    public void remove() {
        removes++;
    }

    public void commit() {
        commits++;
    }

    public void reopenCheck() {
        reopenChecks++;
    }

    public void recoveryCheck() {
        recoveryChecks++;
    }

    public void checkpoint(File file, LongRunConfig config) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", "1");
        properties.setProperty("runName", config.getRunName());
        properties.setProperty("mode", config.getMode().name());
        properties.setProperty("seed", Long.toString(config.getSeed()));
        properties.setProperty("startedMillis", Long.toString(startedMillis));
        properties.setProperty("lastCheckpointMillis", Long.toString(System.currentTimeMillis()));
        properties.setProperty("operationSequence", Long.toString(operationSequence));
        properties.setProperty("reads", Long.toString(reads));
        properties.setProperty("writes", Long.toString(writes));
        properties.setProperty("removes", Long.toString(removes));
        properties.setProperty("commits", Long.toString(commits));
        properties.setProperty("reopenChecks", Long.toString(reopenChecks));
        properties.setProperty("recoveryChecks", Long.toString(recoveryChecks));
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            properties.store(out, "H2 longrun state");
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not replace " + file);
        }
        if (!temp.renameTo(file)) {
            throw new IOException("Could not move " + temp + " to " + file);
        }
    }

    public static LongRunState load(File file) throws IOException {
        LongRunState state = new LongRunState();
        if (file == null || !file.isFile()) {
            return state;
        }
        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            properties.load(in);
        }
        state.startedMillis = parseLong(properties, "startedMillis", System.currentTimeMillis());
        state.operationSequence = parseLong(properties, "operationSequence", 0L);
        state.reads = parseLong(properties, "reads", 0L);
        state.writes = parseLong(properties, "writes", 0L);
        state.removes = parseLong(properties, "removes", 0L);
        state.commits = parseLong(properties, "commits", 0L);
        state.reopenChecks = parseLong(properties, "reopenChecks", 0L);
        state.recoveryChecks = parseLong(properties, "recoveryChecks", 0L);
        return state;
    }

    private static long parseLong(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.trim().isEmpty() ? defaultValue : Long.parseLong(value.trim());
    }
}
