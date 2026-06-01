/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.mvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreOnlineReclamationResult;
import org.h2.mvstore.MVStoreReclamationCode;
import org.h2.mvstore.MVStoreReclamationRequest;
import org.h2.mvstore.MVStoreReclamationScheduler;
import org.h2.mvstore.MVStoreReclamationStatus;
import org.junit.jupiter.api.Test;

/**
 * Fast JUnit contract checks for MVStore online reclamation.
 */
class MVStoreReclamationContractTest {

    @Test
    void defaultRequestKeepsStableOperationalDefaults() {
        MVStoreReclamationRequest request = MVStoreReclamationRequest.DEFAULT;

        assertFalse(request.isDryRun());
        assertEquals(50, request.getTargetFillRate());
        assertEquals(1, request.getMaxCandidateChunks());
        assertEquals(16 * 1024 * 1024, request.getMaxLiveBytesToRewrite());
        assertEquals(0L, request.getMaxRunMillis());
        assertFalse(request.isJournalEnabled());
        assertFalse(request.isRelocationMapAllowed());
        assertTrue(request.isTailCompactionAllowed());
        assertEquals(0, request.getMaxTailCompactionMillis());
    }

    @Test
    void requestBuilderRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new MVStoreReclamationRequest.Builder().targetFillRate(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new MVStoreReclamationRequest.Builder().targetFillRate(101));
        assertThrows(IllegalArgumentException.class,
                () -> new MVStoreReclamationRequest.Builder().maxCandidateChunks(0));
        assertThrows(IllegalArgumentException.class,
                () -> new MVStoreReclamationRequest.Builder().maxLiveBytesToRewrite(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new MVStoreReclamationRequest.Builder().maxRunMillis(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> new MVStoreReclamationRequest.Builder().maxTailCompactionMillis(-1));
    }

    @Test
    void schedulerBuilderRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> MVStoreReclamationScheduler.builder().request(null));
        assertThrows(IllegalArgumentException.class,
                () -> MVStoreReclamationScheduler.builder().minIntervalMillis(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> MVStoreReclamationScheduler.builder().failureBackoffMillis(-1L));
    }

    @Test
    void disabledSchedulerReturnsStableDiagnostic() {
        MVStore store = new MVStore.Builder().open();
        try {
            MVStoreReclamationScheduler scheduler = MVStoreReclamationScheduler.builder().build();
            MVStoreOnlineReclamationResult result = scheduler.runIfEnabled(store);

            assertFalse(scheduler.isEnabled());
            assertEquals(MVStoreReclamationStatus.SKIPPED, result.getStatus());
            assertEquals(MVStoreReclamationCode.RECLAMATION_SCHEDULER_DISABLED, result.getMessage());
            assertFalse(result.isSuccess());
            assertFalse(result.isRewritten());
            assertDiagnosticSummaryContainsStableFields(result);
            assertThrows(UnsupportedOperationException.class, () -> result.getCandidateChunks().add(1));
        } finally {
            store.close();
        }
    }

    @Test
    void schedulerBackoffReturnsStableDiagnostic() {
        MVStore store = new MVStore.Builder().open();
        try {
            MVStoreReclamationScheduler scheduler = MVStoreReclamationScheduler.builder()
                    .enabled(true)
                    .failureBackoffMillis(60_000L)
                    .build();

            MVStoreOnlineReclamationResult first = scheduler.runIfEnabled(store);
            MVStoreOnlineReclamationResult second = scheduler.runIfEnabled(store);

            assertEquals(MVStoreReclamationStatus.SKIPPED, first.getStatus());
            assertEquals(MVStoreReclamationCode.NO_RECLAMATION_CANDIDATE, first.getMessage());
            assertEquals(MVStoreReclamationStatus.SKIPPED, second.getStatus());
            assertEquals(MVStoreReclamationCode.RECLAMATION_SCHEDULER_BACKOFF, second.getMessage());
            assertFalse(second.isSuccess());
            assertFalse(second.isRewritten());
            assertDiagnosticSummaryContainsStableFields(second);
        } finally {
            store.close();
        }
    }

    @Test
    void closedStoreSchedulerReturnsStableDiagnostic() {
        MVStore store = new MVStore.Builder().open();
        store.close();

        MVStoreOnlineReclamationResult result = MVStoreReclamationScheduler.builder()
                .enabled(true)
                .build()
                .runIfEnabled(store);

        assertEquals(MVStoreReclamationStatus.SKIPPED, result.getStatus());
        assertEquals(MVStoreReclamationCode.RECLAMATION_STORE_CLOSED, result.getMessage());
        assertFalse(result.isSuccess());
        assertFalse(result.isRewritten());
        assertEquals(0L, result.getBeforeFileSize());
        assertEquals(0L, result.getAfterFileSize());
        assertEquals(0, result.getBeforeFillRate());
        assertEquals(0, result.getAfterFillRate());
        assertEquals(0, result.getBeforeChunksFillRate());
        assertEquals(0, result.getAfterChunksFillRate());
        assertEquals(0L, result.getBeforeEstimatedReclaimableBytes());
        assertEquals(0L, result.getAfterEstimatedReclaimableBytes());
        assertEquals(0L, result.getEstimatedReclaimedBytes());
        assertDiagnosticSummaryContainsStableFields(result);
    }

    private static void assertDiagnosticSummaryContainsStableFields(MVStoreOnlineReclamationResult result) {
        String summary = result.getDiagnosticSummary();
        assertTrue(summary.contains("status=" + result.getStatus()));
        assertTrue(summary.contains("message=" + result.getMessage()));
        assertTrue(summary.contains("beforeFileSize="));
        assertTrue(summary.contains("afterFileSize="));
        assertTrue(summary.contains("beforeFillRate="));
        assertTrue(summary.contains("afterFillRate="));
        assertTrue(summary.contains("beforeChunksFillRate="));
        assertTrue(summary.contains("afterChunksFillRate="));
        assertTrue(summary.contains("beforeEstimatedReclaimableBytes="));
        assertTrue(summary.contains("afterEstimatedReclaimableBytes="));
        assertTrue(summary.contains("estimatedReclaimedBytes="));
        assertTrue(summary.contains("relocationMapAllowed="));
        assertTrue(summary.contains("relocationMapUsed="));
        assertTrue(summary.contains("tailCompactionAllowed="));
        assertTrue(summary.contains("tailCompactionAttempted="));
        assertTrue(summary.contains("rewritten="));
        assertTrue(summary.contains("candidateChunks="));
    }
}
