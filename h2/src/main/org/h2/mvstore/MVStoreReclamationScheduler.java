/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * Explicit scheduler facade for future background online reclamation.
 */
public final class MVStoreReclamationScheduler {

    private final boolean enabled;
    private final MVStoreReclamationRequest request;
    private final long minIntervalMillis;
    private final long failureBackoffMillis;
    private long nextAllowedRunMillis;

    private MVStoreReclamationScheduler(Builder builder) {
        enabled = builder.enabled;
        request = builder.request;
        minIntervalMillis = builder.minIntervalMillis;
        failureBackoffMillis = builder.failureBackoffMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public MVStoreOnlineReclamationResult runIfEnabled(MVStore store) {
        if (!enabled) {
            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store);
            return new MVStoreOnlineReclamationResult(MVStoreReclamationStatus.SKIPPED,
                    MVStoreReclamationCode.RECLAMATION_SCHEDULER_DISABLED, analysis, analysis, false,
                    false, false, request.isTailCompactionAllowed(), false, new java.util.ArrayList<Integer>());
        }
        long now = System.currentTimeMillis();
        if (now < nextAllowedRunMillis) {
            MVStoreReclamationAnalysis analysis = MVStoreReclamationAnalyzer.analyze(store);
            return new MVStoreOnlineReclamationResult(MVStoreReclamationStatus.SKIPPED,
                    MVStoreReclamationCode.RECLAMATION_SCHEDULER_BACKOFF, analysis, analysis, false,
                    request.isRelocationMapAllowed(), false, request.isTailCompactionAllowed(), false,
                    new java.util.ArrayList<Integer>());
        }
        MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(store, request);
        nextAllowedRunMillis = now + (result.isSuccess() ? minIntervalMillis : failureBackoffMillis);
        return result;
    }

    /**
     * Builder for scheduler configuration.
     */
    public static final class Builder {
        private boolean enabled;
        private MVStoreReclamationRequest request = MVStoreReclamationRequest.DEFAULT;
        private long minIntervalMillis = 60_000L;
        private long failureBackoffMillis = 300_000L;

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

        public MVStoreReclamationScheduler build() {
            return new MVStoreReclamationScheduler(this);
        }
    }
}
