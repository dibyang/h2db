/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import org.h2.api.PluginCapability;
import org.h2.api.StorageEngine;
import org.h2.api.StorageMaintenance;
import org.h2.api.StorageMaintenanceResult;

/**
 * MVStore 存储维护能力边界。
 * <p>
 * 当前阶段只固定维护入口边界；在线回收和 crash-safe publish 留给 S2
 * 阶段实现，并通过 capability gate 明确返回不支持。
 */
final class MVStoreMaintenance implements StorageMaintenance {

    private final StorageEngine engine;

    MVStoreMaintenance(StorageEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean supports(String capability) {
        return PluginCapability.STORAGE_COMPACT_CLOSED.equals(capability) && engine.supports(capability);
    }

    @Override
    public StorageMaintenanceResult compactClosed() {
        if (!supports(PluginCapability.STORAGE_COMPACT_CLOSED)) {
            return StorageMaintenanceResult.UNSUPPORTED;
        }
        return StorageMaintenanceResult.skipped("COMPACT_CLOSED_REQUIRES_DATABASE_CLOSE");
    }

    @Override
    public StorageMaintenanceResult compactOnline() {
        return StorageMaintenanceResult.UNSUPPORTED;
    }

    @Override
    public StorageMaintenanceResult vacuumOnline() {
        return StorageMaintenanceResult.UNSUPPORTED;
    }
}
