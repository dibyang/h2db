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
import org.h2.mvstore.MVStoreOnlineReclamationResult;
import org.h2.mvstore.MVStoreReclamationCoordinator;
import org.h2.mvstore.MVStoreReclamationStatus;

/**
 * MVStore 存储维护能力边界。
 * <p>
 * compact 和在线空间回收都通过 capability gate 暴露，调用方不应绕过
 * {@link StorageMaintenance} 直接进入 MVStore 私有维护逻辑。
 */
final class MVStoreMaintenance implements StorageMaintenance {

    private final StorageEngine engine;

    MVStoreMaintenance(StorageEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean supports(String capability) {
        return engine.supports(capability)
                && (PluginCapability.STORAGE_COMPACT_CLOSED.equals(capability)
                        || PluginCapability.STORAGE_COMPACT_ONLINE_MAINTENANCE.equals(capability)
                        || PluginCapability.STORAGE_VACUUM_ONLINE.equals(capability));
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
        if (!supports(PluginCapability.STORAGE_COMPACT_ONLINE_MAINTENANCE)) {
            return StorageMaintenanceResult.UNSUPPORTED;
        }
        ((MVStoreBackedStorageEngine) engine).getStore().compactFile(50);
        return StorageMaintenanceResult.success("COMPACT_ONLINE_FINISHED");
    }

    @Override
    public StorageMaintenanceResult vacuumOnline() {
        if (!supports(PluginCapability.STORAGE_VACUUM_ONLINE)) {
            return StorageMaintenanceResult.UNSUPPORTED;
        }
        MVStoreOnlineReclamationResult result = MVStoreReclamationCoordinator.run(
                ((MVStoreBackedStorageEngine) engine).getStore().getMvStore());
        return result.getStatus() == MVStoreReclamationStatus.SUCCESS
                ? StorageMaintenanceResult.success(result.getDiagnosticSummary())
                : StorageMaintenanceResult.skipped(result.getDiagnosticSummary());
    }
}
