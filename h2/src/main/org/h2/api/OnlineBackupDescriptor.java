/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Prepared backup cut 的不可变公开描述。
 */
public final class OnlineBackupDescriptor {

    private final UUID backupId;
    private final UUID cutId;
    private final UUID databaseId;
    private final UUID sourceGenerationId;
    private final long schemaEpoch;
    private final List<String> participantIds;

    /**
     * 创建描述。
     *
     * @param backupId backup ID
     * @param cutId cut ID
     * @param databaseId 逻辑数据库 ID
     * @param sourceGenerationId 源 generation ID
     * @param schemaEpoch schema epoch
     * @param participantIds 稳定排序的 participant ID
     */
    public OnlineBackupDescriptor(UUID backupId, UUID cutId,
            UUID databaseId, UUID sourceGenerationId, long schemaEpoch,
            List<String> participantIds) {
        if (backupId == null || cutId == null || databaseId == null
                || sourceGenerationId == null || participantIds == null) {
            throw new IllegalArgumentException(
                    "Backup descriptor fields must not be null");
        }
        this.backupId = backupId;
        this.cutId = cutId;
        this.databaseId = databaseId;
        this.sourceGenerationId = sourceGenerationId;
        this.schemaEpoch = schemaEpoch;
        this.participantIds = Collections.unmodifiableList(
                new ArrayList<>(participantIds));
    }

    /**
     * @return backup ID
     */
    public UUID getBackupId() {
        return backupId;
    }

    /**
     * @return cut ID
     */
    public UUID getCutId() {
        return cutId;
    }

    /**
     * @return 逻辑数据库 ID
     */
    public UUID getDatabaseId() {
        return databaseId;
    }

    /**
     * @return 源 generation ID
     */
    public UUID getSourceGenerationId() {
        return sourceGenerationId;
    }

    /**
     * @return schema epoch
     */
    public long getSchemaEpoch() {
        return schemaEpoch;
    }

    /**
     * @return 稳定排序的 participant ID
     */
    public List<String> getParticipantIds() {
        return participantIds;
    }
}
