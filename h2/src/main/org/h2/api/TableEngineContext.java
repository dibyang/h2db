/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.util.List;
import org.h2.engine.Database;
import org.h2.message.Trace;
import org.h2.schema.Schema;

/**
 * 表引擎 provider 创建表时使用的上下文。
 */
public interface TableEngineContext {

    /**
     * 获取当前数据库。
     *
     * @return 数据库
     */
    Database getDatabase();

    /**
     * 获取目标 schema。
     *
     * @return schema
     */
    Schema getSchema();

    /**
     * 获取当前存储引擎。
     *
     * @return 存储引擎
     */
    StorageEngine getStorageEngine();

    /**
     * 获取诊断日志。
     *
     * @return 诊断日志
     */
    Trace getTrace();

    /**
     * 获取表引擎参数。
     *
     * @return 参数列表
     */
    List<String> getTableEngineParams();

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
