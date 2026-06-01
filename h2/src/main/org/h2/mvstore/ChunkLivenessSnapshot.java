/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * Read-only liveness snapshot for one MVStore chunk.
 */
public final class ChunkLivenessSnapshot {

    /**
     * Reason why this chunk is not selected for relocation.
     */
    public enum PinnedReason {
        NONE,
        ACTIVE_VERSION,
        NOT_REWRITABLE,
        NOT_ALLOCATED,
        NOT_LIVE,
        UNKNOWN_MAP,
        RECENT_CHUNK,
        LOW_VALUE
    }

    private final int chunkId;
    private final long block;
    private final int lengthBlocks;
    private final long version;
    private final int pageCount;
    private final int livePageCount;
    private final long maxLength;
    private final long liveMaxLength;
    private final long deadBytes;
    private final int fillRate;
    private final int score;
    private final boolean candidate;
    private final PinnedReason pinnedReason;

    ChunkLivenessSnapshot(Chunk<?> chunk, long oldestVersionToKeep, int targetFillRate, long timeSinceCreation,
            int retentionTime) {
        chunkId = chunk.id;
        block = chunk.block;
        lengthBlocks = chunk.len;
        version = chunk.version;
        pageCount = chunk.pageCount;
        livePageCount = chunk.pageCountLive;
        maxLength = chunk.maxLen;
        liveMaxLength = chunk.maxLenLive;
        deadBytes = Math.max(0L, maxLength - liveMaxLength);
        fillRate = chunk.getFillRate();
        pinnedReason = determinePinnedReason(chunk, oldestVersionToKeep, targetFillRate, timeSinceCreation,
                retentionTime);
        candidate = pinnedReason == PinnedReason.NONE;
        score = calculateScore(chunk, targetFillRate, candidate);
    }

    private static PinnedReason determinePinnedReason(Chunk<?> chunk, long oldestVersionToKeep,
            int targetFillRate, long timeSinceCreation, int retentionTime) {
        if (!chunk.isAllocated()) {
            return PinnedReason.NOT_ALLOCATED;
        }
        if (!chunk.isLive()) {
            return PinnedReason.NOT_LIVE;
        }
        if (chunk.unusedAtVersion > 0 && chunk.unusedAtVersion >= oldestVersionToKeep) {
            return PinnedReason.ACTIVE_VERSION;
        }
        if (!chunk.isRewritable()) {
            return PinnedReason.NOT_REWRITABLE;
        }
        if (retentionTime >= 0 && chunk.time + retentionTime > timeSinceCreation) {
            return PinnedReason.RECENT_CHUNK;
        }
        if (chunk.getFillRate() > targetFillRate) {
            return PinnedReason.LOW_VALUE;
        }
        return PinnedReason.NONE;
    }

    private static int calculateScore(Chunk<?> chunk, int targetFillRate, boolean candidate) {
        if (!candidate || chunk.maxLen <= 0L) {
            return 0;
        }
        long deadBytes = Math.max(0L, chunk.maxLen - chunk.maxLenLive);
        int fillGap = Math.max(0, targetFillRate - chunk.getFillRate());
        long score = deadBytes / 1024L + fillGap * 100L + chunk.block;
        return score > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) score;
    }

    public int getChunkId() {
        return chunkId;
    }

    public long getBlock() {
        return block;
    }

    public int getLengthBlocks() {
        return lengthBlocks;
    }

    public long getVersion() {
        return version;
    }

    public int getPageCount() {
        return pageCount;
    }

    public int getLivePageCount() {
        return livePageCount;
    }

    public long getMaxLength() {
        return maxLength;
    }

    public long getLiveMaxLength() {
        return liveMaxLength;
    }

    public long getDeadBytes() {
        return deadBytes;
    }

    public int getFillRate() {
        return fillRate;
    }

    public int getScore() {
        return score;
    }

    public boolean isCandidate() {
        return candidate;
    }

    public PinnedReason getPinnedReason() {
        return pinnedReason;
    }
}
