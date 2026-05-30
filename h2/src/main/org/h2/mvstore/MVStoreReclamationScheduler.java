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

    private MVStoreReclamationScheduler(Builder builder) {
        enabled = builder.enabled;
        request = builder.request;
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
                    "RECLAMATION_SCHEDULER_DISABLED", analysis, analysis, false,
                    false, false, request.isTailCompactionAllowed(), false, new java.util.ArrayList<Integer>());
        }
        return MVStoreReclamationCoordinator.run(store, request);
    }

    /**
     * Builder for scheduler configuration.
     */
    public static final class Builder {
        private boolean enabled;
        private MVStoreReclamationRequest request = MVStoreReclamationRequest.DEFAULT;

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

        public MVStoreReclamationScheduler build() {
            return new MVStoreReclamationScheduler(this);
        }
    }
}
