/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates one bounded online reclamation round for an MVStore.
 */
public final class MVStoreReclamationCoordinator {

    private MVStoreReclamationCoordinator() {
    }

    public static MVStoreOnlineReclamationResult run(MVStore store) {
        return run(store, MVStoreReclamationRequest.DEFAULT);
    }

    public static MVStoreOnlineReclamationResult run(MVStore store, MVStoreReclamationRequest request) {
        if (store == null) {
            throw new IllegalArgumentException("store");
        }
        if (request == null) {
            request = MVStoreReclamationRequest.DEFAULT;
        }
        MVStoreReclamationAnalysis before = MVStoreReclamationAnalyzer.analyze(store,
                request.getTargetFillRate());
        ArrayList<Integer> selected = selectCandidateIds(before, request.getMaxCandidateChunks());
        if (selected.isEmpty()) {
            return result(MVStoreReclamationStatus.SKIPPED, "NO_RECLAMATION_CANDIDATE", before, before,
                    false, selected);
        }
        if (request.isDryRun() || request.getMaxLiveBytesToRewrite() == 0) {
            return result(MVStoreReclamationStatus.SKIPPED, "DRY_RUN", before, before, false, selected);
        }
        long start = System.currentTimeMillis();
        boolean rewritten;
        try {
            rewritten = store.compact(request.getTargetFillRate(), request.getMaxLiveBytesToRewrite());
        } catch (RuntimeException e) {
            MVStoreReclamationAnalysis afterFailure = MVStoreReclamationAnalyzer.analyze(store,
                    request.getTargetFillRate());
            return result(MVStoreReclamationStatus.FAILED, "RECLAMATION_FAILED: " + e.getMessage(),
                    before, afterFailure, false, selected);
        }
        MVStoreReclamationAnalysis after = MVStoreReclamationAnalyzer.analyze(store, request.getTargetFillRate());
        if (!rewritten) {
            return result(MVStoreReclamationStatus.NO_PROGRESS, "NO_OPEN_MAP_RELOCATION_PROGRESS", before, after,
                    false, selected);
        }
        if (request.getMaxRunMillis() > 0L && System.currentTimeMillis() - start > request.getMaxRunMillis()) {
            return result(MVStoreReclamationStatus.SUCCESS, "RECLAMATION_PAUSED_BY_TIME_BUDGET", before,
                    after, true, selected);
        }
        return result(MVStoreReclamationStatus.SUCCESS, "RECLAMATION_ROUND_FINISHED", before, after, true,
                selected);
    }

    private static ArrayList<Integer> selectCandidateIds(MVStoreReclamationAnalysis analysis, int maxCandidates) {
        ArrayList<Integer> selected = new ArrayList<>();
        List<ChunkLivenessSnapshot> candidates = analysis.getCandidates();
        for (int i = 0; i < candidates.size() && selected.size() < maxCandidates; i++) {
            selected.add(candidates.get(i).getChunkId());
        }
        return selected;
    }

    private static MVStoreOnlineReclamationResult result(MVStoreReclamationStatus status, String message,
            MVStoreReclamationAnalysis before, MVStoreReclamationAnalysis after, boolean rewritten,
            ArrayList<Integer> candidateChunks) {
        return new MVStoreOnlineReclamationResult(status, message, before, after, rewritten,
                new ArrayList<>(candidateChunks));
    }
}
