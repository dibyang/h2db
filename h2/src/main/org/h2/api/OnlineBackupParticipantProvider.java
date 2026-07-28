/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * Provider for an external participant in a coordinated online backup.
 */
public interface OnlineBackupParticipantProvider extends PluginProvider {

    /**
     * Provider type.
     */
    String TYPE = "online_backup_participant";

    /**
     * Prepare a participant snapshot at the coordinated cut.
     *
     * @param context immutable backup context
     * @return prepared participant
     * @throws Exception if prepare fails
     */
    PreparedBackupParticipant prepare(OnlineBackupContext context)
            throws Exception;
}
