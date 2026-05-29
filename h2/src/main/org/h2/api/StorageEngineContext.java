/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import org.h2.engine.Database;
import org.h2.engine.DbSettings;
import org.h2.message.Trace;

/**
 * 存储引擎打开数据库时使用的上下文。
 */
public interface StorageEngineContext {

    /**
     * 获取当前数据库。
     *
     * @return 数据库
     */
    Database getDatabase();

    /**
     * 获取数据库路径。
     *
     * @return 数据库路径
     */
    String getDatabasePath();

    /**
     * 获取文件密码密钥。
     *
     * @return 文件密码密钥
     */
    byte[] getFilePasswordHash();

    /**
     * 获取数据库设置。
     *
     * @return 数据库设置
     */
    DbSettings getSettings();

    /**
     * 判断是否只读打开。
     *
     * @return 只读时返回 true
     */
    boolean isReadOnly();

    /**
     * 获取诊断日志。
     *
     * @return 诊断日志
     */
    Trace getTrace();
}
