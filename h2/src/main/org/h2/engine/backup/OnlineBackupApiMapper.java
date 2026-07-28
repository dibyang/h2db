/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.util.ArrayList;

import org.h2.api.OnlineBackupActivationReport;
import org.h2.api.OnlineBackupDescriptor;
import org.h2.api.OnlineBackupPublishReport;
import org.h2.api.OnlineBackupRestoreOptions;
import org.h2.api.OnlineBackupRestoreReport;
import org.h2.engine.restore.ShadowRestoreOptions;
import org.h2.engine.restore.ShadowRestoreResult;

/**
 * 公开在线备份 DTO 与 core 类型之间的无状态映射。
 */
public final class OnlineBackupApiMapper {

    private OnlineBackupApiMapper() {
    }

    /**
     * @param session prepared backup session
     * @return 公开 cut 描述
     */
    public static OnlineBackupDescriptor descriptor(
            OnlineBackupSession session) {
        ArrayList<String> participants = new ArrayList<>();
        for (OnlineBackupSession.ParticipantSnapshot participant
                : session.getParticipants()) {
            participants.add(participant.getParticipantId());
        }
        return new OnlineBackupDescriptor(
                session.getContext().getBackupId(),
                session.getContext().getCutId(),
                session.getContext().getDatabaseId(),
                session.getContext().getGenerationId(),
                session.getContext().getSchemaEpoch(), participants);
    }

    /**
     * @param bundleName 调用方提交的 bundle 名称
     * @param result core 发布结果
     * @return 公开发布报告
     */
    public static OnlineBackupPublishReport publishReport(
            String bundleName, OnlineBackupPublishResult result) {
        return new OnlineBackupPublishReport(bundleName,
                result.getManifest().getBackupId(),
                result.getManifest().getCutId(), result.isReused(),
                result.getStagingDirectoryFsync().name(),
                result.getParentDirectoryFsync().name());
    }

    /**
     * @param options 公开 restore 选项
     * @return core restore 选项
     */
    public static ShadowRestoreOptions restoreOptions(
            OnlineBackupRestoreOptions options) {
        ArrayList<ShadowRestoreOptions.ProviderSelection> providers =
                new ArrayList<>();
        for (OnlineBackupRestoreOptions.ProviderSelection provider
                : options.getAdditionalRequiredProviders()) {
            providers.add(new ShadowRestoreOptions.ProviderSelection(
                    provider.getType(), provider.getId()));
        }
        return new ShadowRestoreOptions(options.getShadowGenerationId(),
                options.getParticipantAllowlist(), providers,
                options.getValidationTimeoutMillis(),
                options.isKeepFailedShadow(), options.getUser(),
                options.getPassword(), options.getCipher());
    }

    /**
     * @param shadowName 调用方提交的 shadow 名称
     * @param result core restore 结果
     * @return 公开 restore 报告
     */
    public static OnlineBackupRestoreReport restoreReport(
            String shadowName, ShadowRestoreResult result) {
        OnlineBackupManifest manifest = result.getManifest();
        return new OnlineBackupRestoreReport(shadowName,
                manifest.getBackupId(), manifest.getDatabaseId(),
                manifest.getSourceGenerationId(),
                result.getShadowGenerationId(), manifest.getSchemaEpoch(),
                result.getValidationMillis());
    }

    /**
     * @param report core activation 报告
     * @return 公开 activation 报告
     */
    public static OnlineBackupActivationReport activationReport(
            ActivationReport report) {
        return new OnlineBackupActivationReport(report.getActivationId(),
                report.getDatabaseId(), report.getOldGenerationId(),
                report.getNewGenerationId(), report.getSchemaEpoch(),
                report.getStatus().name(), report.getReason().name(),
                report.getDrainMillis(), report.getUpdatedAt());
    }
}
