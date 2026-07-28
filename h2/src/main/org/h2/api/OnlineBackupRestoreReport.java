/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.UUID;

/**
 * Shadow restore/validation 结果，不暴露服务端绝对路径。
 */
public final class OnlineBackupRestoreReport {

    private final String shadowName;
    private final UUID backupId;
    private final UUID databaseId;
    private final UUID sourceGenerationId;
    private final UUID shadowGenerationId;
    private final long schemaEpoch;
    private final long validationMillis;

    /**
     * 创建 restore 报告。
     *
     * @param shadowName 调用方提交的 shadow 名称
     * @param backupId backup ID
     * @param databaseId 逻辑数据库 ID
     * @param sourceGenerationId 源 generation ID
     * @param shadowGenerationId 新 shadow generation ID
     * @param schemaEpoch schema epoch
     * @param validationMillis validation 耗时毫秒数
     */
    public OnlineBackupRestoreReport(String shadowName, UUID backupId,
            UUID databaseId, UUID sourceGenerationId,
            UUID shadowGenerationId, long schemaEpoch,
            long validationMillis) {
        if (shadowName == null || backupId == null || databaseId == null
                || sourceGenerationId == null
                || shadowGenerationId == null) {
            throw new IllegalArgumentException(
                    "Restore report fields must not be null");
        }
        this.shadowName = shadowName;
        this.backupId = backupId;
        this.databaseId = databaseId;
        this.sourceGenerationId = sourceGenerationId;
        this.shadowGenerationId = shadowGenerationId;
        this.schemaEpoch = schemaEpoch;
        this.validationMillis = validationMillis;
    }

    /**
     * @return 调用方提交的 shadow 名称
     */
    public String getShadowName() {
        return shadowName;
    }

    /**
     * @return backup ID
     */
    public UUID getBackupId() {
        return backupId;
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
     * @return 新 shadow generation ID
     */
    public UUID getShadowGenerationId() {
        return shadowGenerationId;
    }

    /**
     * @return schema epoch
     */
    public long getSchemaEpoch() {
        return schemaEpoch;
    }

    /**
     * @return validation 耗时毫秒数
     */
    public long getValidationMillis() {
        return validationMillis;
    }
}
