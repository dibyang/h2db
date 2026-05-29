/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

/**
 * 存储引擎维护能力入口。
 */
public interface StorageMaintenance {

    /**
     * 判断是否支持指定维护能力。
     *
     * @param capability 能力名称
     * @return 支持时返回 true
     */
    boolean supports(String capability);

    /**
     * 执行关闭态 compact。
     *
     * @return 维护结果
     */
    StorageMaintenanceResult compactClosed();

    /**
     * 执行维护态在线 compact。
     *
     * @return 维护结果
     */
    StorageMaintenanceResult compactOnline();

    /**
     * 执行在线空间回收。
     *
     * @return 维护结果
     */
    StorageMaintenanceResult vacuumOnline();
}
