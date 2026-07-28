/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.h2.api.OnlineBackupActivationReport;
import org.h2.api.OnlineBackupDescriptor;
import org.h2.api.OnlineBackupOptions;
import org.h2.api.OnlineBackupPublishReport;
import org.h2.api.OnlineBackupRestoreOptions;
import org.h2.api.OnlineBackupRestoreReport;
import org.h2.value.Transfer;

/**
 * TCP v21 在线备份管理协议的 DTO 编解码。
 */
public final class OnlineBackupProtocolCodec {

    private OnlineBackupProtocolCodec() {
    }

    /**
     * @param transfer 传输通道
     * @param options prepare 选项
     * @throws IOException 传输失败
     */
    public static void writeOptions(Transfer transfer,
            OnlineBackupOptions options) throws IOException {
        writeUuid(transfer, options.getBackupId());
        writeStrings(transfer, options.getParticipantIds());
        transfer.writeLong(options.getPrepareTimeoutMillis())
                .writeLong(options.getSnapshotLeaseMillis());
    }

    /**
     * @param transfer 传输通道
     * @return prepare 选项
     * @throws IOException 传输失败
     */
    public static OnlineBackupOptions readOptions(Transfer transfer)
            throws IOException {
        return new OnlineBackupOptions(readUuid(transfer),
                readStrings(transfer), transfer.readLong(),
                transfer.readLong());
    }

    /**
     * @param transfer 传输通道
     * @param descriptor backup cut 描述
     * @throws IOException 传输失败
     */
    public static void writeDescriptor(Transfer transfer,
            OnlineBackupDescriptor descriptor) throws IOException {
        writeUuid(transfer, descriptor.getBackupId());
        writeUuid(transfer, descriptor.getCutId());
        writeUuid(transfer, descriptor.getDatabaseId());
        writeUuid(transfer, descriptor.getSourceGenerationId());
        transfer.writeLong(descriptor.getSchemaEpoch());
        writeStrings(transfer, descriptor.getParticipantIds());
    }

    /**
     * @param transfer 传输通道
     * @return backup cut 描述
     * @throws IOException 传输失败
     */
    public static OnlineBackupDescriptor readDescriptor(Transfer transfer)
            throws IOException {
        return new OnlineBackupDescriptor(requireUuid(transfer),
                requireUuid(transfer), requireUuid(transfer),
                requireUuid(transfer), transfer.readLong(),
                readStrings(transfer));
    }

    /**
     * @param transfer 传输通道
     * @param report 发布报告
     * @throws IOException 传输失败
     */
    public static void writePublishReport(Transfer transfer,
            OnlineBackupPublishReport report) throws IOException {
        transfer.writeString(report.getBundleName());
        writeUuid(transfer, report.getBackupId());
        writeUuid(transfer, report.getCutId());
        transfer.writeBoolean(report.isReused())
                .writeString(report.getStagingDirectoryFsync())
                .writeString(report.getParentDirectoryFsync());
    }

    /**
     * @param transfer 传输通道
     * @return 发布报告
     * @throws IOException 传输失败
     */
    public static OnlineBackupPublishReport readPublishReport(
            Transfer transfer) throws IOException {
        return new OnlineBackupPublishReport(transfer.readString(),
                requireUuid(transfer), requireUuid(transfer),
                transfer.readBoolean(), transfer.readString(),
                transfer.readString());
    }

    /**
     * @param transfer 传输通道
     * @param options restore 选项
     * @throws IOException 传输失败
     */
    public static void writeRestoreOptions(Transfer transfer,
            OnlineBackupRestoreOptions options) throws IOException {
        writeUuid(transfer, options.getShadowGenerationId());
        writeStrings(transfer, options.getParticipantAllowlist());
        List<OnlineBackupRestoreOptions.ProviderSelection> providers =
                options.getAdditionalRequiredProviders();
        transfer.writeInt(providers.size());
        for (OnlineBackupRestoreOptions.ProviderSelection provider
                : providers) {
            transfer.writeString(provider.getType())
                    .writeString(provider.getId());
        }
        transfer.writeLong(options.getValidationTimeoutMillis())
                .writeBoolean(options.isKeepFailedShadow())
                .writeString(options.getUser())
                .writeString(new String(options.getPassword()))
                .writeString(options.getCipher());
    }

