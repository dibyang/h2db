/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import org.h2.message.DbException;
import org.h2.mvstore.db.MVStoreStorageEngineProvider;

/**
 * 解析数据库 storage engine id 的阶段性入口。
 * <p>
 * 当前阶段不写入磁盘元数据，旧库和未声明 storage id 的数据库都按内置
 * {@code mvstore} 处理。后续真正支持第二 storage engine 时，应在这里接入
 * 持久化 id 读取和兼容校验。
 */
public final class StorageEngineResolver {

    /**
     * 旧库和缺省新库使用的 storage engine id。
     */
    public static final String DEFAULT_STORAGE_ENGINE_ID = MVStoreStorageEngineProvider.ID;

    private StorageEngineResolver() {
    }

    /**
     * 从数据库设置中解析请求的 storage engine id。
     *
     * @param settings 数据库设置
     * @return 请求的 storage engine id
     */
    public static String resolveRequested(DbSettings settings) {
        String storageEngine = settings.storageEngine;
        return storageEngine == null || storageEngine.trim().isEmpty() ? DEFAULT_STORAGE_ENGINE_ID : storageEngine;
    }

    /**
     * 解析持久化 storage engine id。
     *
     * @param persistedStorageEngineId 持久化 id；当前阶段传入 null 表示旧库或未持久化
     * @return 实际持久化 id
     */
    public static String resolvePersisted(String persistedStorageEngineId) {
        return persistedStorageEngineId == null || persistedStorageEngineId.trim().isEmpty()
                ? DEFAULT_STORAGE_ENGINE_ID : persistedStorageEngineId;
    }

    /**
     * 校验请求 id 与持久化 id 是否一致。
     *
     * @param requestedStorageEngineId 请求 id
     * @param persistedStorageEngineId 持久化 id
     */
    public static void validateMatch(String requestedStorageEngineId, String persistedStorageEngineId) {
        String persisted = resolvePersisted(persistedStorageEngineId);
        if (!persisted.equals(requestedStorageEngineId)) {
            throw DbException.getUnsupportedException("Storage engine mismatch: requested="
                    + requestedStorageEngineId + ", persisted=" + persisted);
        }
    }
}
