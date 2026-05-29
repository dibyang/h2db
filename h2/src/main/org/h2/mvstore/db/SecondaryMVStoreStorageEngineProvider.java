/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import org.h2.api.PluginCapability;
import org.h2.api.StorageEngine;
import org.h2.api.StorageEngineContext;
import org.h2.api.StorageEngineProvider;

/**
 * 第二个内置 storage provider。
 * <p>
 * 第一版用于验证插件化 storage 选择、持久化 id 和建表路径可以跨 provider
 * 工作。它复用现有 MVStore 物理实现，但拥有独立 provider id。
 */
public final class SecondaryMVStoreStorageEngineProvider implements StorageEngineProvider {

    /**
     * 第二个 MVStore-backed storage provider id。
     */
    public static final String ID = "mvstore_secondary";

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public boolean supports(String capability) {
        return PluginCapability.STORAGE_PERSISTENT.equals(capability)
                || PluginCapability.STORAGE_TRANSACTIONAL.equals(capability)
                || PluginCapability.STORAGE_MVCC.equals(capability)
                || PluginCapability.STORAGE_BACKUP.equals(capability);
    }

    @Override
    public StorageEngine open(StorageEngineContext context) {
        return new SecondaryMVStoreStorageEngine(context, this);
    }
}
