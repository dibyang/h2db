/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

/**
 * Result of online reclamation journal recovery.
 */
public final class MVStoreReclamationRecovery {

    private final boolean recovered;
    private final String message;
    private final String jobId;
    private final String phase;
    private final boolean published;
    private final String action;

    MVStoreReclamationRecovery(boolean recovered, String message) {
        this(recovered, message, null, null, false, "NONE");
    }

    MVStoreReclamationRecovery(boolean recovered, String message, String jobId, String phase, boolean published,
            String action) {
        this.recovered = recovered;
        this.message = message;
        this.jobId = jobId;
        this.phase = phase;
        this.published = published;
        this.action = action;
    }

    public boolean isRecovered() {
        return recovered;
    }

    public String getMessage() {
        return message;
    }

    public String getJobId() {
        return jobId;
    }

    public String getPhase() {
        return phase;
    }

    public boolean isPublished() {
        return published;
    }

    public String getAction() {
        return action;
    }
}
