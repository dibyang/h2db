/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import org.h2.engine.Database;
import org.h2.message.Trace;

/**
 * 系统元数据 provider 的上下文。
 * <p>
 * 该上下文用于非 MVStore 主路径迁移期，先收敛系统元数据、LOB、事务日志和
 * 临时结果等能力的所有权边界；当前不改变默认 MVStore 主路径。
 */
public interface SystemCatalogContext {

    /**
     * 获取当前数据库。
     *
     * @return 数据库
     */
    Database getDatabase();

    /**
     * 获取当前存储引擎。
     *
     * @return 存储引擎
     */
    StorageEngine getStorageEngine();

    /**
     * 获取当前存储引擎标识。
     *
     * @return 存储引擎标识
     */
    default String getStorageEngineId() {
        return getStorageEngine().getEngineId();
    }

    /**
     * 获取诊断日志。
     *
     * @return 诊断日志
     */
    Trace getTrace();

    /**
     * 判断数据库是否持久化。
     *
     * @return 持久化时返回 true
     */
    boolean isPersistent();

    /**
     * 判断数据库是否只读。
     *
     * @return 只读时返回 true
     */
    boolean isReadOnly();
}
