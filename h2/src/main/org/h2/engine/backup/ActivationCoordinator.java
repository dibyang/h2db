/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.h2.api.ErrorCode;
import org.h2.engine.Database;
import org.h2.engine.backup.DatabaseIdentityMetadata.Snapshot;
import org.h2.engine.backup.DatabaseOperationGate.TransactionDrain;
import org.h2.message.DbException;

/**
 * 对当前活动 generation 执行有界 drain，并签发进程内 activation token。
 */
public final class ActivationCoordinator {

    private ActivationCoordinator() {
    }

    /**
     * 排空旧 generation 的事务并签发 token。
     *
     * @param database 当前活动数据库
     * @param expectedOldGenerationId 调用方认为的活动 generation ID
     * @param newGenerationId 已完成 validation 的新 generation ID
     * @param timeoutMillis drain 总超时毫秒数
     * @return 只能 commit 或 abort 一次的进程内 token
     */
    public static ActivationToken prepare(Database database,
            UUID expectedOldGenerationId, UUID newGenerationId,
            long timeoutMillis) {
        if (database == null || expectedOldGenerationId == null
                || newGenerationId == null) {
            throw new IllegalArgumentException(
                    "Activation arguments must not be null");
        }
        DatabaseIdentityMetadata metadata =
                database.getOnlineBackupMetadata();
        DatabaseOperationGate gate = database.getOperationGate();
        if (metadata == null || gate == null) {
            throw DbException.get(ErrorCode.UNSUPPORTED_SETTING_COMBINATION,
                    "ONLINE_BACKUP_COORDINATION is disabled");
        }
        if (!database.isPersistent()) {
            throw DbException.get(ErrorCode.DATABASE_IS_NOT_PERSISTENT);
        }
        metadata.validateGenerationId(expectedOldGenerationId);
        if (expectedOldGenerationId.equals(newGenerationId)) {
            throw DbException.getInvalidValueException("newGenerationId",
                    newGenerationId);
        }

        long startedNanos = System.nanoTime();
        TransactionDrain drain = gate.beginTransactionDrain(timeoutMillis,
                expectedOldGenerationId.toString());
        boolean complete = false;
        try {
            Snapshot identity = metadata.requireSnapshot();
            metadata.validateGenerationId(expectedOldGenerationId);
            ActivationReport report = new ActivationReport(UUID.randomUUID(),
                    identity.getDatabaseId(), expectedOldGenerationId,
                    newGenerationId, identity.getSchemaEpoch(),
                    ActivationStatus.PREPARED, ActivationReason.PREPARED,
                    TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedNanos));
            ActivationToken token = new ActivationToken(drain, report);
            complete = true;
            return token;
        } finally {
            if (!complete) {
                drain.close();
            }
        }
    }
}
