/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.h2.api.PluginProvider;
import org.h2.api.StorageEngineProvider;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVStoreStorageEngineProvider;
import org.h2.store.fs.FileUtils;

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

    private static final String STORAGE_METADATA_SUFFIX = ".storage";

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
        if (persistedStorageEngineId == null || persistedStorageEngineId.trim().isEmpty()) {
            return;
        }
        String persisted = resolvePersisted(persistedStorageEngineId);
        if (!persisted.equals(requestedStorageEngineId)) {
            throw DbException.getUnsupportedException("Storage engine mismatch: requested="
                    + requestedStorageEngineId + ", persisted=" + persisted);
        }
    }

    /**
     * 查找并校验 storage provider。
     *
     * @param registry 插件注册中心
     * @param storageEngineId storage engine id
     * @param readOnly 是否只读打开
     * @return storage provider
     */
    public static StorageEngineProvider requireStorageProvider(PluginRegistry registry, String storageEngineId,
            boolean readOnly) {
        return requireStorageProvider(registry, storageEngineId, readOnly, false);
    }

    /**
     * 查找并校验 storage provider。
     *
     * @param registry 插件注册中心
     * @param storageEngineId storage engine id
     * @param readOnly 是否只读打开
     * @param allowReadOnlyDowngrade 是否允许只读降级
     * @return storage provider
     */
    public static StorageEngineProvider requireStorageProvider(PluginRegistry registry, String storageEngineId,
            boolean readOnly, boolean allowReadOnlyDowngrade) {
        PluginProvider provider = registry.findProvider(StorageEngineProvider.TYPE, storageEngineId);
        if (!(provider instanceof StorageEngineProvider)) {
            if (isMissingStorageReadOnlyDowngradeAllowed(readOnly, allowReadOnlyDowngrade)) {
                PluginProvider fallback = registry.findProvider(StorageEngineProvider.TYPE, DEFAULT_STORAGE_ENGINE_ID);
                if (fallback instanceof StorageEngineProvider) {
                    return (StorageEngineProvider) fallback;
                }
            }
            throw missingStorageProvider(storageEngineId, readOnly, allowReadOnlyDowngrade);
        }
        return (StorageEngineProvider) provider;
    }

    /**
     * 判断缺失 storage 插件时是否允许只读降级。
     *
     * @return 当前阶段固定返回 false
     */
    public static boolean isMissingStorageReadOnlyDowngradeAllowed() {
        return false;
    }

    /**
     * 判断当前打开请求是否允许缺失 storage provider 只读降级。
     *
     * @param readOnly 是否只读打开
     * @param allowReadOnlyDowngrade 是否显式允许降级
     * @return 允许时返回 true
     */
    public static boolean isMissingStorageReadOnlyDowngradeAllowed(boolean readOnly, boolean allowReadOnlyDowngrade) {
        return readOnly && allowReadOnlyDowngrade;
    }

    /**
     * 读取数据库旁路 storage 元数据。
     *
     * @param databaseName 数据库路径
     * @return 已持久化的 storage engine id；不存在时返回 null
     */
    public static String readPersistedStorageEngineId(String databaseName) {
        String fileName = storageMetadataFileName(databaseName);
        if (!FileUtils.exists(fileName)) {
            return null;
        }
        try (InputStream in = FileUtils.newInputStream(fileName)) {
            byte[] data = new byte[(int) FileUtils.size(fileName)];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            String value = new String(data, 0, offset, "UTF-8").trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            throw DbException.convertIOException(e, fileName);
        }
    }

    /**
     * 写入数据库旁路 storage 元数据。
     *
     * @param databaseName 数据库路径
     * @param storageEngineId storage engine id
     */
    public static void writePersistedStorageEngineId(String databaseName, String storageEngineId) {
        String fileName = storageMetadataFileName(databaseName);
        try (OutputStream out = FileUtils.newOutputStream(fileName, false)) {
            out.write(storageEngineId.getBytes("UTF-8"));
            out.write('\n');
        } catch (IOException e) {
            throw DbException.convertIOException(e, fileName);
        }
    }

    /**
     * 获取 storage 元数据旁路文件名。
     *
     * @param databaseName 数据库路径
     * @return 元数据文件名
     */
    public static String storageMetadataFileName(String databaseName) {
        return databaseName + STORAGE_METADATA_SUFFIX;
    }

    private static DbException missingStorageProvider(String storageEngineId, boolean readOnly,
            boolean allowReadOnlyDowngrade) {
        return DbException.getUnsupportedException("Missing storage engine provider: type="
                + StorageEngineProvider.TYPE + ", id=" + storageEngineId + ", readOnly=" + readOnly
                + ", readOnlyDowngradeAllowed="
                + isMissingStorageReadOnlyDowngradeAllowed(readOnly, allowReadOnlyDowngrade));
    }
}
