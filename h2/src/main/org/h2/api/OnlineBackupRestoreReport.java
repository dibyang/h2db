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
    private final UUID cutId;
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
     * @deprecated 使用包含 {@code cutId} 的构造器
     */
    @Deprecated
    public OnlineBackupRestoreReport(String shadowName, UUID backupId,
            UUID databaseId, UUID sourceGenerationId,
            UUID shadowGenerationId, long schemaEpoch,
            long validationMillis) {
        requireBaseFields(shadowName, backupId, databaseId,
                sourceGenerationId, shadowGenerationId);
        this.shadowName = shadowName;
        this.backupId = backupId;
        this.cutId = null;
        this.databaseId = databaseId;
        this.sourceGenerationId = sourceGenerationId;
        this.shadowGenerationId = shadowGenerationId;
        this.schemaEpoch = schemaEpoch;
        this.validationMillis = validationMillis;
    }

    /**
     * 创建包含已验证切点身份的 restore 报告。
     *
     * @param shadowName 调用方提交的 shadow 名称
     * @param backupId backup ID
     * @param cutId 已验证的 backup cut ID
     * @param databaseId 逻辑数据库 ID
     * @param sourceGenerationId 源 generation ID
     * @param shadowGenerationId 新 shadow generation ID
     * @param schemaEpoch schema epoch
     * @param validationMillis validation 耗时毫秒数
     */
    public OnlineBackupRestoreReport(String shadowName, UUID backupId,
            UUID cutId, UUID databaseId, UUID sourceGenerationId,
            UUID shadowGenerationId, long schemaEpoch,
            long validationMillis) {
        requireBaseFields(shadowName, backupId, databaseId,
                sourceGenerationId, shadowGenerationId);
        if (cutId == null) {
            throw new IllegalArgumentException(
                    "Restore report cutId must not be null");
        }
        this.shadowName = shadowName;
        this.backupId = backupId;
        this.cutId = cutId;
        this.databaseId = databaseId;
        this.sourceGenerationId = sourceGenerationId;
        this.shadowGenerationId = shadowGenerationId;
        this.schemaEpoch = schemaEpoch;
        this.validationMillis = validationMillis;
    }

    private static void requireBaseFields(String shadowName, UUID backupId,
            UUID databaseId, UUID sourceGenerationId,
            UUID shadowGenerationId) {
        if (shadowName == null || backupId == null || databaseId == null
                || sourceGenerationId == null
                || shadowGenerationId == null) {
            throw new IllegalArgumentException(
                    "Restore report fields must not be null");
        }
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
     * 返回已验证的 backup cut ID。
     *
     * <p>embedded 和 TCP v22+ restore 报告始终非空。通过旧构造器创建或从
     * TCP v21 peer 读取的兼容报告为 {@code null}。</p>
     *
     * @return backup cut ID，旧兼容报告可为 {@code null}
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
