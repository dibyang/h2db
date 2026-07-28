/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable metadata returned after a participant snapshot is prepared.
 */
public final class PreparedParticipantMetadata {

    private final String participantId;
    private final String snapshotId;
    private final Map<String, String> attributes;

    /**
     * Create prepared metadata.
     *
     * @param participantId participant ID
     * @param snapshotId participant snapshot ID
     * @param attributes stable participant attributes
     */
    public PreparedParticipantMetadata(String participantId, String snapshotId,
            Map<String, String> attributes) {
        this.participantId = requireNonBlank(participantId, "participantId");
        this.snapshotId = requireNonBlank(snapshotId, "snapshotId");
        if (attributes == null) {
            throw new IllegalArgumentException("attributes must not be null");
        }
        TreeMap<String, String> sortedAttributes = new TreeMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = requireNonBlank(entry.getKey(), "attribute key");
            String value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(
                        "attribute value must not be null: " + key);
            }
            sortedAttributes.put(key, value);
        }
        this.attributes = Collections.unmodifiableMap(sortedAttributes);
    }

    /**
     * @return participant ID
     */
    public String getParticipantId() {
        return participantId;
    }

    /**
     * @return participant snapshot ID
     */
    public String getSnapshotId() {
        return snapshotId;
    }

    /**
     * @return stable sorted attributes
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
