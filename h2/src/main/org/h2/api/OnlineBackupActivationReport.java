/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.UUID;

/**
 * Activation token 的不可变公开报告。
 */
public final class OnlineBackupActivationReport {

    private final UUID activationId;
    private final UUID databaseId;
    private final UUID oldGenerationId;
    private final UUID newGenerationId;
    private final long schemaEpoch;
    private final String status;
    private final String reason;
    private final long drainMillis;
    private final String updatedAt;

    /**
     * 创建 activation 报告。
     *
     * @param activationId activation ID
     * @param databaseId 逻辑数据库 ID
     * @param oldGenerationId 旧 generation ID
     * @param newGenerationId 新 generation ID
     * @param schemaEpoch schema epoch
     * @param status 稳定状态
     * @param reason 稳定 reason
     * @param drainMillis drain 耗时毫秒数
     * @param updatedAt UTC 更新时间
     */
    public OnlineBackupActivationReport(UUID activationId,
            UUID databaseId, UUID oldGenerationId,
            UUID newGenerationId, long schemaEpoch, String status,
            String reason, long drainMillis, String updatedAt) {
        if (activationId == null || databaseId == null
                || oldGenerationId == null || newGenerationId == null
                || status == null || reason == null || updatedAt == null) {
            throw new IllegalArgumentException(
                    "Activation report fields must not be null");
        }
        this.activationId = activationId;
        this.databaseId = databaseId;
        this.oldGenerationId = oldGenerationId;
        this.newGenerationId = newGenerationId;
        this.schemaEpoch = schemaEpoch;
        this.status = status;
        this.reason = reason;
        this.drainMillis = drainMillis;
        this.updatedAt = updatedAt;
    }

    /**
     * @return activation ID
     */
    public UUID getActivationId() {
        return activationId;
    }

    /**
     * @return 逻辑数据库 ID
     */
    public UUID getDatabaseId() {
        return databaseId;
    }

    /**
     * @return 旧 generation ID
     */
    public UUID getOldGenerationId() {
        return oldGenerationId;
    }

    /**
     * @return 新 generation ID
     */
    public UUID getNewGenerationId() {
        return newGenerationId;
    }

    /**
     * @return schema epoch
     */
    public long getSchemaEpoch() {
        return schemaEpoch;
    }

    /**
     * @return 稳定状态
     */
    public String getStatus() {
        return status;
    }

    /**
     * @return 稳定 reason
     */
    public String getReason() {
        return reason;
    }

    /**
     * @return drain 耗时毫秒数
     */
    public long getDrainMillis() {
        return drainMillis;
    }

    /**
     * @return UTC 更新时间
     */
    public String getUpdatedAt() {
        return updatedAt;
    }
}
