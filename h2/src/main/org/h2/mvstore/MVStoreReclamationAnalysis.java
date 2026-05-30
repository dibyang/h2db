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
 * Read-only analysis result for MVStore online space reclamation.
 */
public final class MVStoreReclamationAnalysis {

    private final int targetFillRate;
    private final long fileSize;
    private final int fillRate;
    private final int chunksFillRate;
    private final long estimatedReclaimableBytes;
    private final ArrayList<ChunkLivenessSnapshot> chunks;
    private final ArrayList<ChunkLivenessSnapshot> candidates;

    MVStoreReclamationAnalysis(int targetFillRate, long fileSize, int fillRate, int chunksFillRate,
            ArrayList<ChunkLivenessSnapshot> chunks, ArrayList<ChunkLivenessSnapshot> candidates) {
        this.targetFillRate = targetFillRate;
        this.fileSize = fileSize;
        this.fillRate = fillRate;
        this.chunksFillRate = chunksFillRate;
        this.chunks = chunks;
        this.candidates = candidates;
        long bytes = 0L;
        for (ChunkLivenessSnapshot candidate : candidates) {
            bytes += candidate.getDeadBytes();
        }
        estimatedReclaimableBytes = bytes;
    }

    public int getTargetFillRate() {
        return targetFillRate;
    }

    public long getFileSize() {
        return fileSize;
    }

    public int getFillRate() {
        return fillRate;
    }

    public int getChunksFillRate() {
        return chunksFillRate;
    }

    public long getEstimatedReclaimableBytes() {
        return estimatedReclaimableBytes;
    }

    public List<ChunkLivenessSnapshot> getChunks() {
        return Collections.unmodifiableList(chunks);
    }

    public List<ChunkLivenessSnapshot> getCandidates() {
        return Collections.unmodifiableList(candidates);
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    public String getDiagnosticSummary() {
        return "targetFillRate=" + targetFillRate +
                ", fileSize=" + fileSize +
                ", fillRate=" + fillRate +
                ", chunksFillRate=" + chunksFillRate +
                ", chunks=" + chunks.size() +
                ", candidates=" + candidates.size() +
                ", estimatedReclaimableBytes=" + estimatedReclaimableBytes;
    }
}
