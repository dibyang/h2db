/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.sql.SQLException;

/**
 * 连接归属的 prepared online backup handle。
 */
public interface OnlineBackupHandle extends AutoCloseable {

    /**
     * @return prepared cut 的结构化描述
     */
    OnlineBackupDescriptor getDescriptor();

    /**
     * 物化并原子发布 bundle。
     *
     * @param bundleName bundle 路径或远程受限名称
     * @return 发布报告
     * @throws SQLException materialize 或发布失败
     */
    OnlineBackupPublishReport publish(String bundleName)
            throws SQLException;

    /**
     * 中止尚未关闭的 prepared backup。
     *
     * @throws SQLException 清理失败
     */
    void abort() throws SQLException;

    /**
     * 释放 snapshot 和 participant 资源。
     *
     * @throws SQLException 清理失败
     */
    @Override
    void close() throws SQLException;
}
