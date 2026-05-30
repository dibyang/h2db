/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Read-only analyzer for MVStore chunk-level space reclamation.
 */
public final class MVStoreReclamationAnalyzer {

    private static final int DEFAULT_TARGET_FILL_RATE = 50;

    private MVStoreReclamationAnalyzer() {
    }

    public static MVStoreReclamationAnalysis analyze(MVStore store) {
        return analyze(store, DEFAULT_TARGET_FILL_RATE);
    }

    public static MVStoreReclamationAnalysis analyze(MVStore store, int targetFillRate) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        if (targetFillRate < 0 || targetFillRate > 100) {
            throw new IllegalArgumentException("targetFillRate");
        }
        FileStore<?> fileStore = store.getFileStore();
        if (fileStore == null) {
            return new MVStoreReclamationAnalysis(targetFillRate, 0L, 100, 100,
                    new ArrayList<ChunkLivenessSnapshot>(), new ArrayList<ChunkLivenessSnapshot>());
        }
        ArrayList<ChunkLivenessSnapshot> chunks = new ArrayList<>();
        ArrayList<ChunkLivenessSnapshot> candidates = new ArrayList<>();
        long oldestVersionToKeep = store.getOldestVersionToKeep();
        for (Chunk<?> chunk : fileStore.getChunks().values()) {
            ChunkLivenessSnapshot snapshot = new ChunkLivenessSnapshot(chunk, oldestVersionToKeep, targetFillRate);
            chunks.add(snapshot);
            if (snapshot.isCandidate()) {
                candidates.add(snapshot);
            }
        }
        Collections.sort(chunks, ChunkIdComparator.INSTANCE);
        Collections.sort(candidates, CandidateComparator.INSTANCE);
        return new MVStoreReclamationAnalysis(targetFillRate, fileStore.size(), fileStore.getFillRate(),
                fileStore.getChunksFillRate(), chunks, candidates);
    }

    private static final class ChunkIdComparator implements Comparator<ChunkLivenessSnapshot> {
        static final ChunkIdComparator INSTANCE = new ChunkIdComparator();

        @Override
        public int compare(ChunkLivenessSnapshot a, ChunkLivenessSnapshot b) {
            return Integer.compare(a.getChunkId(), b.getChunkId());
        }
    }

    private static final class CandidateComparator implements Comparator<ChunkLivenessSnapshot> {
        static final CandidateComparator INSTANCE = new CandidateComparator();

        @Override
        public int compare(ChunkLivenessSnapshot a, ChunkLivenessSnapshot b) {
            int result = Integer.compare(b.getScore(), a.getScore());
            if (result == 0) {
                result = Integer.compare(a.getFillRate(), b.getFillRate());
            }
            if (result == 0) {
                result = Integer.compare(a.getChunkId(), b.getChunkId());
            }
            return result;
        }
    }
}
