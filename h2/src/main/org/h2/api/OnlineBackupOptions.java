/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Options for preparing a coordinated online backup session.
 */
public final class OnlineBackupOptions {

    private final List<String> participantIds;
    private final long prepareTimeoutMillis;
    private final long snapshotLeaseMillis;

    /**
     * Create options.
     *
     * @param participantIds explicitly selected participant IDs
     * @param prepareTimeoutMillis total prepare timeout
     * @param snapshotLeaseMillis prepared snapshot lease
     */
    public OnlineBackupOptions(List<String> participantIds,
            long prepareTimeoutMillis, long snapshotLeaseMillis) {
        if (participantIds == null) {
            throw new IllegalArgumentException(
                    "participantIds must not be null");
        }
        if (prepareTimeoutMillis < 0L || snapshotLeaseMillis < 0L) {
            throw new IllegalArgumentException(
                    "backup timeouts must not be negative");
        }
        this.participantIds = Collections.unmodifiableList(
                new ArrayList<>(participantIds));
        this.prepareTimeoutMillis = prepareTimeoutMillis;
        this.snapshotLeaseMillis = snapshotLeaseMillis;
    }

    /**
     * @return explicitly selected participant IDs
     */
    public List<String> getParticipantIds() {
        return participantIds;
    }

    /**
     * @return total prepare timeout in milliseconds
     */
    public long getPrepareTimeoutMillis() {
        return prepareTimeoutMillis;
    }

    /**
     * @return snapshot lease in milliseconds
     */
    public long getSnapshotLeaseMillis() {
        return snapshotLeaseMillis;
    }
}
