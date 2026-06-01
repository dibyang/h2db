/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.util.function.BooleanSupplier;

/**
 * Explicit scheduler facade for future background online reclamation.
 */
public final class MVStoreReclamationScheduler {

    private final boolean enabled;
    private final MVStoreReclamationRequest request;
    private final long minIntervalMillis;
    private final long failureBackoffMillis;
    private final int spacePressureThreshold;
    private final BooleanSupplier foregroundBusySupplier;
    private long nextAllowedRunMillis;
    private int consecutiveFailureCount;

    private MVStoreReclamationScheduler(Builder builder) {
        enabled = builder.enabled;
        request = builder.request;
        minIntervalMillis = builder.minIntervalMillis;
        failureBackoffMillis = builder.failureBackoffMillis;
        spacePressureThreshold = builder.spacePressureThreshold;
        foregroundBusySupplier = builder.foregroundBusySupplier;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public MVStoreOnlineReclamationResult runIfEnabled(MVStore store) {
        if (store.isClosed()) {
            return closedStoreResult(request);
        }
        if (!enabled) {
            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store);
            return new MVStoreOnlineReclamationResult(MVStoreReclamationStatus.SKIPPED,
                    MVStoreReclamationCode.RECLAMATION_SCHEDULER_DISABLED, analysis, analysis, false,
                    false, false, request.isTailCompactionAllowed(), false, false,
                    new java.util.ArrayList<Integer>());
        }
        if (foregroundBusySupplier.getAsBoolean()) {
            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store);
            return new MVStoreOnlineReclamationResult(MVStoreReclamationStatus.SKIPPED,
                    MVStoreReclamationCode.RECLAMATION_SCHEDULER_FOREGROUND_BUSY, analysis, analysis, false,
                    request.isRelocationMapAllowed(), false, request.isTailCompactionAllowed(), false, false,
                    new java.util.ArrayList<Integer>());
        }
        long now = System.currentTimeMillis();
        if (now < nextAllowedRunMillis) {
            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store);
            if (!isUnderSpacePressure(analysis)) {
                return new MVStoreOnlineReclamationResult(MVStoreReclamationStatus.SKIPPED,
                        MVStoreReclamationCode.RECLAMATION_SCHEDULER_BACKOFF, analysis, analysis, false,
                        request.isRelocationMapAllowed(), false, request.isTailCompactionAllowed(), false, false,
                        new java.util.ArrayList<Integer>());
            }
        }
        MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store, request);
        if (result.isSuccess()) {
            consecutiveFailureCount = 0;
            nextAllowedRunMillis = now + minIntervalMillis;
        } else {
            consecutiveFailureCount++;
            nextAllowedRunMillis = now + adaptiveFailureBackoffMillis();
        }
        return result;
    }

    private boolean isUnderSpacePressure(MVStoreReclamationAnalysis analysis) {
        return spacePressureThreshold > 0 && analysis.hasCandidates()
                && analysis.getChunksFillRate() < spacePressureThreshold;
    }

    private long adaptiveFailureBackoffMillis() {
        long factor = 1L << Math.min(consecutiveFailureCount - 1, 4);
        long backoff = failureBackoffMillis * factor;
        return backoff < 0L ? Long.MAX_VALUE : backoff;
    }

    private static MVStoreOnlineReclamationResult closedStoreResult(MVStoreReclamationRequest request) {
        MVStoreReclamationAnalysis analysis = new MVStoreReclamationAnalysis(0, 0L, 0, 0,
                new java.util.ArrayList<ChunkLivenessSnapshot>(), new java.util.ArrayList<ChunkLivenessSnapshot>());
        return new MVStoreOnlineReclamationResult(MVStoreReclamationStatus.SKIPPED,
                MVStoreReclamationCode.RECLAMATION_STORE_CLOSED, analysis, analysis, false,
                request.isRelocationMapAllowed(), false, request.isTailCompactionAllowed(), false, false,
                new java.util.ArrayList<Integer>());
    }

    /**
     * Builder for scheduler configuration.
     */
    public static final class Builder {
        private boolean enabled;
        private MVStoreReclamationRequest request = MVStoreReclamationRequest.DEFAULT;
        private long minIntervalMillis = 60_000L;
        private long failureBackoffMillis = 300_000L;
        private int spacePressureThreshold;
        private BooleanSupplier foregroundBusySupplier = new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return false;
            }
        };

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder request(MVStoreReclamationRequest request) {
            if (request == null) {
                throw new IllegalArgumentException("request");
            }
            this.request = request;
            return this;
        }

        public Builder minIntervalMillis(long minIntervalMillis) {
            if (minIntervalMillis < 0L) {
                throw new IllegalArgumentException("minIntervalMillis");
            }
            this.minIntervalMillis = minIntervalMillis;
            return this;
        }

        public Builder failureBackoffMillis(long failureBackoffMillis) {
            if (failureBackoffMillis < 0L) {
                throw new IllegalArgumentException("failureBackoffMillis");
            }
            this.failureBackoffMillis = failureBackoffMillis;
            return this;
        }

        public Builder spacePressureThreshold(int spacePressureThreshold) {
            if (spacePressureThreshold < 0 || spacePressureThreshold > 100) {
                throw new IllegalArgumentException("spacePressureThreshold");
            }
            this.spacePressureThreshold = spacePressureThreshold;
            return this;
        }

        public Builder foregroundBusySupplier(BooleanSupplier foregroundBusySupplier) {
            if (foregroundBusySupplier == null) {
                throw new IllegalArgumentException("foregroundBusySupplier");
            }
            this.foregroundBusySupplier = foregroundBusySupplier;
            return this;
        }

        public MVStoreReclamationScheduler build() {
            return new MVStoreReclamationScheduler(this);
        }
    }
}