    /**
     * @param transfer 传输通道
     * @return restore 选项
     * @throws IOException 传输失败
     */
    public static OnlineBackupRestoreOptions readRestoreOptions(
            Transfer transfer) throws IOException {
        UUID generationId = requireUuid(transfer);
        List<String> participants = readStrings(transfer);
        int size = readSize(transfer);
        ArrayList<OnlineBackupRestoreOptions.ProviderSelection> providers =
                new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            providers.add(new OnlineBackupRestoreOptions.ProviderSelection(
                    transfer.readString(), transfer.readString()));
        }
        long timeoutMillis = transfer.readLong();
        boolean keepFailed = transfer.readBoolean();
        String user = transfer.readString();
        String password = transfer.readString();
        String cipher = transfer.readString();
        return new OnlineBackupRestoreOptions(generationId, participants,
                providers, timeoutMillis, keepFailed, user,
                password.toCharArray(), cipher);
    }

    /**
     * @param transfer 传输通道
     * @param report restore 报告
     * @throws IOException 传输失败
     */
    public static void writeRestoreReport(Transfer transfer,
            OnlineBackupRestoreReport report) throws IOException {
        transfer.writeString(report.getShadowName());
        writeUuid(transfer, report.getBackupId());
        writeUuid(transfer, report.getDatabaseId());
        writeUuid(transfer, report.getSourceGenerationId());
        writeUuid(transfer, report.getShadowGenerationId());
        transfer.writeLong(report.getSchemaEpoch())
                .writeLong(report.getValidationMillis());
    }

    /**
     * @param transfer 传输通道
     * @return restore 报告
     * @throws IOException 传输失败
     */
    public static OnlineBackupRestoreReport readRestoreReport(
            Transfer transfer) throws IOException {
        return new OnlineBackupRestoreReport(transfer.readString(),
                requireUuid(transfer), requireUuid(transfer),
                requireUuid(transfer), requireUuid(transfer),
                transfer.readLong(), transfer.readLong());
    }

    /**
     * @param transfer 传输通道
     * @param report activation 报告
     * @throws IOException 传输失败
     */
    public static void writeActivationReport(Transfer transfer,
            OnlineBackupActivationReport report) throws IOException {
        writeUuid(transfer, report.getActivationId());
        writeUuid(transfer, report.getDatabaseId());
        writeUuid(transfer, report.getOldGenerationId());
        writeUuid(transfer, report.getNewGenerationId());
        transfer.writeLong(report.getSchemaEpoch())
                .writeString(report.getStatus())
                .writeString(report.getReason())
                .writeLong(report.getDrainMillis())
                .writeString(report.getUpdatedAt());
    }

    /**
     * @param transfer 传输通道
     * @return activation 报告
     * @throws IOException 传输失败
     */
    public static OnlineBackupActivationReport readActivationReport(
            Transfer transfer) throws IOException {
        return new OnlineBackupActivationReport(requireUuid(transfer),
                requireUuid(transfer), requireUuid(transfer),
                requireUuid(transfer), transfer.readLong(),
                transfer.readString(), transfer.readString(),
                transfer.readLong(), transfer.readString());
    }

    /**
     * @param transfer 传输通道
     * @param value UUID，可为 {@code null}
     * @throws IOException 传输失败
     */
    public static void writeUuid(Transfer transfer, UUID value)
            throws IOException {
        transfer.writeString(value == null ? null : value.toString());
    }

    /**
     * @param transfer 传输通道
     * @return UUID，可为 {@code null}
     * @throws IOException 传输失败
     */
    public static UUID readUuid(Transfer transfer) throws IOException {
        String value = transfer.readString();
        return value == null ? null : UUID.fromString(value);
    }

    private static UUID requireUuid(Transfer transfer) throws IOException {
        UUID value = readUuid(transfer);
        if (value == null) {
            throw new IOException("Required UUID is null");
        }
        return value;
    }

    private static void writeStrings(Transfer transfer, List<String> values)
            throws IOException {
        transfer.writeInt(values.size());
        for (String value : values) {
            transfer.writeString(value);
        }
    }

    private static List<String> readStrings(Transfer transfer)
            throws IOException {
        int size = readSize(transfer);
        ArrayList<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(transfer.readString());
        }
        return values;
    }

    private static int readSize(Transfer transfer) throws IOException {
        int size = transfer.readInt();
        if (size < 0 || size > 10_000) {
            throw new IOException("Invalid collection size: " + size);
        }
        return size;
    }
}
