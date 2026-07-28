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
 * Materialized artifact paths returned by one participant.
 */
public final class MaterializedParticipantArtifact {

    private final String participantId;
    private final List<String> relativePaths;

    /**
     * Create materialized artifact metadata.
     *
     * @param participantId participant ID
     * @param relativePaths materialized relative paths
     */
    public MaterializedParticipantArtifact(String participantId,
            List<String> relativePaths) {
        if (participantId == null || participantId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "participantId must not be empty");
        }
        if (relativePaths == null) {
            throw new IllegalArgumentException(
                    "relativePaths must not be null");
        }
        this.participantId = participantId;
        this.relativePaths = Collections.unmodifiableList(
                new ArrayList<>(relativePaths));
    }

    /**
     * @return participant ID
     */
    public String getParticipantId() {
        return participantId;
    }

    /**
     * @return materialized relative paths
     */
    public List<String> getRelativePaths() {
        return relativePaths;
    }
}
