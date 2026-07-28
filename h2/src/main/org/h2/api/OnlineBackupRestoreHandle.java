/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.sql.SQLException;

/**
 * 连接归属的已完成 shadow restore handle。
 */
public interface OnlineBackupRestoreHandle extends AutoCloseable {

    /**
     * @return restore/validation 报告
     */
    OnlineBackupRestoreReport getReport();

    /**
     * 释放远程 handle；已发布 shadow 不会因此被删除。
     *
     * @throws SQLException handle 释放失败
     */
    @Override
    void close() throws SQLException;
}
