/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import org.h2.api.StorageEngineContext;
import org.h2.api.StorageMaintenance;

/**
 * 第二个 MVStore-backed storage engine 实例。
 */
final class SecondaryMVStoreStorageEngine implements MVStoreBackedStorageEngine {

    private final SecondaryMVStoreStorageEngineProvider provider;
    private final Store store;
    private final StorageMaintenance maintenance;

    SecondaryMVStoreStorageEngine(StorageEngineContext context, SecondaryMVStoreStorageEngineProvider provider) {
        this.provider = provider;
        this.store = new Store(context.getDatabase(), context.getFilePasswordHash());
        this.maintenance = new MVStoreMaintenance(this);
    }

    @Override
    public String getEngineId() {
        return SecondaryMVStoreStorageEngineProvider.ID;
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

    @Override
    public Store getStore() {
        return store;
    }
}
