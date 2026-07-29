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
     * 在数据库 barrier 外完成不冻结切点的耗时准备。
     * <p>
     * 只有显式声明
     * {@link PluginCapability#ONLINE_BACKUP_PHASED_PREPARE}的 provider 才会
     * 调用此方法。默认返回 {@code null}，旧 provider 继续使用
     * {@link #prepare(OnlineBackupContext)}。
     *
     * @param context 不可变备份上下文
     * @return armed participant
     * @throws Exception arm 失败
     */
    default ArmedBackupParticipant arm(OnlineBackupContext context)
            throws Exception {
        return null;
    }

    /**
     * 在协调切点准备旧式 participant snapshot。
     * <p>
     * 未声明分阶段准备能力的旧 provider 继续覆盖此方法。默认实现
     * fail-closed，使仅实现 {@link #arm(OnlineBackupContext)} 的新 provider
     * 可以保持源码和二进制兼容。
     *
     * @param context 不可变备份上下文
     * @return 已准备的 participant
     * @throws Exception prepare 失败
     */
    default PreparedBackupParticipant prepare(OnlineBackupContext context)
            throws Exception {
        throw new UnsupportedOperationException(
                "Participant does not implement legacy prepare");
    }

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
