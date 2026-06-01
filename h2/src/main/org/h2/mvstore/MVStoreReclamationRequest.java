/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * Request options for one MVStore online reclamation round.
 */
public final class MVStoreReclamationRequest {

    /**
     * Default request used by the maintenance entry point.
     */
    public static final MVStoreReclamationRequest DEFAULT = new Builder().build();

    private final boolean dryRun;
    private final int targetFillRate;
    private final int maxCandidateChunks;
    private final int maxLiveBytesToRewrite;
    private final long maxRunMillis;
    private final boolean journalEnabled;
    private final boolean relocationMapAllowed;
    private final boolean tailCompactionAllowed;
    private final int maxTailCompactionMillis;

    private MVStoreReclamationRequest(Builder builder) {
        dryRun = builder.dryRun;
        targetFillRate = builder.targetFillRate;
        maxCandidateChunks = builder.maxCandidateChunks;
        maxLiveBytesToRewrite = builder.maxLiveBytesToRewrite;
        maxRunMillis = builder.maxRunMillis;
        journalEnabled = builder.journalEnabled;
        relocationMapAllowed = builder.relocationMapAllowed;
        tailCompactionAllowed = builder.tailCompactionAllowed;
        maxTailCompactionMillis = builder.maxTailCompactionMillis;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public int getTargetFillRate() {
        return targetFillRate;
    }

    public int getMaxCandidateChunks() {
        return maxCandidateChunks;
    }

    public int getMaxLiveBytesToRewrite() {
        return maxLiveBytesToRewrite;
    }

    public long getMaxRunMillis() {
        return maxRunMillis;
    }

    public boolean isJournalEnabled() {
        return journalEnabled;
    }

    public boolean isRelocationMapAllowed() {
        return relocationMapAllowed;
    }

    public boolean isTailCompactionAllowed() {
        return tailCompactionAllowed;
    }

    public int getMaxTailCompactionMillis() {
        return maxTailCompactionMillis;
    }

    String asJournalString() {
        return "dryRun=" + dryRun +
                ",targetFillRate=" + targetFillRate +
                ",maxCandidateChunks=" + maxCandidateChunks +
                ",maxLiveBytesToRewrite=" + maxLiveBytesToRewrite +
                ",maxRunMillis=" + maxRunMillis +
                ",journalEnabled=" + journalEnabled +
                ",relocationMapAllowed=" + relocationMapAllowed +
                ",tailCompactionAllowed=" + tailCompactionAllowed +
                ",maxTailCompactionMillis=" + maxTailCompactionMillis;
    }

    /**
     * Builder for immutable reclamation requests.
     */
    public static final class Builder {
        private boolean dryRun;
        private int targetFillRate = 50;
        private int maxCandidateChunks = 1;
        private int maxLiveBytesToRewrite = 16 * 1024 * 1024;
        private long maxRunMillis;
        private boolean journalEnabled;
        private boolean relocationMapAllowed;
        private boolean tailCompactionAllowed = true;
        private int maxTailCompactionMillis;

        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public Builder targetFillRate(int targetFillRate) {
            if (targetFillRate < 0 || targetFillRate > 100) {
                throw new IllegalArgumentException("targetFillRate");
            }
            this.targetFillRate = targetFillRate;
            return this;
        }

        public Builder maxCandidateChunks(int maxCandidateChunks) {
            if (maxCandidateChunks < 1) {
                throw new IllegalArgumentException("maxCandidateChunks");
            }
            this.maxCandidateChunks = maxCandidateChunks;
            return this;
        }

        public Builder maxLiveBytesToRewrite(int maxLiveBytesToRewrite) {
            if (maxLiveBytesToRewrite < 0) {
                throw new IllegalArgumentException("maxLiveBytesToRewrite");
            }
            this.maxLiveBytesToRewrite = maxLiveBytesToRewrite;
            return this;
        }

        public Builder maxRunMillis(long maxRunMillis) {
            if (maxRunMillis < 0L) {
                throw new IllegalArgumentException("maxRunMillis");
            }
            this.maxRunMillis = maxRunMillis;
            return this;
        }

        public Builder journalEnabled(boolean journalEnabled) {
            this.journalEnabled = journalEnabled;
            return this;
        }

        public Builder relocationMapAllowed(boolean relocationMapAllowed) {
            this.relocationMapAllowed = relocationMapAllowed;
            return this;
        }

        public Builder tailCompactionAllowed(boolean tailCompactionAllowed) {
            this.tailCompactionAllowed = tailCompactionAllowed;
            return this;
        }

        public Builder maxTailCompactionMillis(int maxTailCompactionMillis) {
            if (maxTailCompactionMillis < 0) {
                throw new IllegalArgumentException("maxTailCompactionMillis");
            }
            this.maxTailCompactionMillis = maxTailCompactionMillis;
            return this;
        }

        public MVStoreReclamationRequest build() {
            return new MVStoreReclamationRequest(this);
        }
    }
}
