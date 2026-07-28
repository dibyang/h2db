/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine.backup;

import org.h2.engine.backup.DatabaseOperationGate.TransactionDrain;

/**
 * 已完成 transaction drain 的进程内单次消费 token。
 * <p>
 * Token 不是 active generation 的持久化事实来源。ADB 必须先更新自己的
 * durable active pointer，再调用 {@link #commitActivation()}；切换失败时调用
 * {@link #abortActivation()}或关闭 token。
 */
public final class ActivationToken implements AutoCloseable {

    private final TransactionDrain drain;
    private ActivationReport report;

    ActivationToken(TransactionDrain drain, ActivationReport report) {
        this.drain = drain;
        this.report = report;
    }

    /**
     * 永久 fence 旧 generation。
     * <p>
     * 相同消费可安全重试；已 abort 的 token 不能再 commit。
     *
     * @return commit 后报告
     */
    public synchronized ActivationReport commitActivation() {
        if (report.getStatus() == ActivationStatus.COMMITTED) {
            return report;
        }
        if (report.getStatus() == ActivationStatus.ABORTED) {
            throw new IllegalStateException(
                    "Activation token was already aborted");
        }
        drain.fence(report.getOldGenerationId().toString());
        report = report.transition(ActivationStatus.COMMITTED,
                ActivationReason.GENERATION_FENCED);
        return report;
    }

    /**
     * 取消切换并恢复旧 generation 的事务准入。
     * <p>
     * 相同消费可安全重试；已 commit 的 token 不能再 abort。
     *
     * @return abort 后报告
     */
    public synchronized ActivationReport abortActivation() {
        if (report.getStatus() == ActivationStatus.ABORTED) {
            return report;
        }
        if (report.getStatus() == ActivationStatus.COMMITTED) {
            throw new IllegalStateException(
                    "Activation token was already committed");
        }
        drain.close();
        report = report.transition(ActivationStatus.ABORTED,
                ActivationReason.ACTIVATION_ABORTED);
        return report;
    }

    /**
     * @return 当前不可变报告
     */
    public synchronized ActivationReport getReport() {
        return report;
    }

    /**
     * 未消费 token 关闭时等价于 abort；已消费 token 关闭不改变结果。
     */
    @Override
    public synchronized void close() {
        if (report.getStatus() == ActivationStatus.PREPARED) {
            abortActivation();
        }
    }
}
