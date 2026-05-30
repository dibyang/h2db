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
        MVStoreReclamationJournal.recover(store);
        MVStoreReclamationAnalysis before = MVStoreReclamationAnalyzer.analyze(store,
                request.getTargetFillRate());
        ArrayList<Integer> selected = selectCandidateIds(before, request.getMaxCandidateChunks());
        if (selected.isEmpty()) {
            return result(MVStoreReclamationStatus.SKIPPED, "NO_RECLAMATION_CANDIDATE", before, before,
                    false, request, selected);
        }
        MVStoreReclamationJournal journal = request.isJournalEnabled()
                ? MVStoreReclamationJournal.begin(store, selected) : null;
        if (request.isDryRun() || request.getMaxLiveBytesToRewrite() == 0) {
            complete(journal);
            return result(MVStoreReclamationStatus.SKIPPED, "DRY_RUN", before, before, false, request, selected);
        }
        long start = System.currentTimeMillis();
        boolean rewritten;
        try {
            phase(journal, "EVACUATING");
            rewritten = store.compact(request.getTargetFillRate(), request.getMaxLiveBytesToRewrite());
        } catch (RuntimeException e) {
            MVStoreReclamationAnalysis afterFailure = MVStoreReclamationAnalyzer.analyze(store,
                    request.getTargetFillRate());
            phase(journal, "FAILED");
            return result(MVStoreReclamationStatus.FAILED, "RECLAMATION_FAILED: " + e.getMessage(),
                    before, afterFailure, false, request, selected);
        }
        boolean tailCompactionAttempted = false;
        if (rewritten && request.isTailCompactionAllowed() && request.getMaxTailCompactionMillis() > 0) {
            publish(journal);
            phase(journal, "SHRINKING");
            store.compactFile(request.getMaxTailCompactionMillis());
            tailCompactionAttempted = true;
        }
        MVStoreReclamationAnalysis after = MVStoreReclamationAnalyzer.analyze(store, request.getTargetFillRate());
        if (!rewritten) {
            complete(journal);
            return result(MVStoreReclamationStatus.NO_PROGRESS, "NO_OPEN_MAP_RELOCATION_PROGRESS", before, after,
                    false, request, tailCompactionAttempted, selected);
        }
        publish(journal);
        complete(journal);
        if (request.getMaxRunMillis() > 0L && System.currentTimeMillis() - start > request.getMaxRunMillis()) {
            return result(MVStoreReclamationStatus.SUCCESS, "RECLAMATION_PAUSED_BY_TIME_BUDGET", before,
                    after, true, request, tailCompactionAttempted, selected);
        }
        return result(MVStoreReclamationStatus.SUCCESS, "RECLAMATION_ROUND_FINISHED", before, after, true,
                request, tailCompactionAttempted, selected);
    }

    public static MVStoreReclamationRecovery recover(MVStore store) {
        return MVStoreReclamationJournal.recover(store);
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
            MVStoreReclamationRequest request, ArrayList<Integer> candidateChunks) {
        return result(status, message, before, after, rewritten, request, false, candidateChunks);
    }

    private static MVStoreOnlineReclamationResult result(MVStoreReclamationStatus status, String message,
            MVStoreReclamationAnalysis before, MVStoreReclamationAnalysis after, boolean rewritten,
            MVStoreReclamationRequest request, boolean tailCompactionAttempted, ArrayList<Integer> candidateChunks) {
        return new MVStoreOnlineReclamationResult(status, message, before, after, rewritten,
                request.isRelocationMapAllowed(), false, request.isTailCompactionAllowed(),
                tailCompactionAttempted, new ArrayList<>(candidateChunks));
    }

    private static void phase(MVStoreReclamationJournal journal, String phase) {
        if (journal != null) {
            journal.phase(phase);
        }
    }

    private static void complete(MVStoreReclamationJournal journal) {
        if (journal != null) {
            journal.complete();
        }
    }

    private static void publish(MVStoreReclamationJournal journal) {
        if (journal != null) {
            journal.publish();
        }
    }
}
