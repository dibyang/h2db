/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.h2.api.PluginCapability;
import org.h2.api.StorageEngine;
import org.h2.api.StorageEngineContext;
import org.h2.api.StorageEngineProvider;

/**
 * 内置 MVStore 存储引擎 provider。
 * <p>
 * 阶段一只把现有 {@link Store} 纳入统一 provider/registry 路径，不改变磁盘格式、
 * URL 参数和打开行为。
 */
public final class MVStoreStorageEngineProvider implements StorageEngineProvider {

    /**
     * 内置 MVStore storage provider id。
     */
    public static final String ID = "mvstore";

    private static final Set<String> CAPABILITIES = new HashSet<>(Arrays.asList(
            PluginCapability.STORAGE_PERSISTENT,
            PluginCapability.STORAGE_TRANSACTIONAL,
            PluginCapability.STORAGE_MVCC,
            PluginCapability.STORAGE_BACKUP,
            PluginCapability.STORAGE_COMPACT_CLOSED));

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
        return CAPABILITIES.contains(capability);
    }

    @Override
    public StorageEngine open(StorageEngineContext context) {
        return new MVStoreStorageEngine(context, this);
    }
}
