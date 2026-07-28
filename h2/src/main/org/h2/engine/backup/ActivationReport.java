/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.time.Instant;
import java.util.UUID;

/**
 * Activation prepare 或 token 消费后的不可变结构化报告。
 */
public final class ActivationReport {

    private final UUID activationId;
    private final UUID databaseId;
    private final UUID oldGenerationId;
    private final UUID newGenerationId;
    private final long schemaEpoch;
    private final ActivationStatus status;
    private final ActivationReason reason;
    private final long drainMillis;
    private final String updatedAt;

    ActivationReport(UUID activationId, UUID databaseId,
            UUID oldGenerationId, UUID newGenerationId, long schemaEpoch,
            ActivationStatus status, ActivationReason reason,
            long drainMillis) {
        this.activationId = activationId;
        this.databaseId = databaseId;
        this.oldGenerationId = oldGenerationId;
        this.newGenerationId = newGenerationId;
        this.schemaEpoch = schemaEpoch;
        this.status = status;
        this.reason = reason;
        this.drainMillis = drainMillis;
        this.updatedAt = Instant.now().toString();
    }

    ActivationReport transition(ActivationStatus nextStatus,
            ActivationReason nextReason) {
        return new ActivationReport(activationId, databaseId,
                oldGenerationId, newGenerationId, schemaEpoch, nextStatus,
                nextReason, drainMillis);
    }

    /**
     * @return activation 操作 ID
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
     * @return 被排空和 fence 的旧 generation ID
     */
    public UUID getOldGenerationId() {
        return oldGenerationId;
    }

    /**
     * @return 已完成 validation 的新 generation ID
     */
    public UUID getNewGenerationId() {
        return newGenerationId;
    }

    /**
     * @return drain 完成后的 schema epoch
     */
    public long getSchemaEpoch() {
        return schemaEpoch;
    }

    /**
     * @return token 状态
     */
    public ActivationStatus getStatus() {
        return status;
    }

    /**
     * @return 稳定、非本地化 reason
     */
    public ActivationReason getReason() {
        return reason;
    }

    /**
     * @return transaction drain 耗时毫秒数
     */
    public long getDrainMillis() {
        return drainMillis;
    }

    /**
     * @return 本次状态更新时间的 UTC 文本
     */
    public String getUpdatedAt() {
        return updatedAt;
    }
}
