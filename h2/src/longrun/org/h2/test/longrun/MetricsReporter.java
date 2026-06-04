/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.longrun;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import org.h2.mvstore.MVStoreOnlineReclamationResult;

/**
 * Writes simple machine-readable metrics for long-running tests.
 */
public final class MetricsReporter implements AutoCloseable {

    private final File directory;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
    private PrintWriter writer;
    private String currentDate;
    private long lastOperations;
    private long lastMillis;

    MetricsReporter(File directory) throws IOException {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        lastMillis = System.currentTimeMillis();
    }

    public void resetBaseline(LongRunState state) {
        lastOperations = state.getOperationSequence();
        lastMillis = System.currentTimeMillis();
    }

    public void report(LongRunState state, MetricPhase phase) throws IOException {
        ensureWriter();
        long now = System.currentTimeMillis();
        long operations = state.getOperationSequence();
        long deltaMillis = Math.max(1L, now - lastMillis);
        double opsPerSecond = (operations - lastOperations) * 1000.0 / deltaMillis;
        writer.println(now + "," + operations + "," + opsPerSecond + "," + state.getReads() + ","
                + state.getWrites() + "," + state.getRemoves() + "," + state.getCommits() + "," + phase);
        writer.flush();
        lastOperations = operations;
        lastMillis = now;
    }

    public void reportReclamation(MVStoreOnlineReclamationResult result) throws IOException {
        ensureWriter();
        writer.println("reclamation," + System.currentTimeMillis() + "," + result.getStatus() + ","
                + result.getMessage() + "," + result.getBeforeFileSize() + "," + result.getAfterFileSize()
                + "," + result.getBeforeFillRate() + "," + result.getAfterFillRate() + ","
                + result.getBeforeChunksFillRate() + "," + result.getAfterChunksFillRate() + ","
                + result.getShrinkBytes() + "," + result.getBeforeEstimatedReclaimableBytes() + ","
                + result.getAfterEstimatedReclaimableBytes() + "," + result.getEstimatedReclaimedBytes() + ","
                + result.getBeforeUnknownMapChunkCount() + "," + result.getAfterUnknownMapChunkCount() + ","
                + result.isLazyMapOwnershipSupported() + "," + result.isRelocationMapAllowed() + ","
                + result.isRelocationMapUsed() + "," + result.isTailCompactionAllowed() + ","
                + result.isTailCompactionPlanned() + "," + result.isTailCompactionAttempted() + ","
                + result.isRewritten() + "," + result.getCandidateChunks().size() + ","
                + candidateChunks(result.getCandidateChunks()));
        writer.flush();
    }

    public void reportFaultInjection(FaultInjectionResult result) throws IOException {
        ensureWriter();
        writer.println("fault," + System.currentTimeMillis() + "," + result.getEventId() + ","
                + result.getKind() + "," + result.getStatus() + "," + result.getMessage() + ","
                + result.getOffset() + "," + result.getLength() + "," + result.getBeforeSize() + ","
                + result.getAfterSize() + "," + result.getFile().getPath());
        writer.flush();
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.close();
        }
    }

    private void ensureWriter() throws IOException {
        String date = dateFormat.format(new Date());
        if (date.equals(currentDate) && writer != null) {
            return;
        }
        if (writer != null) {
            writer.close();
        }
        currentDate = date;
        File file = new File(directory, "metrics-" + date + ".csv");
        boolean empty = !file.exists() || file.length() == 0L;
        writer = new PrintWriter(new FileWriter(file, true));
        if (empty) {
            writer.println("timeMillis,operations,opsPerSecond,reads,writes,removes,commits,phase");
        }
    }

    private static String candidateChunks(List<Integer> chunks) {
        StringBuilder builder = new StringBuilder();
        for (Integer chunk : chunks) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(chunk);
        }
        return builder.toString();
    }
}
