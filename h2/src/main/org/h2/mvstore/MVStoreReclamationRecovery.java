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

    MVStoreReclamationRecovery(boolean recovered, String message) {
        this.recovered = recovered;
        this.message = message;
    }

    public boolean isRecovered() {
        return recovered;
    }

    public String getMessage() {
        return message;
    }
}
