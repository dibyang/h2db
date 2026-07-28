/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.UUID;

/**
 * participant shadow validation 的只读身份上下文。
 * <p>
 * 该对象故意不携带活动路径、生产 endpoint、scheduler、服务注册入口或可写
 * 数据库对象，validation callback 不得启动普通业务 lifecycle。
 */
public final class OnlineBackupValidationContext {

    private final UUID backupId;
    private final UUID cutId;
    private final UUID databaseId;
    private final UUID sourceGenerationId;
    private final UUID shadowGenerationId;
    private final long schemaEpoch;

    /**
     * 创建受限 validation 上下文。
     *
     * @param backupId 备份 ID
     * @param cutId 一致性切点 ID
     * @param databaseId 数据库 ID
     * @param sourceGenerationId 源 generation ID
     * @param shadowGenerationId shadow generation ID
     * @param schemaEpoch schema epoch
     */
    public OnlineBackupValidationContext(UUID backupId, UUID cutId,
            UUID databaseId, UUID sourceGenerationId,
            UUID shadowGenerationId, long schemaEpoch) {
        if (backupId == null || cutId == null || databaseId == null
                || sourceGenerationId == null
                || shadowGenerationId == null) {
            throw new IllegalArgumentException(
                    "Validation identities must not be null");
        }
        if (schemaEpoch < 0L) {
            throw new IllegalArgumentException(
                    "schemaEpoch must not be negative");
        }
        this.backupId = backupId;
        this.cutId = cutId;
        this.databaseId = databaseId;
        this.sourceGenerationId = sourceGenerationId;
        this.shadowGenerationId = shadowGenerationId;
        this.schemaEpoch = schemaEpoch;
    }

    /**
     * @return 备份 ID
     */
    public UUID getBackupId() {
        return backupId;
    }

    /**
     * @return 一致性切点 ID
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
     * @return shadow generation ID
     */
    public UUID getShadowGenerationId() {
        return shadowGenerationId;
    }

    /**
     * @return 备份切点的 schema epoch
     */
    public long getSchemaEpoch() {
        return schemaEpoch;
    }
}
