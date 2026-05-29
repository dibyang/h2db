/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import org.h2.api.StorageEngine;

/**
 * 暴露现有 MVStore {@link Store} 的 storage engine 内部契约。
 * <p>
 * 该接口用于在插件化迁移期支持多个 MVStore-backed storage provider，同时
 * 保持既有 {@code Database.getStore()} 和 MVTable 调用链兼容。
 */
public interface MVStoreBackedStorageEngine extends StorageEngine {

    /**
     * 获取现有 MVStore Store。
     *
     * @return 现有 Store 实例
     */
    Store getStore();
}
