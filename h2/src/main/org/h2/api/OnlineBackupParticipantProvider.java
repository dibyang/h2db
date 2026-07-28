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

    /**
     * 以无业务副作用的 validation mode 校验已恢复 artifact。
     * <p>
     * provider 必须同时声明
     * {@link PluginCapability#ONLINE_BACKUP_VALIDATE} 才会被调用。默认实现
     * fail-closed，旧 provider 不会被当作已认证 validation provider。
     *
     * @param context 受限只读 validation 上下文
     * @param metadata prepare 阶段冻结的 participant 元数据
     * @param artifacts 只读 artifact 视图
     * @throws Exception 校验失败
     */
    default void validateRestore(OnlineBackupValidationContext context,
            PreparedParticipantMetadata metadata,
            ParticipantArtifactSource artifacts) throws Exception {
        throw new UnsupportedOperationException(
                "Participant does not implement restore validation");
    }
}
