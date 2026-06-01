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
    private final int beforeUnknownMapChunkCount;
    private final int afterUnknownMapChunkCount;
    private final boolean lazyMapOwnershipSupported;
    private final boolean relocationMapAllowed;
    private final boolean relocationMapUsed;
    private final boolean tailCompactionAllowed;
    private final boolean tailCompactionAttempted;
    private final boolean rewritten;
    private final ArrayList<Integer> candidateChunks;

    MVStoreOnlineReclamationResult(MVStoreReclamationStatus status, String message,
            MVStoreReclamationAnalysis before, MVStoreReclamationAnalysis after, boolean rewritten,
            boolean relocationMapAllowed, boolean relocationMapUsed, boolean tailCompactionAllowed,
            boolean tailCompactionAttempted, ArrayList<Integer> candidateChunks) {
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
        beforeUnknownMapChunkCount = before.getUnknownMapChunkCount();
        afterUnknownMapChunkCount = after.getUnknownMapChunkCount();
        lazyMapOwnershipSupported = before.isLazyMapOwnershipSupported() && after.isLazyMapOwnershipSupported();
        this.relocationMapAllowed = relocationMapAllowed;
        this.relocationMapUsed = relocationMapUsed;
        this.tailCompactionAllowed = tailCompactionAllowed;
        this.tailCompactionAttempted = tailCompactionAttempted;
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

    public int getBeforeUnknownMapChunkCount() {
        return beforeUnknownMapChunkCount;
    }

    public int getAfterUnknownMapChunkCount() {
        return afterUnknownMapChunkCount;
    }

    public boolean isLazyMapOwnershipSupported() {
        return lazyMapOwnershipSupported;
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

    public boolean isTailCompactionAllowed() {
        return tailCompactionAllowed;
    }

    public boolean isTailCompactionAttempted() {
        return tailCompactionAttempted;
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
                ", beforeUnknownMapChunkCount=" + beforeUnknownMapChunkCount +
                ", afterUnknownMapChunkCount=" + afterUnknownMapChunkCount +
                ", lazyMapOwnershipSupported=" + lazyMapOwnershipSupported +
                ", relocationMapAllowed=" + relocationMapAllowed +
                ", relocationMapUsed=" + relocationMapUsed +
                ", tailCompactionAllowed=" + tailCompactionAllowed +
                ", tailCompactionAttempted=" + tailCompactionAttempted +
                ", rewritten=" + rewritten +
                ", candidateChunks=" + candidateChunks;
    }
}
