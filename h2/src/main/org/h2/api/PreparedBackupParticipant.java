/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * A prepared external backup participant.
 */
public interface PreparedBackupParticipant extends AutoCloseable {

    /**
     * @return prepared metadata
     */
    PreparedParticipantMetadata getPreparedMetadata();

    /**
     * Materialize this participant.
     *
     * @param target restricted artifact target
     * @return materialized artifact metadata
     * @throws Exception if materialization fails
     */
    MaterializedParticipantArtifact materialize(
            ParticipantArtifactTarget target) throws Exception;

    /**
     * Abort this participant.
     *
     * @throws Exception if cleanup fails
     */
    void abort() throws Exception;

    @Override
    default void close() throws Exception {
        abort();
    }
}
