/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 数据库级存储引擎句柄。
 */
public interface StorageEngine {

    /**
     * 获取存储引擎标识。
     *
     * @return 存储引擎标识
     */
    String getEngineId();

    /**
     * 判断是否支持指定能力。
     *
     * @param capability 能力名称
     * @return 支持时返回 true
     */
    boolean supports(String capability);

    /**
     * 刷新待持久化数据。
     */
    void flush();

    /**
     * 立即关闭存储引擎。
     */
    void closeImmediately();

    /**
     * 正常关闭存储引擎。
     * <p>
     * 默认实现先刷新待持久化数据，再执行立即关闭。需要更精细关闭流程的存储引擎
     * 可以覆盖该方法。
     */
    default void close() {
        flush();
        closeImmediately();
    }

    /**
     * 获取维护能力入口。
     *
     * @return 维护能力入口
     */
    StorageMaintenance getMaintenance();
}
