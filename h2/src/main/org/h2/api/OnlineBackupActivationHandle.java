/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.sql.SQLException;

/**
 * 连接归属的进程内 activation token handle。
 */
public interface OnlineBackupActivationHandle extends AutoCloseable {

    /**
     * @return 当前 activation 报告
     */
    OnlineBackupActivationReport getReport();

    /**
     * 永久 fence 旧 generation。
     *
     * @return commit 后报告
     * @throws SQLException token 消费失败
     */
    OnlineBackupActivationReport commitActivation()
            throws SQLException;

    /**
     * 取消切换并恢复旧 generation 准入。
     *
     * @return abort 后报告
     * @throws SQLException token 消费失败
     */
    OnlineBackupActivationReport abortActivation()
            throws SQLException;

    /**
     * 未消费时自动 abort，已消费时只释放 handle。
     *
     * @throws SQLException handle 释放失败
     */
    @Override
    void close() throws SQLException;
}
