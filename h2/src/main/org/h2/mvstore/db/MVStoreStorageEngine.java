/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import org.h2.api.StorageEngine;
import org.h2.api.StorageEngineContext;
import org.h2.api.StorageMaintenance;

/**
 * MVStore 存储引擎适配器。
 * <p>
 * 该类是插件化改造的过渡层，内部仍持有现有 {@link Store}，用于保持
 * {@code Database.getStore()} 以及现有 MVTable 调用链兼容。
 */
public final class MVStoreStorageEngine implements StorageEngine {

    private final MVStoreStorageEngineProvider provider;
    private final Store store;
    private final StorageMaintenance maintenance;

    MVStoreStorageEngine(StorageEngineContext context, MVStoreStorageEngineProvider provider) {
        this.provider = provider;
        this.store = new Store(context.getDatabase(), context.getFilePasswordHash());
        this.maintenance = new MVStoreMaintenance(this);
    }

    @Override
    public String getEngineId() {
        return MVStoreStorageEngineProvider.ID;
    }

    @Override
    public boolean supports(String capability) {
        return provider.supports(capability);
    }

    @Override
    public void flush() {
        store.flush();
    }

    @Override
    public void closeImmediately() {
        store.closeImmediately();
    }

    @Override
    public StorageMaintenance getMaintenance() {
        return maintenance;
    }

    /**
     * 获取现有 MVStore Store。
     *
     * @return 现有 Store 实例
     */
    public Store getStore() {
        return store;
    }
}
