/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of one MVStore online reclamation round.
 */
public final class MVStoreOnlineReclamationResult {

    private final MVStoreReclamationStatus status;
    private final String message;
    private final long beforeFileSize;
    private final long afterFileSize;
    private final int beforeFillRate;
    private final int afterFillRate;
    private final int beforeChunksFillRate;
    private final int afterChunksFillRate;
    private final long beforeEstimatedReclaimableBytes;
    private final long afterEstimatedReclaimableBytes;
    private final long estimatedReclaimedBytes;
    private final boolean relocationMapAllowed;
    private final boolean relocationMapUsed;
    private final boolean rewritten;
    private final ArrayList<Integer> candidateChunks;

    MVStoreOnlineReclamationResult(MVStoreReclamationStatus status, String message,
            MVStoreReclamationAnalysis before, MVStoreReclamationAnalysis after, boolean rewritten,
            boolean relocationMapAllowed, boolean relocationMapUsed, ArrayList<Integer> candidateChunks) {
        this.status = status;
        this.message = message;
        beforeFileSize = before.getFileSize();
        afterFileSize = after.getFileSize();
        beforeFillRate = before.getFillRate();
        afterFillRate = after.getFillRate();
        beforeChunksFillRate = before.getChunksFillRate();
        afterChunksFillRate = after.getChunksFillRate();
        beforeEstimatedReclaimableBytes = before.getEstimatedReclaimableBytes();
        afterEstimatedReclaimableBytes = after.getEstimatedReclaimableBytes();
        estimatedReclaimedBytes = Math.max(0L, beforeEstimatedReclaimableBytes - afterEstimatedReclaimableBytes);
        this.relocationMapAllowed = relocationMapAllowed;
        this.relocationMapUsed = relocationMapUsed;
        this.rewritten = rewritten;
        this.candidateChunks = candidateChunks;
    }

    public MVStoreReclamationStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public long getBeforeFileSize() {
        return beforeFileSize;
    }

    public long getAfterFileSize() {
        return afterFileSize;
    }

    public int getBeforeFillRate() {
        return beforeFillRate;
    }

    public int getAfterFillRate() {
        return afterFillRate;
    }

    public int getBeforeChunksFillRate() {
        return beforeChunksFillRate;
    }

    public int getAfterChunksFillRate() {
        return afterChunksFillRate;
    }

    public long getBeforeEstimatedReclaimableBytes() {
        return beforeEstimatedReclaimableBytes;
    }

    public long getAfterEstimatedReclaimableBytes() {
        return afterEstimatedReclaimableBytes;
    }

    public long getEstimatedReclaimedBytes() {
        return estimatedReclaimedBytes;
    }

    public boolean isRewritten() {
        return rewritten;
    }

    public boolean isRelocationMapAllowed() {
        return relocationMapAllowed;
    }

    public boolean isRelocationMapUsed() {
        return relocationMapUsed;
    }

    public List<Integer> getCandidateChunks() {
        return Collections.unmodifiableList(candidateChunks);
    }

    public boolean isSuccess() {
        return status == MVStoreReclamationStatus.SUCCESS;
    }

    public String getDiagnosticSummary() {
        return "status=" + status +
                ", message=" + message +
                ", beforeFileSize=" + beforeFileSize +
                ", afterFileSize=" + afterFileSize +
                ", beforeFillRate=" + beforeFillRate +
                ", afterFillRate=" + afterFillRate +
                ", beforeChunksFillRate=" + beforeChunksFillRate +
                ", afterChunksFillRate=" + afterChunksFillRate +
                ", beforeEstimatedReclaimableBytes=" + beforeEstimatedReclaimableBytes +
                ", afterEstimatedReclaimableBytes=" + afterEstimatedReclaimableBytes +
                ", estimatedReclaimedBytes=" + estimatedReclaimedBytes +
                ", relocationMapAllowed=" + relocationMapAllowed +
                ", relocationMapUsed=" + relocationMapUsed +
                ", rewritten=" + rewritten +
                ", candidateChunks=" + candidateChunks;
    }
}
